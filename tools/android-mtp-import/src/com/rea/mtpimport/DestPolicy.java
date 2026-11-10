package com.rea.mtpimport;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * 落盘冲突策略（issue #54）：纯 JDK，便于 JVM 自检覆盖。
 *
 * <p>**边界**：本方案不修「两个同名文件仍争同一落盘路径」这件事——relPath 里没有 storage 维度，
 * 两个存储的同名文件本来就只能对应一个目标路径。它保证的是**不覆盖已存在的文件、不把冲突项记成完成**：
 * 目标已存在且尺寸不同就显式拒绝覆盖；尺寸相同按 `relPath + size` 口径视为同一文件，
 * 但仍只算 `SKIPPED`（仅尺寸核对，内容未校验 #177；与 Journal 一致，
 * 见 #20 的已知边界——同路径同尺寸但内容不同分辨不出来）。</p>
 *
 * <p>落点策略（issue #28）：用户可选，默认应用私有目录 `getFilesDir()/imported`；
 * 选相册时由 Android 侧注入「相册可写」判定，不可写一律回退私有且回退原因必须非空
 * （不得静默失败）。`Destination.root` 恒为**私有状态目录**（Journal / `.part`），
 * `kind` 只表达成品去向；成品进 MediaStore 的写入由 PB 分节承接。
 * 目录布局镜像 `relPath`，不重排日期、不加机型层——`relPath` 同时是 Journal 主键，
 * 落点路径与识别键保持一致。</p>
 */
public final class DestPolicy {

    /** 应用私有落点目录名（`getFilesDir()/imported`，系统清理缓存不会带走）。 */
    public static final String PRIVATE_DIR = "imported";

    /** 用户请求的落点（issue #28）：默认私有；相册需可写才生效。 */
    public enum Kind {
        /** 应用私有目录。 */
        PRIVATE,
        /** 公开相册（MediaStore）；不可写时回退，见 {@link #choose}。 */
        GALLERY
    }

    /** 落点决策结果；`fallbackReason` 非空表示发生了回退，调用方必须让用户看见。 */
    public static final class Destination {
        public final Kind kind;
        public final File root;
        public final String fallbackReason;

        Destination(Kind kind, File root, String fallbackReason) {
            this.kind = kind;
            this.root = root;
            this.fallbackReason = fallbackReason;
        }

        public boolean fellBack() {
            return !fallbackReason.isEmpty();
        }
    }

    /** 目标路径上已有文件时怎么处理。 */
    public enum Existing {
        /** 没有同名文件，正常落盘。 */
        NONE,
        /**
         * 同名且尺寸相同：不覆盖，按 `SKIPPED` 记（仅尺寸核对，内容未校验 #177）。
         * 尺寸相同不等于内容正确，不能把它当成校验通过。
         */
        SAME_SIZE,
        /** 同名但尺寸不同：拒绝覆盖，显式失败并保留原文件。 */
        DIFFERENT_SIZE
    }

    private DestPolicy() { }

    /** 应用私有落点根。 */
    public static File privateRoot(File filesDir) {
        return new File(filesDir, PRIVATE_DIR);
    }

    /**
     * 落点决策的兼容入口：调用方未声明相册可用性时，按「不可用」安全回退。
     * 需要真正支持用户选相册的调用方，改用带 `galleryAvailable` 的三参重载。
     *
     * <p>**边界**：本策略只决定「根」与「成品去向」，不判断同名冲突——冲突仍由
     * {@link #resolve} / {@link #existing} 判定。</p>
     */
    public static Destination choose(File filesDir, Kind requested) {
        // 两参入口 = 未声明相册可用性，按「不可用」安全回退（与接入前行为一致）。
        return choose(filesDir, requested, false);
    }

    /**
     * 落点决策（issue #28 · 用户可选）。`galleryAvailable` 由 Android 侧按权限 / 可写性注入，
     * 策略层保持纯 JDK 可测。`root` 恒为**私有状态目录**（Journal / `.part`），
     * `kind` 只表达成品去向；两棵根不得混用，否则换根会丢导入记录。
     */
    public static Destination choose(File filesDir, Kind requested, boolean galleryAvailable) {
        File root = privateRoot(filesDir);
        if (requested == Kind.GALLERY) {
            if (galleryAvailable) {
                // 成品走 MediaStore；Journal / .part 仍留在私有状态根。
                return new Destination(Kind.GALLERY, root, "");
            }
            return new Destination(Kind.PRIVATE, root,
                    "相册不可写（未获授权或写入失败），已回退到应用私有目录");
        }
        return new Destination(Kind.PRIVATE, root, "");
    }

