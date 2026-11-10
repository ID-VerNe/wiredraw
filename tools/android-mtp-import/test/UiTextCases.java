package com.rea.mtpimport;


import static com.rea.mtpimport.TestSupport.*;

/** 颜色 token、返回键路由与短文案的断言组（#318 从 SelfTest.java 拆出）。 */
final class UiTextCases {

    // ---------------- 颜色 token 与对比度（PR-2 / issue #78） ----------------

    /**
     * 守住 `ColorTokens` 的数值与派生关系。UI-SPEC §3.3 / V-07 的实算值，
     * 任何一条改坏都会让下面至少一个用例红（变异实验见 issue #78 的 plan）。
     */
    static void colorTokensCases() {
        check("ColorTokens：白色页面底是 #ffffff（不再是 canvas）",
                ColorTokens.SURFACE_WHITE == 0xFFFFFFFF, "实际 " + hex(ColorTokens.SURFACE_WHITE));
        check("ColorTokens：canvas 专指画布黑 #1d1d1f",
                ColorTokens.CANVAS == 0xFF1D1D1F, "实际 " + hex(ColorTokens.CANVAS));
        check("ColorTokens：ink 是 #111111（只作主 CTA 底）",
                ColorTokens.INK == 0xFF111111, "实际 " + hex(ColorTokens.INK));
        checkContrast("#ffffff on ink（主 CTA 文字）", ColorTokens.SURFACE_WHITE, ColorTokens.INK, 18.88);
        checkContrast("#ffffff on canvas（photo 页胶囊/浅色图标）", ColorTokens.SURFACE_WHITE, ColorTokens.CANVAS, 16.83);
        checkContrast("on-surface on #ffffff（正文）", ColorTokens.ON_SURFACE, ColorTokens.SURFACE_WHITE, 17.12);
        checkContrast("on-surface on surface-container（计数胶囊）",
                ColorTokens.ON_SURFACE, ColorTokens.SURFACE_CONTAINER, 14.75);
        checkContrast("on-surface-variant on #ffffff（次级文字）",
                ColorTokens.ON_SURFACE_VARIANT, ColorTokens.SURFACE_WHITE, 9.37);
        checkContrast("outline on #ffffff（图标/描边下限）", ColorTokens.OUTLINE, ColorTokens.SURFACE_WHITE, 4.47);
        double inkOnCanvas = ColorTokens.contrastRatio(ColorTokens.INK, ColorTokens.CANVAS);
        check("ink on canvas 实算 1.12:1", Math.abs(inkOnCanvas - 1.12) <= 0.05,
                "实际 " + number(inkOnCanvas));
        check("ink on canvas 低于 3:1（深色面禁用文字近黑）", inkOnCanvas < 3.0,
                "实际 " + number(inkOnCanvas));

        check("Pill 深色底 = canvas 实底（不再是白色 alpha）",
                ColorTokens.pillBackground(true) == ColorTokens.CANVAS,
                "实际 " + hex(ColorTokens.pillBackground(true)));
        check("Pill 深色前景 = #ffffff",
                ColorTokens.pillForeground(true) == ColorTokens.SURFACE_WHITE,
                "实际 " + hex(ColorTokens.pillForeground(true)));
        checkContrast("Pill 深色：白字压 canvas", ColorTokens.pillForeground(true), ColorTokens.pillBackground(true), 16.83);
        check("Pill 浅色底 = surface-container #eeeeee",
                ColorTokens.pillBackground(false) == ColorTokens.SURFACE_CONTAINER,
                "实际 " + hex(ColorTokens.pillBackground(false)));
        check("Pill 浅色前景 = on-surface #1a1c1c",
                ColorTokens.pillForeground(false) == ColorTokens.ON_SURFACE,
                "实际 " + hex(ColorTokens.pillForeground(false)));
        checkContrast("Pill 浅色：on-surface 压 surface-container",
                ColorTokens.pillForeground(false), ColorTokens.pillBackground(false), 14.75);

        // alpha 面按当前 token 重算：遮罩 40% canvas 压白 = #a5a5a5（装饰，不承载正文）；
        // 未选格灰块 60% surface-container 压白 = #f5f5f5（文件名 secondary 仍有 8.6:1）。
        int scrimOnWhite = ColorTokens.compositeOver(ColorTokens.SCRIM, ColorTokens.SURFACE_WHITE);
        check("遮罩 40% canvas 合成 = #a5a5a5（装饰面）", scrimOnWhite == 0xFFA5A5A5, "实际 " + hex(scrimOnWhite));
        int cellOnWhite = ColorTokens.compositeOver(0x99EEEEEE, ColorTokens.SURFACE_WHITE);
        check("未选格 60% surface-container 合成 = #f5f5f5", cellOnWhite == 0xFFF5F5F5, "实际 " + hex(cellOnWhite));
        check("次级文字压未选格灰块 ≥4.5:1",
                ColorTokens.contrastRatio(ColorTokens.ON_SURFACE_VARIANT, cellOnWhite) >= 4.5,
                "实际 " + number(ColorTokens.contrastRatio(ColorTokens.ON_SURFACE_VARIANT, cellOnWhite)));
    }

