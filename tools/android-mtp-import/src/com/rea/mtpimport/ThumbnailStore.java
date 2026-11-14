package com.rea.mtpimport;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.util.Log;
import android.util.LruCache;
import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;


/**
 * 照片墙缩略图与看图页预览（issue #59 / #61 / #83）。
 *
 * 真实 MTP 路径的像素来源顺序（`V-32`）：
 * 1. 单元内的**同名 JPG** `getThumbnail`（JPG 小预览，不取整张 1.8 MB）；
 * 2. JPG 缺失或取图失败时回退 ARW `getThumbnail`；
 * 3. 都拿不到就返回 null，界面退回 {@code V-19} 灰块 + 文件名 / {@code U-51} 胶囊降级形态。
 *
 * 网格**只走 getThumbnail**，绝不为滚动拉整张 JPG（那会把一次浏览放大到 923 MB / 43 秒）。
 * 整张 JPG 只在 {@code photo} 页停留后由 {@link #loadFullJpeg} 取一次，失败保留缩略图；
 * 整张 ARW 任何场景都不下载。
 *
 * 唯一的例外是**姿态**（issue #367 第二代）：MTP 内嵌缩略图不带 EXIF，网格要判断竖拍就只能看
 * 同名 JPG 的文件头，因此每个单元允许**一次** {@link ExifOrientation#HEAD_BYTES} 的头部读
 * （8 KiB，不到整张的 1%），结果按句柄进有界姿态缓存，重复滚动不再读相机。
 * 头部读失败或读不出 EXIF 就退回「不旋转」，与修复前行为一致，不做任何猜测性旋转。
 *
 * `getThumbnail` 对 ARW 的支持**未在真机验证**（2026-11-06 相机被另一会话探针占用）；
 * 因此这里把能力当成运行时可失败：任何 Throwable 都只记 logcat 并回退，不让界面崩。
 *
 * 并发：所有相机调用都在 {@code session.ioLock()} 下串行，与导入的 `importFile` /
 * `getPartialObject` / 会话探活共用同一把锁；内存分三档有界：
 * 网格 24 MiB、看图页缩略图 8 MiB、整张 JPG 24 MiB（2048 长边约 11 MiB / 张）。
 * 离屏/被淘汰的位图不会常驻（用户要求「不显示的就从内存删掉」），会话关闭时
 * {@link #clear()} 整块释放。
 */
public final class ThumbnailStore {

    private static final String TAG = "MtpImport";
    /** 24 MiB ≈ 36 张 512 px 缩略图（512×341 ARGB_8888 约 0.67 MiB/张）。 */
    private static final int CACHE_BYTES = 24 << 20;
    /** 网格单元约 344 px 宽；按 512 长边解码，避免大图整张进内存。 */
    private static final int MAX_EDGE = 512;
    /** 看图页只保留最近几张缩略图：验证「连续打开两次不重复读相机」，又不让离屏图常驻。 */
    private static final int PREVIEW_BYTES = 8 << 20;
    /** 看图页整张 JPG 按 2048 长边解码（约 11 MiB / 张），绝不按 24 MP 原始分辨率进内存。 */
    private static final int FULL_EDGE = 2048;
    /** 2 张整张 JPG 的缓存，够返回上一张再前进时直接命中，又不至于长期吃内存。 */
    private static final int FULL_BYTES = 24 << 20;
    /** 姿态缓存的条目上限：一次完整浏览 671 单元也装得下，且不随照片尺寸变化。 */
    private static final int ORIENTATION_ENTRIES = 4096;

    /**
     * 三档缓存共用的计量口径：一条位图占多少字节（issue #307）。
     *
     * 三档容量不同（24 / 8 / 24 MiB），被复制的从来不是容量而是「怎么算一张图的大小」。
     * 计量只在这里定义一次，否则要换成 {@code getAllocationByteCount()} 或加
     * {@code Math.max(1, ...)} 时只会改到其中一档，三档的实际容量上限悄悄分叉。
     */
    private static final class ByteCountCache extends LruCache<Integer, Bitmap> {
        ByteCountCache(int maxBytes) {
            super(maxBytes);
        }

        @Override
        protected int sizeOf(Integer key, Bitmap value) {
            return value.getByteCount();
        }
    }

