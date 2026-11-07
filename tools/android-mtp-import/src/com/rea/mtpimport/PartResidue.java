package com.rea.mtpimport;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 无主 `.part` 清理（issue #52；#177 续传停用后改口径）。
 *
 * **保留集恒空**：续传已停用（#177），`.part` 不再承载「已传前缀」，磁盘上任何残留都不可信。
 * 因此 `relPath + size` 对得上也不再保留——它们只可能是上一轮的残渣，留着不会被读取，
 * 只会白占空间。清理的前提仍是「枚举完整成功 + 没有活动写者」。
 *
 * 只扫 `outDir` 顶层：{@link Journal#partPath} 把半成品平铺在这里，成品在子目录。
 *
 * **调用前提（由调用方保证）**：本类不做并发保护。调用方必须在「没有活动写者」时调用，
 * 且只在该相机枚举**完整成功**时调用——用不完整的目录当依据，可能把本轮仍在用的 `.part` 误删。
 * 完整性判定不要由调用方自己拼：{@link #cleanIfComplete} 直接吃 {@link ScanResult}，
 * `complete == false` 或结果缺失时一个都不删，并留一行给用户看的原因。
 * 当前实现由 `Main.cleanupPartResidue()` 负责「没有活动写者」这个前提。
 */
public final class PartResidue {

    private PartResidue() {
    }

    /**
     * 合法保留集：恒空（#177 续传停用后没有可续传的半成品）。
     *
     * `entries` 参数保留是为了不改调用方签名；续传停用后它不参与判定。
     */
    public static Set<String> keepNames(Iterable<Catalog.Entry> entries) {
        return new HashSet<>();
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
     * 这是安全默认值——宁可留下残留，也不能因为 API 误用把正在写的半成品全删掉。
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
     * 只有「本次枚举完整成功」才清理；否则一个都不删，并记一行原因。
     *
     * `scan == null` 表示还没有完成过一次扫描，同样不清。开关只看 {@link ScanResult#complete}，
     * 不看目录是否非空：相机上没有可导入文件时（完整枚举、目录为空）照清。
     *
     * @return 删除成功的个数；跳过时为 0
     */
    public static int cleanIfComplete(File outDir, Set<String> keepNames, ScanResult scan, Log log) {
        if (scan == null) {
            if (log != null) log.line("尚未完成一次完整枚举，跳过无主半成品清理");
            return 0;
        }
        if (!scan.complete) {
            if (log != null) log.line(scan.skipReason());
            return 0;
        }
        return clean(outDir, keepNames, log);
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
