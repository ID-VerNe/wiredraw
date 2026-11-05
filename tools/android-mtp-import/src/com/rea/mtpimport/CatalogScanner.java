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

    public interface ScanProgress {
        void onScan(int scanned, int total);
    }

    private CatalogScanner() {
    }

    /**
     * 枚举全部对象。`run` 为取消令牌：每个 handle 前判一次，取消即提前结束
     * （结果会被调用点丢弃，这里只是不让 1526 个 handle 空转一遍——第五轮评审 B-1）。
     */
    public static Catalog scan(MtpDevice mtp, int[] storageIds, Log log, ScanRun run, ScanProgress progress) {
        Catalog catalog = new Catalog();
        int scanned = 0;
        int total = 0;

        // 先数一遍总量，进度条才有分母（句柄数组已在本地，不额外碰相机）
        int[][] perStorage = new int[storageIds.length][];
        for (int i = 0; i < storageIds.length; i++) {
            if (run != null && run.isCancelled()) {
                log.line("扫描已取消：停止枚举存储");
                return catalog.build();
            }
            int[] hs = mtp.getObjectHandles(storageIds[i], 0, 0);
            perStorage[i] = hs == null ? new int[0] : hs;
            total += perStorage[i].length;
        }
        log.line("待扫描对象共 " + total + " 个（" + storageIds.length + " 个存储）");

        for (int i = 0; i < storageIds.length; i++) {
            for (int handle : perStorage[i]) {
                if (run != null && run.isCancelled()) {
                    log.line("扫描已取消：已枚举 " + scanned + "/" + total + " 个对象，提前结束");
                    return catalog.build();
                }
                MtpObjectInfo oi = mtp.getObjectInfo(handle);
                scanned++;
                if (progress != null && (scanned % 50 == 0 || scanned == total)) {
                    progress.onScan(scanned, total);
                }
                if (oi == null) continue;
                catalog.add(new Catalog.Entry(
                        handle,
                        oi.getParent(),
                        oi.getFormat(),
                        oi.getCompressedSizeLong(),
                        oi.getName()));
            }
        }

        Catalog built = catalog.build();
        log.line(String.format(Locale.US,
                "扫描完成：文件夹 %d 个、文件 %d 个（合计 %.2f GB，其中 ARW %.2f GB）",
                built.folderCount(), built.fileCount(),
                built.fileBytes() / 1073741824.0, built.rawBytes() / 1073741824.0));
        return built;
    }
}
