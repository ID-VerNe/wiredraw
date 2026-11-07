package com.rea.mtpimport;

import android.mtp.MtpDevice;
import android.mtp.MtpObjectInfo;

import java.util.Locale;

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

    public interface ScanProgress {
        void onScan(int scanned, int total);

        /**
         * 渐进发布（issue #60）：已发现至少一张照片（ARW 或 JPG，门槛见
         * {@link Catalog#hasPhotoFile()}）后，首张立即发布，之后每 {@link #PARTIAL_EVERY}
         * 个对象发布一次。快照是深拷贝，调用点可以安全地投递给 UI 线程继续扫描。
         */
        void onPartial(Catalog snapshot, int scanned, int total);
    }

    private CatalogScanner() {
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
        for (int i = 0; i < storageIds.length; i++) {
            for (int handle : perStorage[i]) {
                if (run != null && run.isCancelled()) {
                    log.line("扫描已取消：已枚举 " + scanned + "/" + total + " 个对象，提前结束");
                    return ScanResult.of(catalog.build(), scanned, dropped, missingHandles, true);
                }
                MtpObjectInfo oi = mtp.getObjectInfo(handle);
                scanned++;
                if (progress != null && (scanned % 50 == 0 || scanned == total)) {
                    progress.onScan(scanned, total);
                }
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
