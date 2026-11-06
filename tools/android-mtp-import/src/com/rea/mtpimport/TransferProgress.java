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
    /**
     * #70：当前文件的进度（绝对偏移，范围 [0, currentFileSize]）。
     * 与 {@link #doneBytes}（已完成文件的字节）分开记：重试同一文件时只要重设它，
     * 不会把已落盘的同一段字节记两遍。
     */
    private long currentFileBytes;
    private long currentFileSize;
    private int filesDone;
    private long startNanos = -1;
    private long pausedNanos;
    private long pauseStartedNanos = -1;

    public TransferProgress(long totalBytes, int totalFiles) {
        this.totalBytes = Math.max(0, totalBytes);
        this.totalFiles = Math.max(0, totalFiles);
    }

    public void start(long nowNanos) {
        this.startNanos = nowNanos;
        this.doneBytes = 0;
        this.currentFileBytes = 0;
        this.currentFileSize = 0;
        this.filesDone = 0;
        this.pausedNanos = 0;
        this.pauseStartedNanos = -1;
    }

    /** 累加已传输字节；超出 total 的部分被钳掉，回退的负数被忽略。 */
    public void onBytes(long delta) {
        if (delta <= 0) return;
        doneBytes = Math.min(totalBytes, doneBytes + delta);
    }

    /**
     * #70：进入一个文件。`alreadyOnDisk` 是磁盘上已有的字节（续传起点），这里**重设**
     * 当前文件进度而不是往已完成字节里累加——同一文件失败后重试会再调一次，
     * 累加会把同一段字节记两遍（进度虚涨，重试后数字跳到 80 而不是 40）。
     */
    public void beginFile(long alreadyOnDisk, long fileSize) {
        currentFileSize = Math.max(0, fileSize);
        currentFileBytes = clampToFile(alreadyOnDisk);
    }

    /**
     * #70：按当前文件的**绝对偏移**上报进度（与 `getPartialObject` 的 offset 同口径）。
     * 回退被忽略、超出文件大小被钳住：MTP 侧分块可能重复上报或超出对账大小。
     */
    public void onFileProgress(long absoluteBytes) {
        if (absoluteBytes <= 0) return;
        long clamped = clampToFile(absoluteBytes);
        if (clamped > currentFileBytes) currentFileBytes = clamped;
    }

    /**
     * #70：当前文件传完。只按 `fileSize` 记一次已完成字节，然后清空当前文件进度——
     * 块级上报的字节不再叠加，避免「已上报到 60 + 再记 100」的重复。
     */
    public void commitFile(long fileSize) {
        doneBytes = Math.min(totalBytes, doneBytes + Math.max(0, fileSize));
        currentFileBytes = 0;
        currentFileSize = 0;
        onFileDone();
    }

    /**
     * #70：当前文件失败/取消。只丢掉文件内进度，已完成文件的字节不动。
     * 界面数字因此会往回走——这是预期行为（失败的文件没有被传完），不是进度抖动。
     */
    public void rollbackFile() {
        currentFileBytes = 0;
        currentFileSize = 0;
    }

    private long clampToFile(long bytes) {
        if (bytes <= 0) return 0;
        return Math.min(currentFileSize, bytes);
    }

    /** 标记「此刻起不再传输字节」；重复调用只认第一次（避免重复累加）。 */
    public void pause(long nowNanos) {
        if (startNanos < 0 || pauseStartedNanos >= 0) return;
        pauseStartedNanos = nowNanos;
    }

    /** 结束一段暂停；没有配对的 pause 时为 no-op。 */
    public void resume(long nowNanos) {
        if (pauseStartedNanos < 0) return;
        if (nowNanos > pauseStartedNanos) pausedNanos += nowNanos - pauseStartedNanos;
        pauseStartedNanos = -1;
    }

    public void onFileDone() {
        if (filesDone < totalFiles) filesDone++;
    }

    /** 0.0–1.0；total 为 0 时返回 0.0（不除零）。 */
    public double fraction() {
        if (totalBytes <= 0) return 0.0;
        return Math.min(1.0, Math.max(0.0, (double) bytesDone() / (double) totalBytes));
    }

    /**
     * 已完成文件字节 + 当前文件已落盘字节（#70）。
     * 钳在 total：块级上报与对账大小可能不一致（探针实测两种都出现过）。
     */
    public long bytesDone() {
        return Math.min(totalBytes, doneBytes + currentFileBytes);
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

    /** 平均速度（字节/秒）；未开始或实际传输不足 1 ms 时返回 0，避免除零炸出 Infinity。 */
    public double bytesPerSec(long nowNanos) {
        if (startNanos < 0) return 0;
        long activeNanos = activeElapsedNanos(nowNanos);
        if (activeNanos < 1_000_000L) return 0;
        return bytesDone() / (activeNanos / 1e9);
    }

    /**
     * 实际传输时长：墙钟减去已结束的暂停；暂停中读取时把当前这段暂停也算进去。
     * 暂停期间没有字节增量，若计入分母会把速率与 ETA 越拖越偏。
     */
    private long activeElapsedNanos(long nowNanos) {
        long elapsed = nowNanos - startNanos;
        long paused = pausedNanos;
        if (pauseStartedNanos >= 0 && nowNanos > pauseStartedNanos) {
            paused += nowNanos - pauseStartedNanos;
        }
        long active = elapsed - paused;
        return active < 0 ? 0 : active;
    }

    /** 剩余秒数（向上取整）；速度未知时返回 -1 表示「无法估计」。 */
    public long etaSeconds(long nowNanos) {
        if (totalBytes <= 0) return -1;
        long remain = totalBytes - bytesDone();
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
                filesDone, totalFiles, humanBytes(bytesDone()), humanBytes(totalBytes),
                fraction() * 100.0, humanRate(nowNanos), eta < 0 ? "未知" : eta + "s");
    }
}
