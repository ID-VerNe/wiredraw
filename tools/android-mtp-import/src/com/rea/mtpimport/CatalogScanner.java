package com.rea.mtpimport;

import android.mtp.MtpDevice;
import android.mtp.MtpObjectInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 扫描：MTP 对象 → {@link Catalog}。**只读**，不发任何写操作。
 *
 * 两条实测结论直接写进实现：
 * 1. `getObjectHandles(sid, 0xB101, 0)` 的 format 过滤**不可信**——
 *    探针实测返回 754 个「ARW」而全量只有 511 个（tools/android-mtp-probe/README.md 坑 2）。
 *    所以这里一律 `getObjectHandles(sid, 0, 0)` 全量枚举 + 逐个 `getObjectInfo()` 自己分类。
 * 2. 全量约 39 ms/个、1526 个约 59 秒，所以必须回调进度，否则用户以为卡死。
 */
public final class CatalogScanner {

    /** 每发现这么多对象发布一次快照（issue #60 的口径：约 200 个）。 */
    private static final int PARTIAL_EVERY = 200;

    /** 进度节流通知最小间隔（issue #371）：约 100ms 一次，保证 UI 连贯平滑且不洪泛。 */
    public static final long PROGRESS_INTERVAL_MS = 100L;

    public interface ScanProgress {
        void onScan(int scanned, int total);

        /**
         * 渐进发布（issue #60）：已发现至少一张照片（ARW 或 JPG，门槛见
         * {@link Catalog#hasPhotoFile()}）后，首张立即发布，之后每 {@link #PARTIAL_EVERY}
         * 个对象发布一次。快照是深拷贝，调用点可以安全地投递给 UI 线程继续扫描。
         */
        void onPartial(Catalog snapshot, int scanned, int total);

        /**
         * 优先发现文件夹/目录结构回调（issue #372）：在全量枚举具体照片前快速探查相机内文件夹，
         * 让上层或界面能立刻获得可用的日期文件夹/目录骨架。默认空实现以兼容旧调用。
         */
        default void onFoldersDiscovered(Catalog snapshot, List<Catalog.Entry> folders) {}
    }

    private CatalogScanner() {
    }

