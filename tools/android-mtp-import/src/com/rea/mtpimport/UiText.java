package com.rea.mtpimport;

import java.util.Locale;

/**
 * 用户可见数字与短文案的唯一格式化入口（UI-SPEC §4.2 的 C-05～C-10）。
 *
 * 放在纯 JDK 类里而不是 Compose 里，是为了让口径能被 selfTest 直接断言：
 * 容量换算、小数位、CTA 分母、组头聚合、百分比与 a/b 自洽，都是可机械判定的。
 */
public final class UiText {

    private static final long KB = 1024L;
    private static final long MB = KB * 1024L;
    private static final long GB = MB * 1024L;

    private static final String[] MONTHS = {
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };

    private UiText() {
    }

    /**
     * 容量：1024 进制，GB 2 位小数、MB 最多 1 位小数（C-05）。
     *
     * 单位分界取 1 GB：定稿的组头与 `DONE` 用的都是 MB（`Jun 7 · 12 · 284 MB`、
     * `36 · 871 MB`），所以 1 GB 以下一律走 MB；C-06 的「小于 0.1 GB 必须降级为 MB」
     * 是这条规则的下界，天然满足，且不会出现 `0.00 GB`（C-07）。
     */
    public static String capacity(long bytes) {
        if (bytes < 0) bytes = 0;
        if (bytes >= GB) {
            return String.format(Locale.US, "%.2f GB", bytes / (double) GB);
        }
        double mb = bytes / (double) MB;
        double rounded = Math.round(mb * 10.0) / 10.0;
        if (rounded >= 100.0) {
            // 100 MB 以上按整数展示（`871 MB` / `284 MB`），与 U-44 的组头正则一致。
            return String.format(Locale.US, "%.0f MB", rounded);
        }
        if (Math.abs(rounded - Math.rint(rounded)) < 1e-9) {
            return String.format(Locale.US, "%.0f MB", rounded);
        }
        return String.format(Locale.US, "%.1f MB", rounded);
    }

    /** 日期组头：`Jun 7 · 12 · 284 MB`，数字是组内聚合（§0.6 / V-12 / U-44）。 */
    public static String groupHeader(String label, int count, long bytes) {
        return label + " · " + count + " · " + capacity(bytes);
    }

    /** 主 CTA：已选 0 → `Import all N`；已选 ≥1 → `Import N`（§4.5 唯一出处）。 */
    public static String importCta(int selectedCount, int totalPhotos) {
        return selectedCount <= 0 ? "Import all " + totalPhotos : "Import " + selectedCount;
    }

    /** 环下计数：`12/36`（P-03）。 */
    public static String fraction(int done, int total) {
        return done + "/" + total;
    }

    /** 环内百分比：`round(done/total×100)`，与环下 a/b 严格自洽（U-45）。 */
    public static int percent(int done, int total) {
        if (total <= 0) return 0;
        if (done <= 0) return 0;
        if (done >= total) return 100;
        return (int) Math.round(done * 100.0 / total);
    }

    /** 环内百分比文本。 */
    public static String percentText(int done, int total) {
        return percent(done, total) + "%";
    }

    /** `DONE` 的 `36 · 871 MB`（U-47 的正则口径，容量按 C-05）。 */
    public static String doneSummary(int count, long bytes) {
        return count + " · " + capacity(bytes);
    }

    /** 扫描进度分母：`320/1526`，`1526` 只作分母（C-09 / U-43）。 */
    public static String scanCounter(int scanned, int total) {
        return scanned + "/" + total;
    }

    /** 详情抽屉里的速率（P-03：不进主视觉）。 */
    public static String rate(long bytesPerSecond) {
        if (bytesPerSecond <= 0) return "0 MB/s";
        double mb = bytesPerSecond / (double) MB;
        double rounded = Math.round(mb * 10.0) / 10.0;
        if (Math.abs(rounded - Math.rint(rounded)) < 1e-9) {
            return String.format(Locale.US, "%.0f MB/s", rounded);
        }
        return String.format(Locale.US, "%.1f MB/s", rounded);
    }

    /** 详情抽屉里的剩余时间（P-03：不进主视觉）。 */
    public static String eta(long remainingBytes, long bytesPerSecond) {
        if (bytesPerSecond <= 0 || remainingBytes <= 0) return "";
        long seconds = Math.round(remainingBytes / (double) bytesPerSecond);
        long minutes = Math.max(1, Math.round(seconds / 60.0));
        return "~" + minutes + " min";
    }

    /** `2026, 6, 7` → `Jun 7`；月份越界返回 null（调用方退回目录名）。 */
    public static String monthDay(int year, int month, int day) {
        if (month < 1 || month > 12) return null;
        if (day < 1 || day > 31) return null;
        return MONTHS[month - 1] + " " + day;
    }

    /** 顶栏设备短名；空值退回 `Camera`（§0.10 的用词）。 */
    public static String deviceName(String raw) {
        if (raw == null) return "Camera";
        String s = raw.trim();
        return s.isEmpty() ? "Camera" : s;
    }
}