    static void checkContrast(String name, int fg, int bg, double expected) {
        double actual = ColorTokens.contrastRatio(fg, bg);
        check(name + " 实算 " + number(expected) + ":1",
                Math.abs(actual - expected) <= 0.05, "实际 " + number(actual));
    }

    static String hex(int argb) {
        return String.format(java.util.Locale.ROOT, "#%08X", argb);
    }

    // ---------------- 系统返回键路由（BackNav，issue #48） ----------------

    static void backNavCases() {
        check("IMPORTING 返回键弹 Pause & exit 确认框",
                BackNav.decide(UiState.State.IMPORTING, false, false, false, false)
                        == BackNav.Action.OPEN_EXIT_CONFIRM,
                "实际 " + BackNav.decide(UiState.State.IMPORTING, false, false, false, false));
        check("PAUSED 返回键一按回照片墙",
                BackNav.decide(UiState.State.PAUSED, false, false, false, false)
                        == BackNav.Action.BACK_TO_CATALOG,
                "实际 " + BackNav.decide(UiState.State.PAUSED, false, false, false, false));
        check("DONE 返回键回照片墙而不是退出应用（issue #48 主症状）",
                BackNav.decide(UiState.State.DONE, false, false, false, false)
                        == BackNav.Action.BACK_TO_CATALOG,
                "实际 " + BackNav.decide(UiState.State.DONE, false, false, false, false));
        check("ERROR 返回键回照片墙（§1.3 不丢选择）",
                BackNav.decide(UiState.State.ERROR, false, false, false, false)
                        == BackNav.Action.BACK_TO_CATALOG,
                "实际 " + BackNav.decide(UiState.State.ERROR, false, false, false, false));
        check("UNAVAILABLE 返回键回照片墙",
                BackNav.decide(UiState.State.UNAVAILABLE, false, false, false, false)
                        == BackNav.Action.BACK_TO_CATALOG,
                "实际 " + BackNav.decide(UiState.State.UNAVAILABLE, false, false, false, false));
        check("CATALOG_READY 是主页，返回键退出应用",
                BackNav.decide(UiState.State.CATALOG_READY, false, false, false, false)
                        == BackNav.Action.EXIT,
                "实际 " + BackNav.decide(UiState.State.CATALOG_READY, false, false, false, false));
        check("启动态（COLD/PERMISSION/SCANNING/EMPTY）仍返回退出",
                BackNav.decide(UiState.State.COLD, false, false, false, false) == BackNav.Action.EXIT
                        && BackNav.decide(UiState.State.PERMISSION_PENDING, false, false, false, false)
                        == BackNav.Action.EXIT
                        && BackNav.decide(UiState.State.SCANNING, false, false, false, false)
                        == BackNav.Action.EXIT
                        && BackNav.decide(UiState.State.EMPTY_CATALOG, false, false, false, false)
                        == BackNav.Action.EXIT,
                "启动态返回口径不符");
        check("浮层优先：确认框 > 过滤 > 详情 > 照片",
                BackNav.decide(UiState.State.DONE, true, true, true, true)
                        == BackNav.Action.CLOSE_EXIT_CONFIRM
                        && BackNav.decide(UiState.State.DONE, false, true, true, true)
                        == BackNav.Action.CLOSE_FILTER
                        && BackNav.decide(UiState.State.DONE, false, false, true, true)
                        == BackNav.Action.CLOSE_DETAILS
                        && BackNav.decide(UiState.State.DONE, false, false, false, true)
                        == BackNav.Action.CLOSE_PHOTO,
                "浮层优先级不符");
        check("所有非主页终态都不走 EXIT（issue #48 防复发）",
                nonExitStates(),
                "出现 EXIT 的状态：" + nonExitStatesDetail());
    }

    /** 非主页终态集合：这些状态下系统返回键不得退出应用。 */
    static boolean nonExitStates() {
        for (UiState.State s : new UiState.State[]{
                UiState.State.PAUSED, UiState.State.DONE,
                UiState.State.ERROR, UiState.State.UNAVAILABLE}) {
            if (BackNav.decide(s, false, false, false, false) == BackNav.Action.EXIT) return false;
        }
        return true;
    }

