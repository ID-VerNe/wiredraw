package com.rea.mtpimport;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.LruCache;


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

    private final LruCache<Integer, Bitmap> cache = new LruCache<Integer, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(Integer key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private final LruCache<Integer, Bitmap> previewCache =
            new LruCache<Integer, Bitmap>(PREVIEW_BYTES) {
                @Override
                protected int sizeOf(Integer key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private final LruCache<Integer, Bitmap> fullCache =
            new LruCache<Integer, Bitmap>(FULL_BYTES) {
                @Override
                protected int sizeOf(Integer key, Bitmap value) {
                    return value.getByteCount();
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
            epoch++;
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
     * 代际校验 + 落缓存（三档加载共用）。解码期间换了会话 / 清过缓存时返回 null：
     * 旧句柄的位图不得写进新会话，否则重扫后会按 handle 取到上一会话的照片（评审阻断项 2）。
     */
    private Bitmap commit(int epochAtStart, Integer key,
                          LruCache<Integer, Bitmap> cache, Bitmap bmp) {
        synchronized (epochLock) {
            if (epoch != epochAtStart) return null;
            cache.put(key, bmp);
        }
        return bmp;
    }

    /** 同名 JPG 优先、缺失/失败回退 ARW（`V-32`）；每次都单独兜异常，保证回退可达。 */
    private Bitmap fromCamera(PhotoWall.Unit unit) {
        MtpSession s = session;
        if (s == null) return null;

        if (unit.jpg != null) {
            try {
                Bitmap b = thumbnail(s, unit.jpg.handle);
                if (b != null) return b;
            } catch (Throwable t) {
                Log.w(TAG, "同名 JPG 缩略图失败 handle=" + unit.jpg.handle + "：" + t);
            }
        }
        if (unit.arw != null) {
            try {
                return thumbnail(s, unit.arw.handle);
            } catch (Throwable t) {
                Log.w(TAG, "ARW 缩略图失败 handle=" + unit.arw.handle + "：" + t);
            }
        }
        return null;
    }

    private Bitmap thumbnail(MtpSession s, int handle) {
        // 锁内只做相机调用；解码放到锁外，不阻塞导入的 importFile。
        byte[] bytes = fetch(s, handle);
        return bytes == null ? null : decode(bytes, MAX_EDGE);
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
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
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
