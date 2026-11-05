package com.rea.mtpimport;

/**
 * 假数据模式的一次性载荷：一个状态 + 该状态需要的假树、假计数、假进度。
 *
 * 这个类型放在 main 源集是因为 {@link DemoBridge} 要引用它；真正的构造逻辑
 * 只在 debug 源集里（release 编不进去，见 U-54）。
 */
public final class DemoPayload {

    public final UiState.State state;
    /** 照片墙用的假 catalog；非目录态可以为 null。 */
    public final Catalog catalog;
    public final int scanned;
    public final int scanTotal;
    public final int doneFiles;
    public final int totalFiles;
    public final long doneBytes;
    public final long totalBytes;
    public final long bytesPerSecond;
    /** `ERROR` 的一行中文原因（≤20 字）。 */
    public final String errorReason;
    /** `DONE` 的照片数。 */
    public final int donePhotos;
    /** `DONE` 的容量字节数。 */
    public final long resultBytes;

    public DemoPayload(UiState.State state,
                       Catalog catalog,
                       int scanned,
                       int scanTotal,
                       int doneFiles,
                       int totalFiles,
                       long doneBytes,
                       long totalBytes,
                       long bytesPerSecond,
                       String errorReason,
                       int donePhotos,
                       long resultBytes) {
        this.state = state;
        this.catalog = catalog;
        this.scanned = scanned;
        this.scanTotal = scanTotal;
        this.doneFiles = doneFiles;
        this.totalFiles = totalFiles;
        this.doneBytes = doneBytes;
        this.totalBytes = totalBytes;
        this.bytesPerSecond = bytesPerSecond;
        this.errorReason = errorReason == null ? "" : errorReason;
        this.donePhotos = donePhotos;
        this.resultBytes = resultBytes;
    }

    /** 环内百分比，与 a/b 同源（U-45）。 */
    public String percentText() {
        return UiText.percentText(doneFiles, totalFiles);
    }

    public String fractionText() {
        return UiText.fraction(doneFiles, totalFiles);
    }

    public long remainingBytes() {
        return Math.max(0, totalBytes - doneBytes);
    }
}