    static String nonExitStatesDetail() {
        StringBuilder out = new StringBuilder();
        for (UiState.State s : new UiState.State[]{
                UiState.State.PAUSED, UiState.State.DONE,
                UiState.State.ERROR, UiState.State.UNAVAILABLE}) {
            if (BackNav.decide(s, false, false, false, false) == BackNav.Action.EXIT) {
                if (out.length() > 0) out.append(", ");
                out.append(s.name());
            }
        }
        return out.toString();
    }

    // ---------------- 数字与短文案（UiText，C-05 / C-08 / U-45） ----------------

    static void uiTextCases() {
        long mb = 1024L * 1024L;
        long gb = mb * 1024L;

        check("capacity: 0 → 0 MB", "0 MB".equals(UiText.capacity(0)), "实际 " + UiText.capacity(0));
        check("capacity: 1 MB 整数不带小数", "1 MB".equals(UiText.capacity(mb)), "实际 " + UiText.capacity(mb));
        check("capacity: 1.5 MB 保留 1 位", "1.5 MB".equals(UiText.capacity(mb + mb / 2)),
                "实际 " + UiText.capacity(mb + mb / 2));
        check("capacity: 284 MB（组头口径）", "284 MB".equals(UiText.capacity(284L * mb)),
                "实际 " + UiText.capacity(284L * mb));
        check("capacity: ≥100 MB 取整", "858 MB".equals(UiText.capacity(858L * mb)),
                "实际 " + UiText.capacity(858L * mb));
        check("capacity: 999 MB 仍是 MB", UiText.capacity(999L * mb).endsWith(" MB"),
                "实际 " + UiText.capacity(999L * mb));
        check("capacity: 1 GB → 2 位小数", "1.00 GB".equals(UiText.capacity(gb)),
                "实际 " + UiText.capacity(gb));
        long twelvePoint75Gb = 13_690_208_256L;
        check("capacity: 12.75 GB → 2 位小数", "12.75 GB".equals(UiText.capacity(twelvePoint75Gb)),
                "实际 " + UiText.capacity(twelvePoint75Gb));
        check("capacity: 小容量不出现 0.00 GB", !UiText.capacity(50L * mb).contains("GB"),
                "实际 " + UiText.capacity(50L * mb));
        check("capacity: 负数按 0 处理", "0 MB".equals(UiText.capacity(-1)), "实际 " + UiText.capacity(-1));

        check("groupHeader 是组内聚合且用中点分隔",
                "Jun 7 \u00B7 12 \u00B7 284 MB".equals(UiText.groupHeader("Jun 7", 12, 284L * mb)),
                "实际 " + UiText.groupHeader("Jun 7", 12, 284L * mb));

        check("importCta 未选 → Import all N（All 档 512，U-53/#83）",
                "Import all 512".equals(UiText.importCta(0, 512)),
                "实际 " + UiText.importCta(0, 512));
        check("importCta 已选 → Import N（分母仍是可见总量，C-08）",
                "Import 2".equals(UiText.importCta(2, 512)),
                "实际 " + UiText.importCta(2, 512));
        check("importCta RAW 档未选 → Import all 511（U-53/#83）",
                "Import all 511".equals(UiText.importCta(0, 511)),
                "实际 " + UiText.importCta(0, 511));

        check("percent 12/36 → 33 且与 a/b 自洽", UiText.percent(12, 36) == 33,
                "实际 " + UiText.percent(12, 36));
        check("percent 边界 0 与满", UiText.percent(0, 36) == 0 && UiText.percent(36, 36) == 100,
                "实际 " + UiText.percent(0, 36) + "/" + UiText.percent(36, 36));
        check("percent 分母为 0 不除零", UiText.percent(5, 0) == 0, "实际 " + UiText.percent(5, 0));
        // #266：未传完不得显示 100%。文件计数口径下 511/512 会 round 成 100，
        // 最后一个 RAW 还在传时环内就报「已完成」，误导可持续数秒（U-45 追加断言）。
        check("percent 511/512 未传完钉 99，不显示 100%", UiText.percent(511, 512) == 99,
                "实际 " + UiText.percent(511, 512));
        check("percentText 511/512 → `99%`", "99%".equals(UiText.percentText(511, 512)),
                "实际 " + UiText.percentText(511, 512));
        check("percent 199/200（99.5%）未传完也钉 99", UiText.percent(199, 200) == 99,
                "实际 " + UiText.percent(199, 200));
        check("percent 200/200 传完才 100", UiText.percent(200, 200) == 100,
                "实际 " + UiText.percent(200, 200));
        check("percentText 带百分号", "33%".equals(UiText.percentText(12, 36)),
                "实际 " + UiText.percentText(12, 36));
        check("fraction 格式 a/b", "12/36".equals(UiText.fraction(12, 36)),
                "实际 " + UiText.fraction(12, 36));
        check("scanCounter 只给进度分母", "320/1526".equals(UiText.scanCounter(320, 1526)),
                "实际 " + UiText.scanCounter(320, 1526));

        // issue #53：P-07 / U-21 要求续传跳过数进 Details 抽屉；字面量在这里钉住。
        check("skippedLine 形如 `Skipped N existing`（P-07 / U-21）",
                "Skipped 12 existing".equals(UiText.skippedLine(12)),
                "实际 " + UiText.skippedLine(12));
        check("skippedLine 0 不显示（不得伪装「跳过 0」）",
                "".equals(UiText.skippedLine(0)),
                "实际 " + UiText.skippedLine(0));
        check("skippedLine 负数按 0 处理", "".equals(UiText.skippedLine(-3)),
                "实际 " + UiText.skippedLine(-3));

        // issue #211：逐文件跳过 / 取消 / 字节对账 / 同尺寸跳过 / 本轮摘要都收进 UiText。
        check("fileSkippedNote 与 skippedLine 同一词表（P-07 / U-21）",
                "Skipped existing".equals(UiText.fileSkippedNote()),
                "实际 " + UiText.fileSkippedNote());
        check("transferCancelled 句写明「从 0 重来」",
                "已取消，整个半成品已删除（从 0 重来）".equals(UiText.transferCancelled()),
                "实际 " + UiText.transferCancelled());
        check("sizeMismatch 带期望与实际",
                "字节数不符：期望 1024，实际 1000".equals(UiText.sizeMismatch(1024, 1000)),
                "实际 " + UiText.sizeMismatch(1024, 1000));
        check("sameSizeExists 必须写明「内容未校验」（#177）",
                UiText.sameSizeExists().contains("内容未校验")
                        && UiText.sameSizeExists().contains("未覆盖"),
                "实际 " + UiText.sameSizeExists());
        check("runSummary 形如 `完成 7 / 跳过 2 / 失败 1（共 10 个）`",
                "完成 7 / 跳过 2 / 失败 1（共 10 个）".equals(UiText.runSummary(7, 2, 1, 10, "")),
                "实际 " + UiText.runSummary(7, 2, 1, 10, ""));
        check("runSummary 带停止原因与中点分隔",
                "完成 1 / 跳过 0 / 失败 0（共 1 个） · 已取消".equals(UiText.runSummary(1, 0, 0, 1, "已取消")),
                "实际 " + UiText.runSummary(1, 0, 0, 1, "已取消"));

        String summary = UiText.doneSummary(36, 858L * mb);
        check("doneSummary 形如 `36 · 858 MB`（U-47）", summary.matches("\\d+ \u00B7 \\d+ MB"),
                "实际 " + summary);
        check("doneSummary GB 口径也合法",
                UiText.doneSummary(511, (long) (11.89 * gb)).matches("\\d+ \u00B7 \\d+\\.\\d{2} GB"),
                "实际 " + UiText.doneSummary(511, (long) (11.89 * gb)));

        check("rate 3.4 MB/s", "3.4 MB/s".equals(UiText.rate(3_567_104L)),
                "实际 " + UiText.rate(3_567_104L));
        check("rate 0 不除零", "0 MB/s".equals(UiText.rate(0)), "实际 " + UiText.rate(0));
        check("eta 有值且带 ~ 与 min", !UiText.eta(1000L * mb, 3_567_104L).isEmpty()
                        && UiText.eta(1000L * mb, 3_567_104L).startsWith("~"),
                "实际 " + UiText.eta(1000L * mb, 3_567_104L));

        check("monthDay 六月七日", "Jun 7".equals(UiText.monthDay(2026, 6, 7)),
                "实际 " + UiText.monthDay(2026, 6, 7));
        check("monthDay 月份越界返回 null", UiText.monthDay(2026, 13, 1) == null,
                "实际 " + UiText.monthDay(2026, 13, 1));

        check("deviceName 空值退回 Camera",
                "Camera".equals(UiText.deviceName(null)) && "Camera".equals(UiText.deviceName("  "))
                        && "a7C".equals(UiText.deviceName("a7C")),
                "实际 " + UiText.deviceName(null));
    }
}
