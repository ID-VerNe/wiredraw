package com.rea.mtpimport;

import java.util.Locale;

/**
 * 进度数学，纯 JDK 类型，时钟由外部注入（测试可控，不依赖 System.nanoTime）。
 *
 * 单调性约定：已完字节只增不减、且被钳在 [0, total]。MTP 侧回报的字节数与
 * expectedSize 不一定完全一致（探针实测有「大小一致 / 不一致」两种），所以不能直接相除。
 */
public final class TransferProgress {

    private final long totalBytes;
    private final int totalFiles;
    private long doneBytes;
    private int filesDone;
    private long startNanos = -1;

    public TransferProgress(long totalBytes, int totalFiles) {
        this.totalBytes = Math.max(0, totalBytes);
        this.totalFiles = Math.max(0, totalFiles);
    }

    public void start(long nowNanos) {
        this.startNanos = nowNanos;
        this.doneBytes = 0;
        this.filesDone = 0;
    }

    /** 累加已传输字节；超出 total 的部分被钳掉，回退的负数被忽略。 */
    public void onBytes(long delta) {
        if (delta <= 0) return;
        doneBytes = Math.min(totalBytes, doneBytes + delta);
    }

    public void onFileDone() {
        if (filesDone < totalFiles) filesDone++;
    }

    /** 0.0–1.0；total 为 0 时返回 0.0（不除零）。 */
    public double fraction() {
        if (totalBytes <= 0) return 0.0;
        return Math.min(1.0, Math.max(0.0, (double) doneBytes / (double) totalBytes));
    }

    public long bytesDone() {
        return doneBytes;
    }

    public long totalBytes() {
        return totalBytes;
    }

    public int filesDone() {
        return filesDone;
    }

    public int totalFiles() {
        return totalFiles;
    }

    /** 平均速度（字节/秒）；未开始或耗时不足 1 ms 时返回 0，避免除零炸出 Infinity。 */
    public double bytesPerSec(long nowNanos) {
        if (startNanos < 0) return 0;
        long elapsed = nowNanos - startNanos;
        if (elapsed < 1_000_000L) return 0;
        return doneBytes / (elapsed / 1e9);
    }

    /** 剩余秒数（向上取整）；速度未知时返回 -1 表示「无法估计」。 */
    public long etaSeconds(long nowNanos) {
        if (totalBytes <= 0) return -1;
        long remain = totalBytes - doneBytes;
        if (remain <= 0) return 0;
        double bps = bytesPerSec(nowNanos);
        if (bps <= 0) return -1;
        return (long) Math.ceil(remain / bps);
    }

    public String humanBytes(long n) {
        if (n >= 1L << 30) return String.format(Locale.US, "%.1f GB", n / (double) (1L << 30));
        if (n >= 1L << 20) return String.format(Locale.US, "%.1f MB", n / (double) (1L << 20));
        if (n >= 1L << 10) return String.format(Locale.US, "%.1f KB", n / (double) (1L << 10));
        return n + " B";
    }

    public String humanRate(long nowNanos) {
        return humanBytes((long) bytesPerSec(nowNanos)) + "/s";
    }

    /** 界面/日志用的一行摘要，避免每个调用点各写一套格式。 */
    public String summary(long nowNanos) {
        long eta = etaSeconds(nowNanos);
        return String.format(Locale.US, "%d/%d 个 · %s / %s (%.1f%%) · %s · 剩余 %s",
                filesDone, totalFiles, humanBytes(doneBytes), humanBytes(totalBytes),
                fraction() * 100.0, humanRate(nowNanos), eta < 0 ? "未知" : eta + "s");
    }
}