    /**
     * 优先快速探查文件夹与目录结构（issue #372）。
     * 尝试以 FMT_FOLDER (0x3001) 及 DCIM 目录结构枚举目录节点，免除深层扁平遍历的漫长等待。
     */
    private static List<Catalog.Entry> discoverFolders(MtpDevice mtp, int[] storageIds, Log log, ScanRun run) {
        List<Catalog.Entry> folders = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int sid : storageIds) {
            if (run != null && run.isCancelled()) break;
            // 1. 优先以 FMT_FOLDER 快速查询目录句柄
            int[] folderHandles = mtp.getObjectHandles(sid, Catalog.FMT_FOLDER, 0);
            if (folderHandles != null && folderHandles.length > 0) {
                for (int fh : folderHandles) {
                    if (run != null && run.isCancelled()) break;
                    if (!seen.add(fh)) continue;
                    MtpObjectInfo oi = mtp.getObjectInfo(fh);
                    if (oi != null && oi.getFormat() == Catalog.FMT_FOLDER) {
                        folders.add(new Catalog.Entry(
                                fh,
                                oi.getParent(),
                                oi.getFormat(),
                                oi.getCompressedSizeLong(),
                                oi.getName(),
                                sid));
                    }
                }
            }
            // 2. 若格式过滤未返回文件夹，尝试探查顶层 DCIM 目录结构（通用 MTP 兼容）
            if (folders.isEmpty()) {
                int[] rootHs = mtp.getObjectHandles(sid, 0, 0xFFFFFFFF);
                if (rootHs != null) {
                    for (int rh : rootHs) {
                        if (run != null && run.isCancelled()) break;
                        if (!seen.add(rh)) continue;
                        MtpObjectInfo oi = mtp.getObjectInfo(rh);
                        if (oi != null && oi.getFormat() == Catalog.FMT_FOLDER) {
                            folders.add(new Catalog.Entry(rh, oi.getParent(), oi.getFormat(), oi.getCompressedSizeLong(), oi.getName(), sid));
                            if ("DCIM".equalsIgnoreCase(oi.getName())) {
                                int[] subHs = mtp.getObjectHandles(sid, 0, rh);
                                if (subHs != null) {
                                    for (int sh : subHs) {
                                        if (run != null && run.isCancelled()) break;
                                        if (!seen.add(sh)) continue;
                                        MtpObjectInfo subOi = mtp.getObjectInfo(sh);
                                        if (subOi != null && subOi.getFormat() == Catalog.FMT_FOLDER) {
                                            folders.add(new Catalog.Entry(sh, subOi.getParent(), subOi.getFormat(), subOi.getCompressedSizeLong(), subOi.getName(), sid));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return folders;
    }

    /**
     * 枚举全部对象。`run` 为取消令牌：每个 handle 前判一次，取消即提前结束
     * （结果会被调用点丢弃，这里只是不让 1526 个 handle 空转一遍——第五轮评审 B-1）。
     */
    public static ScanResult scan(MtpDevice mtp, int[] storageIds, Log log, ScanRun run, ScanProgress progress) {
        Catalog catalog = new Catalog();
        int scanned = 0;
        int total = 0;
        // issue #52：这两个计数决定目录能否被当作 `.part` 保留集。
        // `getObjectHandles` / `getObjectInfo` 返回 null 时扫描不会抛异常，必须在这里显式记账。
        int dropped = 0;
        int missingHandles = 0;

        // 优先快速探查文件夹（issue #372）：在耗时的全量照片枚举前建立目录骨架
        List<Catalog.Entry> discoveredFolders = discoverFolders(mtp, storageIds, log, run);
        for (Catalog.Entry fe : discoveredFolders) {
            catalog.add(fe);
        }
        if (!discoveredFolders.isEmpty()) {
            log.line("优先发现拍摄文件夹 " + discoveredFolders.size() + " 个");
            if (progress != null) {
                progress.onFoldersDiscovered(catalog.snapshot(), Collections.unmodifiableList(discoveredFolders));
            }
        }

        // 先数一遍总量，进度条才有分母（句柄数组已在本地，不额外碰相机）
        int[][] perStorage = new int[storageIds.length][];
        for (int i = 0; i < storageIds.length; i++) {
            if (run != null && run.isCancelled()) {
                log.line("扫描已取消：停止枚举存储");
                return ScanResult.of(catalog.build(), scanned, dropped, missingHandles, true);
            }
            int[] hs = mtp.getObjectHandles(storageIds[i], 0, 0);
            if (hs == null) {
                // 该存储的对象列表整个拿不到：目录缺一整块，必须标不完整。
                missingHandles++;
                hs = new int[0];
            }
            perStorage[i] = hs;
            total += perStorage[i].length;
        }
        log.line("待扫描对象共 " + total + " 个（" + storageIds.length + " 个存储）");

        boolean published = false;
        long lastProgressMs = 0L;
        for (int i = 0; i < storageIds.length; i++) {
            for (int handle : perStorage[i]) {
                if (run != null && run.isCancelled()) {
                    log.line("扫描已取消：已枚举 " + scanned + "/" + total + " 个对象，提前结束");
                    return ScanResult.of(catalog.build(), scanned, dropped, missingHandles, true);
                }
                Catalog.Entry existing = catalog.get(handle);
                scanned++;
                long now = System.currentTimeMillis();
                if (progress != null && (scanned == 1 || scanned == total || now - lastProgressMs >= PROGRESS_INTERVAL_MS)) {
                    lastProgressMs = now;
                    progress.onScan(scanned, total);
                }
                if (existing != null) {
                    // 已在优先文件夹探查阶段解析过元数据，直接复用、免除重复的 MTP 调用
                    continue;
                }
                MtpObjectInfo oi = mtp.getObjectInfo(handle);
                if (oi == null) {
                    // 对象信息拿不到时目录里就没有它；保留集会缺这一项，按不完整处理。
                    dropped++;
                    continue;
                }
                Catalog.Entry entry = new Catalog.Entry(
                        handle,
                        oi.getParent(),
                        oi.getFormat(),
                        oi.getCompressedSizeLong(),
                        oi.getName(),
                        storageIds[i]);   // #54：只用于判别同名冲突，不进 relPath
                catalog.add(entry);
                // 门槛是「已发现至少一张照片」（ARW 或 JPG），不是「发现 ARW」：纯 JPG 拍摄时
                // 旧口径 rawSeen 恒为 0，首屏要等整轮扫描结束（约 59 秒，issue #96）。
                // 门槛口径与 PhotoWall 的入选集合一致，发布出的快照至少有一个照片单元。
                if (progress != null && catalog.hasPhotoFile()
                        && (!published || scanned % PARTIAL_EVERY == 0)) {
                    published = true;
                    progress.onPartial(catalog.snapshot(), scanned, total);
                }
            }
        }

        Catalog built = catalog.build();
        log.line(String.format(Locale.US,
                "扫描完成：文件夹 %d 个、文件 %d 个（合计 %.2f GB，其中 ARW %.2f GB）",
                built.folderCount(), built.fileCount(),
                built.fileBytes() / 1073741824.0, built.rawBytes() / 1073741824.0));
        return ScanResult.of(built, scanned, dropped, missingHandles, false);
    }
}