    /** 偏好串 → 落点；未知 / 空 / null 一律按默认私有，不让坏数据把用户送进相册路径。 */
    public static Kind parseKind(String stored) {
        if (stored == null) return Kind.PRIVATE;
        try {
            return Kind.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return Kind.PRIVATE;
        }
    }

    /** 落点 → 偏好串；null 按默认私有。 */
    public static String storeKind(Kind kind) {
        return (kind == null ? Kind.PRIVATE : kind).name();
    }

    /** 相册成品根前缀（MediaStore `RELATIVE_PATH` 的顶层目录）。 */
    public static final String GALLERY_DIR = "Pictures";

    /** ARW 入 `MediaStore.Images` 表的 MIME（手机侧已证免运行时权限）。 */
    public static final String ARW_MIME = "image/x-sony-arw";

    /** JPG 入 `MediaStore.Images` 表的 MIME。 */
    public static final String JPEG_MIME = "image/jpeg";

    /**
     * 成品去向（issue #28 分节 PB）：把「成品根」从状态目录里分出去。
     *
     * `Destination.root` 恒为私有状态目录（Journal / `.part`），这里只算**成品**的相对去向：
     * `PRIVATE` 镜像 `relPath`（落在私有状态根下，与旧行为一致），`GALLERY` 加 `Pictures/`
     * 前缀交给 MediaStore 流式复制。两棵根不混用，换成品去向不会丢导入记录。
     * 越界（绝对路径 / `..` 路径段 / 空串）返回 null，避免把成品写到相册根之外。
     */
    public static String productRelativePath(Kind kind, String relPath) {
        String safe = safeRelative(relPath);
        if (safe == null) return null;
        return kind == Kind.GALLERY ? GALLERY_DIR + "/" + safe : safe;
    }

    /** MediaStore 的 `RELATIVE_PATH`：带 `Pictures/` 前缀与尾斜杠，越界返回 null。 */
    public static String galleryRelativeDir(String relPath) {
        String safe = safeRelative(relPath);
        if (safe == null) return null;
        int slash = safe.lastIndexOf('/');
        String dir = slash < 0 ? "" : safe.substring(0, slash);
        return GALLERY_DIR + (dir.isEmpty() ? "" : "/" + dir) + "/";
    }

    /** MediaStore 的 `DISPLAY_NAME`：只取文件名，越界返回 null。 */
    public static String galleryDisplayName(String relPath) {
        String safe = safeRelative(relPath);
        if (safe == null) return null;
        return safe.substring(safe.lastIndexOf('/') + 1);
    }

    /** 入 `MediaStore.Images` 表的 MIME：ARW 用专用值、JPG 用标准值，其余返回 null（不支持入表）。 */
    public static String galleryMime(String relPath) {
        if (relPath == null) return null;
        String lower = relPath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".arw")) return ARW_MIME;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return JPEG_MIME;
        return null;
    }

    /** 按查询到的相册条目尺寸判定同名冲突；`existingSize` &lt; 0 表示不存在。 */
    public static Existing existingBySize(long existingSize, long expectedSize) {
        if (existingSize < 0) return Existing.NONE;
        if (existingSize == expectedSize) return Existing.SAME_SIZE;
        return Existing.DIFFERENT_SIZE;
    }

    /** 写入失败回退私有：原因必须非空、含「回退」与原始失败原因，供日志与界面告知用户。 */
    public static String writeFallbackReason(String cause) {
        String detail = cause == null || cause.isEmpty() ? "相册不可用" : cause;
        return "已回退到应用私有目录（" + detail + "）";
    }

    /** 统一校验并归一化 relPath：绝对路径 / `..` 段 / `.` 段 / 空段 / 尾斜杠一律拒绝。 */
    private static String safeRelative(String relPath) {
        if (relPath == null || relPath.isEmpty()) return null;
        String p = relPath.replace('\\', '/');
        if (p.startsWith("/") || p.endsWith("/")) return null;
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals("..") || seg.equals(".")) return null;
        }
        return p;
    }

    /** 把 relPath 解析到 outDir 下的目标位置；越界（绝对路径/`..`）返回 null。 */
    public static File resolve(File outDir, String relPath) throws IOException {
        File dest = new File(outDir, relPath);
        String root = outDir.getCanonicalPath();
        String target = dest.getCanonicalPath();
        if (target.equals(root)) return null;
        if (!target.startsWith(root + File.separator)) return null;
        return dest;
    }

    /** 目标已存在时按尺寸判定。 */
    public static Existing existing(File dest, long expectedSize) {
        if (!dest.exists()) return Existing.NONE;
        if (dest.isFile() && dest.length() == expectedSize) return Existing.SAME_SIZE;
        return Existing.DIFFERENT_SIZE;
    }
}
