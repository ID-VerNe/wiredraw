package com.rea.mtpimport;

import java.io.File;
import java.io.IOException;

/**
 * 落盘冲突策略（issue #54）：纯 JDK，便于 JVM 自检覆盖。
 *
 * <p>**边界**：本方案不修「两个同名文件仍争同一落盘路径」这件事——relPath 里没有 storage 维度，
 * 两个存储的同名文件本来就只能对应一个目标路径。它保证的是**不覆盖已存在的文件、不把冲突项记成完成**：
 * 目标已存在且尺寸不同就显式拒绝覆盖；尺寸相同按 `relPath + size` 口径视为同一文件，
 * 但仍只算 `SKIPPED`（仅尺寸核对，内容未校验 #177；与 Journal / 已导入索引一致，
 * 见 #20 的已知边界——同路径同尺寸但内容不同分辨不出来）。</p>
 *
 * <p>落点策略（issue #28）：默认应用私有目录 `getFilesDir()/imported`；请求相册时本期一律回退，
 * 且回退原因必须非空（相册权限 / MIME / RELATIVE_PATH 均未真机验证，不在未验时假装可用）。
 * 目录布局镜像 `relPath`，不重排日期、不加机型层——`relPath` 同时是 Journal / 索引主键，
 * 落点路径与识别键保持一致。</p>
 */
public final class DestPolicy {

    /** 应用私有落点目录名（`getFilesDir()/imported`，系统清理缓存不会带走）。 */
    public static final String PRIVATE_DIR = "imported";

    /** 用户请求的落点（issue #28）：默认私有；相册本期未接入。 */
    public enum Kind {
        /** 应用私有目录。 */
        PRIVATE,
        /** 公开相册（MediaStore）；本期请求一律回退，见 {@link #choose}。 */
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
     * 落点决策（issue #28）。默认私有；相册本期未接入，请求相册必须回退且带原因。
     *
     * <p>**边界**：本策略只决定「根」，不判断同名冲突——冲突仍由 {@link #resolve} /
     * {@link #existing} 判定。相册写入依赖尚未真机验证的 MediaStore 行为，本单不实现。</p>
     */
    public static Destination choose(File filesDir, Kind requested) {
        File root = privateRoot(filesDir);
        if (requested == Kind.GALLERY) {
            // 相册写入依赖 MediaStore 权限 / MIME / RELATIVE_PATH，均未真机验证（#28）；
            // 本期不做「看似可用」的落点，直接回退并把原因交给调用方展示。
            return new Destination(Kind.PRIVATE, root,
                    "相册位置尚未接入（真机未验证），已回退到应用私有目录");
        }
        return new Destination(Kind.PRIVATE, root, "");
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
