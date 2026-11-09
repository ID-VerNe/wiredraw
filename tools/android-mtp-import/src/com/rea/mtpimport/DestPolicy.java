package com.rea.mtpimport;

import java.io.File;
import java.io.IOException;

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
