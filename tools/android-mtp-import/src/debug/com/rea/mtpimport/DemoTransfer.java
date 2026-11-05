package com.rea.mtpimport;

/**
 * demo 传输的进度模拟（issue #46）。
 *
 * 没有相机时真实传输永远是 `UNAVAILABLE`，`IMPORTING` / `PAUSED` / `DONE` 只能靠
 * `--es demo_state` 硬切。这里给「点 Import all」提供一条会走的进度：每 tick 推进
 * 一个文件与等比例字节，到顶后由调用方落 `DONE`。
 *
 * 本类不 import android.*：与纯 JDK 类同一条保护线，并进 selfTest 的 classpath。
 */
public final class DemoTransfer implements DemoRun {

    private final int totalFiles;
    private final long totalBytes;
    private int doneFiles;
    private long doneBytes;

    public DemoTransfer(int totalFiles, long totalBytes) {
        // 0 张也当成 1 张，避免除零；真实调用方不会传 0（Import all 至少 36）
        this.totalFiles = Math.max(1, totalFiles);
        this.totalBytes = Math.max(0, totalBytes);
    }

    public int doneFiles() {
        return doneFiles;
    }

    public long doneBytes() {
        return doneBytes;
    }

    public int totalFiles() {
        return totalFiles;
    }

    public long totalBytes() {
        return totalBytes;
    }

    /**
     * 推进一个文件；最后一步把字节数补齐到 {@link #totalBytes()}，保证完成时数值自洽。
     * 到顶后继续调用返回 true 且不再变化（ticker 竞争时不会越界）。
     */
    public boolean advance() {
        if (doneFiles >= totalFiles) {
            doneBytes = totalBytes;
            return true;
        }
        doneFiles++;
        doneBytes = doneFiles >= totalFiles
                ? totalBytes
                : (long) ((double) totalBytes * doneFiles / totalFiles);
        return doneFiles >= totalFiles;
    }
}
