package com.rea.mtpimport;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.LruCache;

/**
 * 照片墙缩略图（issue #59）。
 *
 * 真实 MTP 路径按顺序尝试：
 * 1. 相机对 ARW 句柄的 `getThumbnail`（GetThumb 只读，不取整张 24 MP RAW）；
 * 2. 同名同目录 JPG 的 `getThumbnail`（索尼机身默认同时写 JPEG，回退不需要相机额外能力）；
 * 3. 都拿不到就返回 null，界面退回 V-19 的灰块 + 文件名降级形态。
 *
 * `getThumbnail` 对 ARW 的支持**未在真机验证**（2026-11-06 相机被另一会话探针占用）；
 * 因此这里把能力当成运行时可失败：任何 Throwable 都只记 logcat 并回退，不让它把照片墙打崩。
 *
 * 并发：所有相机调用都在 {@code session.ioLock()} 下串行，与导入的 `importFile` /
 * `getPartialObject` 共用同一把锁；内存用 24 MiB 的 LruCache，离屏/被淘汰的位图不会常驻
 * （用户要求「不显示的就从内存删掉」），会话关闭时 {@link #clear()} 整块释放。
 */
public final class ThumbnailStore {

    private static final String TAG = "MtpImport";
    /** 24 MiB ≈ 80 张 512 px 缩略图，够覆盖一屏 3 列 × 7 行的来回滚动。 */
    private static final int CACHE_BYTES = 24 << 20;
    /** 网格单元约 344 px 宽；按 512 长边解码，避免大图整张进内存。 */
    private static final int MAX_EDGE = 512;

    private final LruCache<Integer, Bitmap> cache = new LruCache<Integer, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(Integer key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private volatile MtpSession session;
    private volatile Catalog catalog;
    private volatile PhotoPreview demo;

    public void setSession(MtpSession s) {
        this.session = s;
    }

    public void setCatalog(Catalog c) {
        this.catalog = c;
    }

    public void setDemo(PhotoPreview p) {
        this.demo = p;
    }

    /** 会话关闭 / 离开应用时整体释放；缓存的句柄只在当前会话里有效。 */
    public void clear() {
        cache.evictAll();
        session = null;
        catalog = null;
        demo = null;
    }

    /**
     * 只摘掉会话，保留已解码的缓存。
     *
     * 扫描取消后上一轮完整照片墙仍会留在屏幕上（`backToCatalog` 的既有口径），
     * 这时把缓存一起清掉会让整墙退回灰块；已解码的位图与句柄无关，可以继续显示。
     */
    public void detachSession() {
        session = null;
        catalog = null;
    }

    /** 只查缓存，不触发相机调用（Compose 首帧用）。 */
    public Bitmap cached(int handle) {
        return cache.get(handle);
    }

    public int cachedCount() {
        return cache.size();
    }

    /**
     * 读一张缩略图。**必须在 IO 线程调用**；返回 null 表示该条目没有可用预览。
     * 同一 handle 重复调用会命中缓存。
     */
    public Bitmap load(Catalog.Entry entry) {
        if (entry == null) return null;
        Bitmap hit = cache.get(entry.handle);
        if (hit != null) return hit;

        Bitmap decoded = null;
        try {
            PhotoPreview d = demo;
            if (d != null) {
                decoded = scaleForGrid(d.previewFor(entry));
            }
            if (decoded == null) decoded = fromCamera(entry);
        } catch (Throwable t) {
            Log.w(TAG, "缩略图读取失败 handle=" + entry.handle + "：" + t);
        }
        if (decoded != null) cache.put(entry.handle, decoded);
        return decoded;
    }

    private Bitmap fromCamera(Catalog.Entry entry) {
        MtpSession s = session;
        if (s == null) return null;

        Bitmap b = thumbnail(s, entry.handle);
        if (b != null) return b;

        // 回退：同名同目录的 JPG（不额外枚举，Catalog 里已经有）。
        Catalog c = catalog;
        Catalog.Entry jpg = c == null ? null : c.findSiblingJpeg(entry);
        if (jpg != null) {
            b = thumbnail(s, jpg.handle);
            if (b != null) return b;
        }
        return null;
    }

    private Bitmap thumbnail(MtpSession s, int handle) {
        s.ioLock().lock();
        try {
            // 扫描取消时扫描线程会在收尾里关会话：摘掉会话之后到达的请求直接放弃，
            // 不让 getThumbnail 撞上 mtp.close()。
            if (session != s) return null;
            byte[] bytes = s.device().getThumbnail(handle);
            if (bytes == null || bytes.length == 0) return null;
            return decode(bytes);
        } finally {
            s.ioLock().unlock();
        }
    }

    private static Bitmap decode(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        while (bounds.outWidth / sample > MAX_EDGE || bounds.outHeight / sample > MAX_EDGE) {
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
