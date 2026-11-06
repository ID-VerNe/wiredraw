package com.rea.mtpimport;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 无主 `.part` 清理（issue #52，S1 的 A 版）。
 *
 * 保留集与 {@link Journal#partPath} 同口径：`relPath + size`。
 *
 * - 合法保留 = `relPath` 仍在本次目录、且 size 能对上某个对象的 `.part`
 *   （含异尺寸拒绝留下的那只——它的 size 就是当前对象 size，每项至多一个，由 {@link DestPolicy} 保证）；
 * - 可删 = `relPath` 已完全不在目录，或同 `relPath` 但 size 已不被任何对象认领的 `.part`
 *   （换卡 / 编号复用 / 文件替换留下的旧尺寸残留，接不上）。
 *
 * 为什么不用 Journal 判定：{@link Journal} 只在成功时写 `done = size`，失败轮次从不记部分进度，
 * 用它当依据会把所有可续传的 `.part` 判成无主。
 *
 * 只扫 `outDir` 顶层：{@link Journal#partPath} 把半成品平铺在这里，成品在子目录。
 *
 * **调用前提（由调用方保证）**：本类不做并发保护。调用方必须在「没有活动写者」时调用，
 * 且只在该相机枚举**完整成功**时调用——用不完整的目录当保留集，会把仍可续传的 `.part` 误删。
 * 当前实现由 `Main.cleanupPartResidue()` 负责这两个前提。
 */
public final class PartResidue {

    private PartResidue() {
    }

    /** 合法保留集：本次目录里每个对象的 `relPath + size`（与 {@link Journal#partPath} 同口径）。 */
    public static Set<String> keepNames(Iterable<Catalog.Entry> entries) {
        Set<String> keep = new HashSet<>();
        if (entries == null) return keep;
        for (Catalog.Entry e : entries) {
            if (e == null || e.relPath == null || e.relPath.isEmpty()) continue;
            keep.add(Journal.partName(e.relPath) + "." + e.size + ".part");
        }
        return keep;
    }

    /** 名字是否是 `<任意>.<十进制 size>.part` 的半成品。 */
    static boolean isPartName(String name) {
        if (name == null || !name.endsWith(".part")) return false;
        String stem = name.substring(0, name.length() - ".part".length());
        int dot = stem.lastIndexOf('.');
        if (dot <= 0 || dot == stem.length() - 1) return false;
        for (int i = dot + 1; i < stem.length(); i++) {
            char c = stem.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /**
     * 顶层里无人认领的半成品（不递归）。
     *
     * `keepNames == null` 一律返回空列表：保留集缺失时**一个都不删**。
     * 这是安全默认值——宁可留下残留，也不能因为 API 误用把可续传的半成品全删掉。
     */
    public static List<File> orphans(File outDir, Set<String> keepNames) {
        List<File> dead = new ArrayList<>();
        if (outDir == null || !outDir.isDirectory() || keepNames == null) return dead;
        File[] kids = outDir.listFiles();
        if (kids == null) return dead;
        for (File kid : kids) {
            if (!kid.isFile()) continue;
            String name = kid.getName();
            if (!isPartName(name)) continue;
            if (keepNames.contains(name)) continue;
            dead.add(kid);
        }
        return dead;
    }

    /**
     * 删除无人认领的半成品；失败只记日志、不重试、不阻断本轮。
     *
     * 选择「记日志」而不是静默：删不掉意味着空间会继续被占（#52 的症状之一），
     * 静默会让用户和排查者都看不到；但它不该把整轮导入打成失败，所以不计结果。
     *
     * @return 删除成功的个数
     */
    public static int clean(File outDir, Set<String> keepNames, Log log) {
        List<File> dead = orphans(outDir, keepNames);
        int n = 0;
        for (File f : dead) {
            if (f.delete()) {
                n++;
            } else if (log != null) {
                log.line("无主半成品删除失败（不影响本轮）：" + f);
            }
        }
        if (n > 0 && log != null) {
            log.line("已清理无主半成品：" + n + " 个");
        }
        return n;
    }
}
