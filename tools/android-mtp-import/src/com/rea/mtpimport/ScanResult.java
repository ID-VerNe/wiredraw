package com.rea.mtpimport;

/**
 * 一次扫描的结果：目录 + 枚举完整性（issue #52）。
 *
 * 为什么单独一层：清理 `.part` 的开关必须是「本次枚举是否**完整**」，而不是「扫完了」
 * 或「目录非空」。{@link CatalogScanner} 在 `getObjectHandles` / `getObjectInfo` 返回
 * `null` 时会静默跳过对象，扫描本身不抛异常；只看「扫完了」会把残缺目录当成完整目录，
 * 把仍可续传的半成品误删。完整性标志与丢弃计数只由扫描侧算一次，UI 侧不许再拼。
 *
 * 纯 JDK，可在 JVM 上直接测。
 */
public final class ScanResult {

    /** 本次枚举得到的目录（可能不完整；是否可用看 {@link #complete}）。 */
    public final Catalog catalog;

    /** 是否完整枚举：未取消，且没有丢弃对象、没有存储取句柄失败。 */
    public final boolean complete;

    /** 实际发起 `getObjectInfo` 的对象数（含被丢弃的）。 */
    public final int scanned;

    /** `getObjectInfo` 返回 `null`、被静默丢弃的对象数。 */
    public final int dropped;

    /** `getObjectHandles` 返回 `null` 的存储数（该存储的对象列表整个拿不到）。 */
    public final int missingHandles;

    private ScanResult(Catalog catalog, boolean complete, int scanned, int dropped, int missingHandles) {
        this.catalog = catalog;
        this.complete = complete;
        this.scanned = scanned;
        this.dropped = dropped;
        this.missingHandles = missingHandles;
    }

    /**
     * 由扫描侧填一次计数并判定完整性。
     *
     * 三种情况都算「不完整」：取消、任一对象信息拿不到、任一存储的句柄列表拿不到。
     * 不完整时目录仍可给界面显示（照片墙可以少几张），但**不许**拿它当 `.part` 保留集。
     */
    public static ScanResult of(Catalog catalog, int scanned, int dropped, int missingHandles, boolean cancelled) {
        boolean complete = !cancelled && dropped == 0 && missingHandles == 0;
        return new ScanResult(catalog, complete, scanned, dropped, missingHandles);
    }

    /** 跳过 `.part` 清理的原因（中文、给用户看）；只在 {@link #complete} 为 false 时有意义。 */
    public String skipReason() {
        StringBuilder sb = new StringBuilder("本次枚举不完整，跳过无主半成品清理：");
        sb.append("已扫 ").append(scanned).append(" 个对象");
        if (dropped > 0) {
            sb.append("，丢弃 ").append(dropped).append(" 个（相机未返回对象信息）");
        }
        if (missingHandles > 0) {
            sb.append("，").append(missingHandles).append(" 个存储未返回对象列表（USB 会话可能异常）");
        }
        return sb.toString();
    }
}
