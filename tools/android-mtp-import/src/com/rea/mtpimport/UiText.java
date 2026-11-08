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
     * `36 · 858 MB`），所以 1 GB 以下一律走 MB；C-06 的「小于 0.1 GB 必须降级为 MB」
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
            // 100 MB 以上按整数展示（`858 MB` / `284 MB`），与 U-44 的组头正则一致。
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

    /**
     * 进度环的分数（0f～1f）：分母 ≤0 记 0，越界一律夹紧（#214 / F-07）。
     *
     * 扫描环 / 传输环 / 照片墙细线都只调这一个函数，页面不再各写一份
     * 「`if (total > 0) done/total else 0f`」；`{@link Widgets}` 的控件侧夹紧仍保留，
     * 因为 `ProgressRing` 是通用控件，入参不保证已归一。
     */
    public static float progressFraction(int done, int total) {
        if (total <= 0) return 0f;
        float f = done / (float) total;
        if (f < 0f) return 0f;
        if (f > 1f) return 1f;
        return f;
    }

    /**
     * 环内百分比：`round(done/total×100)`，与环下 a/b 严格自洽（U-45）。
     *
     * #266：`done < total` 时钉在 99，未传完不得显示 100%。文件计数口径下
     * `511/512 → round(99.8) = 100`，最后一个 RAW 还在传时环内就报「已完成」，
     * 误导可持续数秒；`#180` 删掉字节字段后这条上限没有跟着迁移，本次补上。
     */
    public static int percent(int done, int total) {
        if (total <= 0) return 0;
        if (done <= 0) return 0;
        if (done >= total) return 100;
        // 只剩最后几个文件时 round 会逼近 100，钳到 99；真正的 100 只留给 done >= total。
        return Math.min((int) Math.round(done * 100.0 / total), 99);
    }

    /** 环内百分比文本。 */
    public static String percentText(int done, int total) {
        return percent(done, total) + "%";
    }

    /** `DONE` 的 `36 · 858 MB`（假树值，口径见 `UI-SPEC.md` §3.1 与 `U-54`；U-47 的正则口径，容量按 C-05）。 */
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

    /** 逐文件跳过 note（Details 里挂在 `[Skipped]` 后）；计数句仍走 {@link #skippedLine(int)}。 */
    public static String fileSkippedNote() {
        return "Skipped existing";
    }

    /** 取消句：半成品整体删除、下一轮从 0 重来（#177 续传停用后的唯一取消口径）。 */
    public static String transferCancelled() {
        return "已取消，整个半成品已删除（从 0 重来）";
    }

    /** 字节对账不符：期望与实际都必须带出来，方便真机排障。 */
    public static String sizeMismatch(long expected, long actual) {
        return "字节数不符：期望 " + expected + "，实际 " + actual;
    }

    /**
     * 目标已存在同尺寸文件：按 SKIPPED 记、不覆盖。
     *
     * **尺寸对账不等于内容校验**（#177），句子必须写明「内容未校验」，不能暗示校验通过。
     */
    public static String sameSizeExists() {
        return "已存在同尺寸文件，未覆盖（仅尺寸核对，内容未校验）";
    }

    /** 本轮结束摘要：`完成 7 / 跳过 2 / 失败 1（共 10 个） · 已取消`（`stop` 为空时不带尾巴）。 */
    public static String runSummary(int ok, int skipped, int failed, int total, String stop) {
        String s = "完成 " + ok + " / 跳过 " + skipped + " / 失败 " + failed + "（共 " + total + " 个）";
        return stop == null || stop.isEmpty() ? s : s + " · " + stop;
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
