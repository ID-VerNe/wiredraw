package com.rea.mtpimport;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 无主 `.part` 清理（issue #52，S1）。
 *
 * 判据只看 `relPath`，**不看 size**：
 *
 * - 合法保留集 = 本次 {@link Catalog} 里的所有 `relPath` 压平名；
 * - 可删集 = 顶层里 `relPath` 已完全不在本次目录的 `.part`（对象在相机侧已不存在，接不上）；
 * - `relPath` 仍在目录、但 size 不同的 `.part` **保留**——异尺寸拒绝留下的那只仍属合法半成品，
 *   每项至多一个（{@link DestPolicy} 保证）。
 *
 * 为什么不用 Journal 判定：{@link Journal} 只在成功时写 `done = size`，失败轮次从不记部分进度，
 * 用它当依据会把所有可续传的 `.part` 判成无主。
 *
 * 只扫 `outDir` 顶层：{@link Journal#partPath} 把半成品平铺在这里，成品在子目录。
 *
 * **调用前提（由调用方保证）**：本类不做并发保护。调用方必须在「没有活动写者」时调用——
 * 当前实现放在 `Main.doStart()` 里 `new ImportService` 之前，那时 `service` 与 `orphanService`
 * 的 `isRunning()` 都已被双闸挡掉。
 */
public final class PartResidue {

    private PartResidue() {
    }

    /** 合法保留集：本次目录里所有对象的 `relPath` 压平名（不看 size）。 */
    public static Set<String> keepNames(Iterable<Catalog.Entry> entries) {
        Set<String> keep = new HashSet<>();
        if (entries == null) return keep;
        for (Catalog.Entry e : entries) {
            if (e == null || e.relPath == null || e.relPath.isEmpty()) continue;
            keep.add(Journal.partName(e.relPath));
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

    /** 从 `<base>.<size>.part` 取回压平后的 relPath 名；不是半成品返回 null。 */
    static String partBase(String name) {
        if (!isPartName(name)) return null;
        String stem = name.substring(0, name.length() - ".part".length());
        return stem.substring(0, stem.lastIndexOf('.'));
    }

    /** 顶层里无人认领的半成品（不递归）。 */
    public static List<File> orphans(File outDir, Set<String> keepNames) {
        List<File> dead = new ArrayList<>();
        if (outDir == null || !outDir.isDirectory()) return dead;
        File[] kids = outDir.listFiles();
        if (kids == null) return dead;
        for (File kid : kids) {
            if (!kid.isFile()) continue;
            String name = kid.getName();
            if (!isPartName(name)) continue;
            String base = partBase(name);
            if (base != null && keepNames != null && keepNames.contains(base)) continue;
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
