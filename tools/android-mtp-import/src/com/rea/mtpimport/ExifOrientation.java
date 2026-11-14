package com.rea.mtpimport;

/**
 * 轻量 EXIF Orientation 解析（issue #367 第二代修复）。
 *
 * 为什么自己解析而不是直接用 {@code android.media.ExifInterface}：网格缩略图的字节源是
 * MTP 内嵌缩略图，那份 JPEG **不带 APP1**，`ExifInterface` 只能回落「正常」；
 * 正确的姿态在**完整 JPG 的头部**，而完整 JPG 不能为滚动下载（红线见 `ThumbnailStore` 头注释）。
 * 所以这里只需要「从任意一段文件头里把 `0x0112` 挖出来」这一件事，
 * 用纯 JDK 实现，才能进 JVM 自检做真值测试（本类不 import `android.*`）。
 *
 * 三态语义（调用方必须按此判断，别把 {@link #NO_EXIF} 当成「正常」）：
 * - {@link #NO_EXIF}：**没找到 EXIF 段**，或标记链 / IFD 在给定字节内不完整——本字节源无法判断姿态；
 * - {@code 1}：段在但 `0x0112` 缺失或不是合法 1..8，按 EXIF 规范视为正常朝向；
 * - {@code 2..8}：真实姿态值。
 *
 * 只读、无状态、不抛异常：任何畸形输入都返回 {@link #NO_EXIF}，绝不猜一个角度去旋转位图。
 */
public final class ExifOrientation {

    /** 没找到 / 读不全 EXIF 段。**不是**「朝向正常」。 */
    public static final int NO_EXIF = 0;

    /** EXIF 规范里的正常朝向。 */
    public static final int NORMAL = 1;

    /**
     * 取姿态只需要文件头。索尼 a7C 的真机素材实测：`APP1` 段长 **45514 字节**（远超 8 KiB），
     * 但 `IFD0` 只有 13 条、`0x0112` 落在绝对偏移 **46**——要的是 `IFD0`，不是整段。
     * 8 KiB 因此足够，且相对整张约 0.8 MB 不到 1%。
     */
    public static final int HEAD_BYTES = 8 * 1024;

    /** JPEG APP1 里的 EXIF 标识（`Exif\0\0`，共 6 字节）。 */
    private static final int EXIF_TAG_BYTES = 6;

    /** TIFF IFD 里 Orientation 的 tag 号。 */
    private static final int TAG_ORIENTATION = 0x0112;

    /** TIFF 类型 SHORT（Orientation 的类型）。 */
    private static final int TYPE_SHORT = 3;

    private ExifOrientation() {
    }

    /**
     * 从一段 JPEG 字节（可以是完整文件，也可以只是文件头）里取 Orientation。
     *
     * @param head JPEG 字节；null / 太短 / 不是 JPEG 都返回 {@link #NO_EXIF}
     * @return {@link #NO_EXIF} / {@code 1..8}
     */
    public static int parse(byte[] head) {
        if (head == null || head.length < 4) return NO_EXIF;
        if ((head[0] & 0xFF) != 0xFF || (head[1] & 0xFF) != 0xD8) return NO_EXIF;   // SOI

        int i = 2;
        while (i + 3 < head.length) {
            if ((head[i] & 0xFF) != 0xFF) return NO_EXIF;      // 标记链断了，说明不是我们认识的 JPEG 头
            int marker = head[i + 1] & 0xFF;
            if (marker == 0xFF) {                              // 标记前的填充字节
                i++;
                continue;
            }
            // 无长度的独立标记：TEM / RSTn / SOI，直接跳过。
            if (marker == 0x01 || marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7)) {
                i += 2;
                continue;
            }
            if (marker == 0xDA) return NO_EXIF;                // 进图像数据了，EXIF 只会在它之前
            int len = u16(head, i + 2, false);                 // JPEG 段长恒为大端，与 TIFF 字节序无关
            if (len < 2) return NO_EXIF;
            int payload = i + 4;
            if (payload > head.length) return NO_EXIF;
            if (marker == 0xE1 && isExif(head, payload)) {
                // 段的声明长度可能超过我们手里的字节数（只读了文件头）：按实际可用字节解析，
                // IFD0 在 APP1 最前面，够用就够用；不够则由 parseTiff 返回 NO_EXIF。
                int declaredEnd = i + 2 + len;
                int limit = Math.min(declaredEnd, head.length);
                return parseTiff(head, payload + EXIF_TAG_BYTES, limit);
            }
            i += 2 + len;
        }
        return NO_EXIF;
    }

    /** APP1 载荷前 6 字节是否为 `Exif\0\0`。 */
    private static boolean isExif(byte[] b, int payload) {
        if (payload + EXIF_TAG_BYTES > b.length) return false;
        return b[payload] == 'E' && b[payload + 1] == 'x' && b[payload + 2] == 'i'
                && b[payload + 3] == 'f' && b[payload + 4] == 0 && b[payload + 5] == 0;
    }

    /**
     * 解析 TIFF 头 + IFD0，找 {@link #TAG_ORIENTATION}。
     *
     * @param base TIFF 头起点（`Exif\0\0` 之后）
     * @param end  可用字节上界（文件头可能截断在 APP1 中间）
     */
    private static int parseTiff(byte[] b, int base, int end) {
        if (end - base < 8) return NO_EXIF;
        boolean little;
        if (b[base] == 'I' && b[base + 1] == 'I') {
            little = true;
        } else if (b[base] == 'M' && b[base + 1] == 'M') {
            little = false;
        } else {
            return NO_EXIF;                                    // 字节序标记不对，不是 TIFF
        }
        if (u16(b, base + 2, little) != 42) return NO_EXIF;     // TIFF magic
        long ifdOffset = u32(b, base + 4, little);
        if (ifdOffset < 8) return NO_EXIF;                      // IFD0 不该落在 TIFF 头里
        long ifd = base + ifdOffset;
        if (ifd + 2 > end) return NO_EXIF;

        int count = u16(b, (int) ifd, little);
        long p = ifd + 2;
        for (int k = 0; k < count; k++) {
            if (p + 12 > end) return NO_EXIF;                   // entry 表被截断，无法判断
            int tag = u16(b, (int) p, little);
            if (tag == TAG_ORIENTATION) {
                int type = u16(b, (int) p + 2, little);
                long n = u32(b, (int) p + 4, little);
                if (type != TYPE_SHORT || n < 1) return NORMAL; // 类型不符按规范当正常
                int value = u16(b, (int) p + 8, little);
                return (value >= NORMAL && value <= 8) ? value : NORMAL;
            }
            p += 12;
        }
        return NORMAL;                                          // 段在、tag 不在 → 正常朝向
    }

    private static int u16(byte[] b, int at, boolean little) {
        int a = b[at] & 0xFF;
        int c = b[at + 1] & 0xFF;
        return little ? (a | (c << 8)) : ((a << 8) | c);
    }

    private static long u32(byte[] b, int at, boolean little) {
        long a = b[at] & 0xFF;
        long c = b[at + 1] & 0xFF;
        long d = b[at + 2] & 0xFF;
        long e = b[at + 3] & 0xFF;
        return little ? (a | (c << 8) | (d << 16) | (e << 24))
                : ((a << 24) | (c << 16) | (d << 8) | e);
    }
}
