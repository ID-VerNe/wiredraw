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
        if (rounded >= 10.0) {
            // 10～99 MB 固定 1 位小数：U-53 / U-55 的 `Jun 7 · 43 · 74.0 MB` 是字面量，
            // 组头与 filter 芯片的负向断言都按它判定（`.0` 不能被吞掉）。
            return String.format(Locale.US, "%.1f MB", rounded);
        }
        if (Math.abs(rounded - Math.rint(rounded)) < 1e-9) {
            // 个位数容量仍按整数（`1 MB` / `0 MB`），避免小组头出现 `1.0 MB` 的噪声。
            return String.format(Locale.US, "%.0f MB", rounded);
        }
        return String.format(Locale.US, "%.1f MB", rounded);
    }

    /** 日期组头：`Jun 7 · 43 · 74.0 MB`，数字是当前档位下的组内导出载荷（§0.6 / V-12 / U-44）。 */
    public static String groupHeader(String label, int count, long bytes) {
        return label + " · " + count + " · " + capacity(bytes);
    }

    /**
     * 主 CTA（§4.5 唯一出处 / `C-08` / `C-10` / issue #83）：
     * 可见已选数为 0 → `Import all N`（N = 当前 filter 可见集合的导出文件数）；
     * 可见已选数 ≥1 → `Import N`（N = 可见已选数）。
     *
     * 分母与分子都只算**当前可见集合**：隐藏的已选项保留但不计入，避免出现可达的 `Import 0`。
     */
    public static String importCta(int visibleSelected, int visibleExportFiles) {
        return visibleSelected <= 0 ? "Import all " + visibleExportFiles
                : "Import " + visibleSelected;
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

    /**
     * issue #70（剩下一半）：传输环的进度口径（`T-IMPORTING` / `V-22` / `A-04`）。
     *
     * UI-SPEC 只规定环内是百分比、环下是 `12/36`（`P-03`），没有规定百分比按什么口径算。
     * #70 要求续传期间环也随字节推进，所以字节总量已知时用「已落盘字节 / 计划字节」；
     * 字节总量未知（<= 0，例如计划里全是大小未知的对象）时才退回文件数口径（P-03 的分母）。
     *
     * 钳在 [0,1] 是界面唯一的降级：失败 / 取消会 `rollbackFile()` 让 `bytesDone()` 往回走
     * （#70 的预期语义，不在这里修正单调性）；MTP 上报大小与对账大小可能不一致（探针实测两种都出现过）；
     * `getPartialObject` 的 offset 语义到 #67 仍无真机定论。界面只保证不出现负数、不超 100%。
     */
    public static double ringFraction(int doneFiles, int totalFiles, long doneBytes, long totalBytes) {
        double f;
        if (totalBytes > 0) {
            f = doneBytes / (double) totalBytes;
        } else if (totalFiles > 0) {
            f = doneFiles / (double) totalFiles;
        } else {
            return 0.0;
        }
        if (Double.isNaN(f) || f <= 0.0) return 0.0;
        return f >= 1.0 ? 1.0 : f;
    }

    /** 环内主数字：与 {@link #ringFraction} 同源，避免环弧与数字各说各话（`V-22` / `A-04`）。 */
    public static String ringPercentText(int doneFiles, int totalFiles, long doneBytes, long totalBytes) {
        return Math.round(ringFraction(doneFiles, totalFiles, doneBytes, totalBytes) * 100.0) + "%";
    }

    /** `DONE` 的 `36 · 871 MB`（U-47 的正则口径，容量按 C-05）。 */
    public static String doneSummary(int count, long bytes) {
        return count + " · " + capacity(bytes);
    }

    /** 扫描进度分母：`320/1526`，`1526` 只作分母（C-09 / U-43）。 */
    public static String scanCounter(int scanned, int total) {
        return scanned + "/" + total;
    }

    /**
     * 续传跳过数（`P-07` / `U-21` / 词表）：只进 `Details` 抽屉，主视觉不出现（`U-47` 同样禁止 `DONE` 首屏出现该句）。
     *
     * `skipped <= 0` 返回空串而不是 `Skipped 0 existing`：P-07 明确禁止把断点续传伪装成「跳过 0」，
     * 没有跳过的轮次也不该在抽屉里多一行噪声。
     */
    public static String skippedLine(int skipped) {
        if (skipped <= 0) return "";
        return "Skipped " + skipped + " existing";
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