    public interface Callback {
        void onLoaded(Bitmap bitmap);
    }

    private static final class LoadRequest {
        final PhotoWall.Unit unit;
        final BooleanSupplier isCancelled;
        final Callback callback;

        LoadRequest(PhotoWall.Unit unit, BooleanSupplier isCancelled, Callback callback) {
            this.unit = unit;
            this.isCancelled = isCancelled;
            this.callback = callback;
        }

        boolean isCancelled() {
            return isCancelled != null && isCancelled.getAsBoolean();
        }
    }

    private final ArrayDeque<LoadRequest> queue = new ArrayDeque<>();
    private final Object queueLock = new Object();
    private boolean workerActive = false;
    private final ExecutorService loaderExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ThumbnailLoader");
        t.setDaemon(true);
        return t;
    });

    private final LruCache<Integer, Bitmap> cache = new ByteCountCache(CACHE_BYTES);

    private final LruCache<Integer, Bitmap> previewCache = new ByteCountCache(PREVIEW_BYTES);

    private final LruCache<Integer, Bitmap> fullCache = new ByteCountCache(FULL_BYTES);

    /**
     * 单元姿态缓存：句柄 → EXIF Orientation（1..8，issue #367 第二代修复）。
     *
     * 按**条目数**定容而不是字节数：一个值就是一个 int，没有「一张图多大」的计量问题。
     * 句柄不跨会话稳定，所以 {@link #setSession} 与 {@link #clear} 都要清；
     * 宁可多读一次 8 KiB 头，也不能把上一会话的姿态套到新会话的同号句柄上转错方向。
     */
    private final LruCache<Integer, Integer> orientationCache = new LruCache<Integer, Integer>(ORIENTATION_ENTRIES) {
        @Override
        protected int sizeOf(Integer key, Integer value) {
            return 1;
        }
    };

    /** 缓存代际：清空 / 换会话时自增，挡住旧会话的在飞请求写进新会话缓存。 */
    private final Object epochLock = new Object();
    private volatile int epoch;

    private volatile MtpSession session;
    private volatile PhotoPreview demo;

    public void setSession(MtpSession s) {
        // 先换会话再自增代际：在飞请求若捕获到旧代际，put 会被代际检查挡下；
        // 捕获到新代际时看到的必然是已经换好的会话。
        this.session = s;
        synchronized (epochLock) {
            // 清姿态缓存必须与自增代际在**同一个临界区**（与 clear 同构）：只清不锁会留一个窗口——
            // 在飞请求带着旧 startEpoch 已经拿到头部字节时，只要它的 commit 落在
            // 「清完 → 自增前」，代际检查仍通过，上一会话的姿态就写进新会话同号句柄且不会自愈。
            epoch++;
            orientationCache.evictAll();
        }
    }

    public void setDemo(PhotoPreview p) {
        this.demo = p;
    }

    /** 会话关闭 / 离开应用时整体释放；缓存的句柄只在当前会话里有效。 */
    public void clear() {
        // 先摘会话再自增代际 + 清缓存：避免「新代际 + 旧会话」被在飞请求写进缓存。
        session = null;
        demo = null;
        synchronized (epochLock) {
            epoch++;
            cache.evictAll();
            previewCache.evictAll();
            fullCache.evictAll();
            orientationCache.evictAll();
        }
        synchronized (queueLock) {
            queue.clear();
        }
    }

    /**
     * 只摘掉会话，保留已解码的缓存。
     *
     * 扫描取消后上一轮完整照片墙仍会留在屏幕上（`backToCatalog` 的既有口径），
     * 这时把缓存一起清掉会让整墙退回灰块；已解码的位图与句柄无关，可以继续显示。
     */
    public void detachSession() {
        session = null;
    }

    /** 只查缓存，不触发相机调用（Compose 首帧用）；key 是单元的预览句柄。 */
    public Bitmap cached(int handle) {
        return cache.get(handle);
    }

    /**
     * 格子离屏（移出组合）时调用：把该句柄的位图立即移出缓存，
     * 满足「不显示的就从内存删掉」；重新滚回时允许重新取图。
     */
    public void evict(int handle) {
        cache.remove(handle);
    }

    /**
     * 读一张网格缩略图。**必须在 IO 线程调用**；返回 null 表示该单元没有可用预览。
     * 同一单元重复调用会命中缓存（键 = 预览句柄）。
     */
    public Bitmap load(PhotoWall.Unit unit) {
        if (unit == null) return null;
        int key = unit.previewHandle();
        Bitmap hit = cache.get(key);
        if (hit != null) return hit;

        int startEpoch = epoch;
        Bitmap decoded = null;
        try {
            decoded = scaleForGrid(demoPreview(unit.preview()));
            if (decoded == null) decoded = fromCamera(unit);
        } catch (Throwable t) {
            Log.w(TAG, "缩略图读取失败 unit=" + unit.relPath + "：" + t);
        }
        if (decoded == null) return null;
        return commit(startEpoch, key, cache, decoded);
    }

    /**
     * 视口感知异步取图（issue #373）：支持生命周期感知、LIFO 优先队列与离屏取消。
     * 若已命中缓存直接回调，否则入队；后入队的任务（最新进入视口）优先获取 USB 读图机会。
     */
    public void loadAsync(PhotoWall.Unit unit, BooleanSupplier isCancelled, Callback callback) {
        if (unit == null || callback == null) return;
        int key = unit.previewHandle();
        Bitmap hit = cache.get(key);
        if (hit != null) {
            callback.onLoaded(hit);
            return;
        }
        if (isCancelled != null && isCancelled.getAsBoolean()) {
            callback.onLoaded(null);
            return;
        }
        synchronized (queueLock) {
            queue.removeIf(r -> r.unit.previewHandle() == key);
            queue.addFirst(new LoadRequest(unit, isCancelled, callback));
            if (!workerActive) {
                workerActive = true;
                loaderExecutor.execute(this::drainQueue);
            }
        }
    }

    private void drainQueue() {
        while (true) {
            LoadRequest req;
            synchronized (queueLock) {
                req = queue.pollFirst();
                if (req == null) {
                    workerActive = false;
                    return;
                }
            }
            if (req.isCancelled()) {
                req.callback.onLoaded(null);
                continue;
            }
            int key = req.unit.previewHandle();
            Bitmap hit = cache.get(key);
            if (hit != null) {
                req.callback.onLoaded(hit);
                continue;
            }
            Bitmap bmp = load(req.unit);
            req.callback.onLoaded(bmp);
        }
    }

    /**
     * 看图页首帧只读缓存（整张 JPG 优先，其次预览缓存、网格缓存），不触发相机调用。
     *
     * demo 有整图（≤1440），网格缓存里只有降采样版（≤512），所以 demo 未命中预览缓存时
     * 返回 null，让 {@link #loadPreview} 在 IO 线程取整图；真实路径仍可先显示网格缩略图。
     */
    public Bitmap cachedPreview(PhotoWall.Unit unit) {
        if (unit == null) return null;
        if (unit.hasJpg()) {
            Bitmap full = fullCache.get(unit.jpg.handle);
            if (full != null) return full;
        }
        int key = unit.previewHandle();
        Bitmap hit = previewCache.get(key);
        if (hit != null) return hit;
        if (demo != null) return null;
        return cache.get(key);
    }

    /**
     * 读一张看图页缩略图（issue #61）。**必须在 IO 线程调用**；返回 null 表示没有可用像素，
     * 界面保持 {@code U-51} 的胶囊形态。
     *
     * 像素来源与照片墙同一条只读链路（同名 JPG → ARW），分辨率上限为
     * min(`getThumbnail` 源分辨率, `MAX_EDGE`=512)；这里**不下载整张 RAW**。
     * 整张 JPG 由 {@link #loadFullJpeg} 在停留后补。
     */
    public Bitmap loadPreview(PhotoWall.Unit unit) {
        if (unit == null) return null;
        Bitmap hit = cachedPreview(unit);
        if (hit != null) return hit;

        int startEpoch = epoch;
        Bitmap decoded = null;
        try {
            decoded = demoPreview(unit.preview());   // demo 素材即整图，不降采样
            if (decoded == null) decoded = fromCamera(unit);
        } catch (Throwable t) {
            Log.w(TAG, "看图页预览失败 unit=" + unit.relPath + "：" + t);
        }
        if (decoded == null) return null;
        return commit(startEpoch, unit.previewHandle(), previewCache, decoded);
    }

    /**
     * 看图页停留后补同名 JPG 整张（issue #83，`V-32`）。**必须在 IO 线程调用**。
     *
     * 只取单元里已存在的同名 JPG（≤2 MB 量级）；没有 JPG、取图失败或会话已换都返回 null，
     * 调用方保留缩略图。**绝不**为看图页拉整张 ARW（23.8 MB / 张，与省带宽的目标相反）。
     */
    public Bitmap loadFullJpeg(PhotoWall.Unit unit) {
        if (unit == null || unit.jpg == null) return null;
        int key = unit.jpg.handle;
        Bitmap hit = fullCache.get(key);
        if (hit != null) return hit;

        int startEpoch = epoch;
        Bitmap decoded = null;
        try {
            if (demo != null) {
                decoded = demoPreview(unit.jpg);   // demo 素材即整图（1440 长边）
            } else {
                byte[] bytes = fetchFull(unit.jpg);
                if (bytes != null) decoded = decode(bytes, FULL_EDGE);
            }
        } catch (Throwable t) {
            Log.w(TAG, "整张 JPG 读取失败 handle=" + unit.jpg.handle + "：" + t);
        }
        if (decoded == null) return null;
        return commit(startEpoch, key, fullCache, decoded);
    }

    /** demo 模式下的像素来源；非 demo 返回 null，让调用方回退相机。 */
    private Bitmap demoPreview(Catalog.Entry entry) {
        PhotoPreview d = demo;
        return d == null ? null : d.previewFor(entry);
    }

    /**
     * 代际校验 + 落缓存（三档位图加载与姿态缓存共用）。解码 / 读头期间换了会话或清过缓存时返回 null：
     * 旧句柄的位图与姿态都不得写进新会话，否则重扫后会按 handle 取到上一会话的照片（评审阻断项 2），
     * 姿态还会把新会话的同号句柄转错方向。
     *
     * 之所以做成泛型而不是给姿态缓存再开一处 epochLock 同步块：
     * 「epochLock 只在 setSession / clear / commit 三处使用」是既有护栏（`CatalogCases` 按文本钉住），
     * 新开一处就得放宽那条护栏，等于把「所有缓存写入都过代际校验」这条约束拆掉。
     */
    private <T> T commit(int epochAtStart, Integer key, LruCache<Integer, T> cache, T value) {
        synchronized (epochLock) {
            if (epoch != epochAtStart) return null;
            cache.put(key, value);
        }
        return value;
    }

    /** 同名 JPG 优先、缺失/失败回退 ARW（`V-32`）；每次都单独兜异常，保证回退可达。 */
    private Bitmap fromCamera(PhotoWall.Unit unit) {
        MtpSession s = session;
        if (s == null) return null;

        if (unit.jpg != null) {
            try {
                // 同名 JPG 既当像素源，也当姿态来源（issue #367 第二代）。
                Bitmap b = thumbnail(s, unit.jpg, unit.jpg);
                if (b != null) return b;
            } catch (Throwable t) {
                Log.w(TAG, "同名 JPG 缩略图失败 handle=" + unit.jpg.handle + "：" + t);
            }
        }
        if (unit.arw != null) {
            try {
                // ARW 的内嵌缩略图同样不带 APP1：姿态仍从单元里的同名 JPG 取。
                return thumbnail(s, unit.arw, unit.jpg);
            } catch (Throwable t) {
                Log.w(TAG, "ARW 缩略图失败 handle=" + unit.arw.handle + "：" + t);
            }
        }
        return null;
    }

    /**
     * 取一张缩略图并纠正姿态。
     *
     * @param entry           像素来源句柄
     * @param orientationFrom 姿态来源（单元里的同名 JPG）；null 表示没有可用来源，不纠偏
     */
    private Bitmap thumbnail(MtpSession s, Catalog.Entry entry, Catalog.Entry orientationFrom) {
        // 锁内只做相机调用；解码放到锁外，不阻塞导入的 importFile。
        byte[] bytes = fetch(s, entry.handle);
        if (bytes == null) return null;
        return applyUnitOrientation(decode(bytes, MAX_EDGE), bytes, s, orientationFrom);
    }

    /**
     * 网格 / 看图页缩略图的姿态补正（issue #367 第二代修复）。
     *
     * 第一代的错在**姿态来源**：`decode` 只读传入字节自身的 EXIF，而 MTP 内嵌缩略图不带 APP1，
     * 于是 {@code getExifOrientation} 恒回落「正常」，竖拍照片在网格里一律横躺；
     * 同一张图进大图又正立，是因为 `fetchFull` 拿的是带 `Orientation=8` 的完整 JPG。
     *
     * 这里按「谁说得出姿态」分两路：
     * - 字节源**自带** EXIF → `decode` 已经按它转过，直接返回，绝不二次旋转；
     * - 字节源没有 EXIF（`NO_EXIF`）→ 回到同名 JPG 的**文件头**取一次姿态（8 KiB，不是整张）。
     */
    private Bitmap applyUnitOrientation(Bitmap decoded, byte[] bytes,
                                        MtpSession s, Catalog.Entry orientationFrom) {
        if (decoded == null) return null;
        if (ExifOrientation.parse(bytes) != ExifOrientation.NO_EXIF) return decoded;
        if (orientationFrom == null) return decoded;
        int orientation = unitOrientation(s, orientationFrom);
        Matrix matrix = orientationToMatrix(orientation);
        if (matrix == null) return decoded;
        try {
            Bitmap rotated = Bitmap.createBitmap(decoded, 0, 0,
                    decoded.getWidth(), decoded.getHeight(), matrix, true);
            if (rotated != decoded) {
                decoded.recycle();
            }
            return rotated;
        } catch (Throwable t) {
            Log.w(TAG, "缩略图姿态纠偏失败 handle=" + orientationFrom.handle + "：" + t);
            return decoded;
        }
    }

    /**
     * 单元的 EXIF Orientation：先查姿态缓存，再读一次同名 JPG 的头部。
     *
     * 读失败**不缓存**（下一次还能重试），读成功但没 EXIF 就按正常朝向缓存，
     * 避免每次滚到这张都再发一次 MTP 读。
     *
     * 写入必须过**代际校验**（走 {@link #commit}，与三档位图同一把守卫）：`fetchHead` 在锁外，
     * 期间会话可能已经换掉，而句柄不跨会话稳定——不挡的话，上一会话的姿态会落到新会话的同号句柄上，
     * 把正立的照片转歪。{@link #setSession} / {@link #clear} 里的 `evictAll` 只能清已写入的，
     * 挡不住在飞的这一次。
     */
    private int unitOrientation(MtpSession s, Catalog.Entry jpg) {
        Integer hit = orientationCache.get(jpg.handle);
        if (hit != null) return hit;
        int startEpoch = epoch;
        byte[] head = fetchHead(s, jpg);
        if (head == null) return ExifOrientation.NORMAL;
        int raw = ExifOrientation.parse(head);
        int orientation = raw == ExifOrientation.NO_EXIF ? ExifOrientation.NORMAL : raw;
        commit(startEpoch, jpg.handle, orientationCache, orientation);   // 会话已换则不写缓存
        return orientation;
    }

    /**
     * 只读同名 JPG 的文件头（{@link ExifOrientation#HEAD_BYTES}），用于取姿态。
     *
     * 与 {@link #fetchFull} 同一把 `ioLock`，一次调用即返回；`offset` 恒为 0——
     * `getPartialObject` 的 offset 在真机上并非总是生效（续传停用已记该结论），
     * 而 offset=0 在两种语义下都落在「从对象头开始」，因此不受影响。
     */
    private byte[] fetchHead(MtpSession s, Catalog.Entry entry) {
        if (entry == null || entry.size <= 0) return null;
        int want = (int) Math.min(ExifOrientation.HEAD_BYTES, entry.size);
        if (want <= 0) return null;
        s.ioLock().lock();
        try {
            if (session != s) return null;
            byte[] buffer = new byte[want];
            // getPartialObject 返回 long；超量返回按请求长度截断，防越界。
            long got = s.device().getPartialObject(entry.handle, 0, want, buffer);
            if (got <= 0) return null;
            int n = (int) Math.min(got, (long) want);
            if (n >= want) return buffer;
            byte[] trimmed = new byte[n];
            System.arraycopy(buffer, 0, trimmed, 0, n);
            return trimmed;
        } catch (Throwable t) {
            Log.w(TAG, "头部读取失败 handle=" + entry.handle + "：" + t);
            return null;
        } finally {
            s.ioLock().unlock();
        }
    }

    private byte[] fetch(MtpSession s, int handle) {
        s.ioLock().lock();
        try {
            // 扫描取消时扫描线程会在收尾里关会话：摘掉会话之后到达的请求直接放弃，
            // 不让 getThumbnail 撞上 mtp.close()。
            if (session != s) return null;
            return s.device().getThumbnail(handle);
        } finally {
            s.ioLock().unlock();
        }
    }

    /** 整张文件分块读（1 MiB / 块），只用于同名 JPG；句柄在锁内才有效。 */
    private byte[] fetchFull(Catalog.Entry entry) {
        MtpSession s = session;
        if (s == null || entry.size <= 0) return null;
        s.ioLock().lock();
        try {
            if (session != s) return null;
            return RangeRead.read(entry.size, 1 << 20,
                    (offset, want, buffer) ->
                            s.device().getPartialObject(entry.handle, offset, want, buffer));
        } catch (java.io.IOException e) {
            Log.w(TAG, "整张 JPG 分块读取失败 handle=" + entry.handle + "：" + e);
            return null;
        } finally {
            s.ioLock().unlock();
        }
    }

    /**
     * 解码：长边压到 {@code maxEdge}（网格 {@link #MAX_EDGE}=512 / 看图页整张 {@link #FULL_EDGE}=2048，
     * 避免 24 MP 原始分辨率约 96 MiB 进内存）。
     *
     * 采样循环按「缩放后仍大于 maxEdge」加倍；改成 `>=` 会多缩一档，边界保持原样。
     */
    private static Bitmap decode(byte[] bytes, int maxEdge) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        if (decoded == null) return null;
        return applyExifOrientation(decoded, bytes);
    }

    /**
     * 读取 EXIF Orientation 姿态并旋转纠偏（issue #367, #368）。
     *
     * 索尼 a7C 机身传感器原始点阵恒为横向（3:2），竖拍姿态记录在 EXIF TAG_ORIENTATION。
     * 若非正常朝向，通过 Matrix 进行旋转/镜像变换；生成新位图后显式回收原位图，防止内存泄漏。
     */
    private static Bitmap applyExifOrientation(Bitmap decoded, byte[] bytes) {
        if (decoded == null || bytes == null || bytes.length == 0) return decoded;
        try {
            int orientation = getExifOrientation(bytes);
            Matrix matrix = orientationToMatrix(orientation);
            if (matrix == null) return decoded;
            Bitmap rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
            if (rotated != decoded) {
                decoded.recycle();
            }
            return rotated;
        } catch (Throwable t) {
            Log.w(TAG, "EXIF 旋转纠正失败：" + t);
            return decoded;
        }
    }

    private static int getExifOrientation(byte[] bytes) {
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(bytes);
            ExifInterface exif = new ExifInterface(in);
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Throwable t) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static Matrix orientationToMatrix(int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                return matrix;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                return matrix;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270);
                return matrix;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.postScale(-1, 1);
                return matrix;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.postScale(1, -1);
                return matrix;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.postRotate(90);
                matrix.postScale(-1, 1);
                return matrix;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.postRotate(270);
                matrix.postScale(-1, 1);
                return matrix;
            default:
                return null;
        }
    }

    /** demo 素材是 1440 长边的整图，进网格前先降到 512，别把 5.5 MB/张原样塞进 24 MiB 缓存。 */
    private static Bitmap scaleForGrid(Bitmap src) {
        if (src == null) return null;
        int longEdge = Math.max(src.getWidth(), src.getHeight());
        if (longEdge <= MAX_EDGE) return src;
        float ratio = (float) MAX_EDGE / longEdge;
        int w = Math.max(1, Math.round(src.getWidth() * ratio));
        int h = Math.max(1, Math.round(src.getHeight() * ratio));
        return Bitmap.createScaledBitmap(src, w, h, true);
    }
}
