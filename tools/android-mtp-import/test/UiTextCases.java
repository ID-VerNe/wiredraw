package com.rea.mtpimport;

import java.io.File;

import static com.rea.mtpimport.TestSupport.*;
import static com.rea.mtpimport.SourceGuard.*;

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

    // ---------------- 返回键确认框（#374 IA-8 / `T-EXIT` / `V-33`） ----------------

    /**
     * 框里必须问出来、两个选项等宽、实心项是「继续导入」。
     *
     * 文案在这一层直接判；结构只能按源文本判 —— 自检是纯 JDK，起不了 Compose、也拿不到布局结果，
     * 宽度与主次由真机 dump 判（`U-57`）。这里钉的是「有人把标题删了 / 把两个按钮换回一宽一窄」。
     */
    static void exitDialogCases() {
        String title = UiText.exitConfirmTitle();
        check("T-EXIT 有问句且 ≤20 字符（C-01）",
                title.endsWith("?") && title.length() <= 20, "实际 " + title);
        check("T-EXIT 两项与词表一致（W-24 / W-11）",
                "Keep going".equals(UiText.keepGoing()) && "Pause & exit".equals(UiText.pauseExit()),
                "实际 " + UiText.keepGoing() + " / " + UiText.pauseExit());

        File app = source("com/rea/mtpimport/ui/ImportApp.kt");
        String src = readSourceOrNull(app);
        if (src == null) {
            check("确认框：能读到 ImportApp.kt（#374 IA-8）", false, "读不到 " + app);
            return;
        }
        // PA+PB 给确认框加了 `visible` 参数（浮层骨架常驻组合，由 OverlayHost 驱动入场/退场，
        // `V-36`）。外面的 `Box` 换成 `OverlayHost` 后内层缩进不变，本组其余断言原样成立；
        // 只把切片锚点跟着签名改，别把「提问 / 等宽 / 主次」这些契约放宽。
        String dialog = slice(src, "private fun ExitConfirmDialog(model: AppModel, visible: Boolean) {", "\n}\n");
        boolean hasDialog = dialog != null;
        // 标题：既是可见文本，也是读屏进框的第一句话（paneTitle）。
        boolean hasTitle = hasDialog && countLines(dialog, "UiText.exitConfirmTitle()") == 2;
        boolean titleProperty = hasDialog && dialog.contains("paneTitle = UiText.exitConfirmTitle()");
        // 等宽：两个按钮都吃 `Modifier.fillMaxWidth()`，不能再让次选项缩成 94 dp（IA-8 实测 340 vs 94）。
        boolean bothFullWidth = hasDialog
                && countLines(dialog, "modifier = Modifier.fillMaxWidth()") == 2;
        // 主次：实心（PrimaryButton）挂 keepGoing，描边（SecondaryButton）挂 pauseExit。
        boolean keepIsSolid = hasDialog
                && dialog.indexOf("PrimaryButton(\n                UiText.keepGoing()") > 0
                && dialog.indexOf("SecondaryButton(\n                UiText.pauseExit()") > 0;
        check("确认框：提问 + 两个等宽选项 + 实心项是 Keep going（#374 IA-8 / V-33）",
                hasDialog && hasTitle && titleProperty && bothFullWidth && keepIsSolid,
                "能切到函数体 " + hasDialog + " / 标题两处 " + hasTitle + " / paneTitle " + titleProperty
                        + " / 等宽 " + bothFullWidth + " / 主次 " + keepIsSolid);
        // 字面量只在 UiText：框里不许再出现 `"Pause & exit"` 这类内联文案（§4.5 唯一出处）。
        check("确认框文案只在 UiText：ImportApp 内联字面量 0 命中（§4.5）",
                countLines(src, "\"Pause & exit\"") == 0
                        && countLines(src, "\"Keep going\"") == 0
                        && countLines(src, "\"Resume\"") == 0,
                "ImportApp 里仍有内联按钮文案");
    }

    // ---------------- 状态播报（#374 IA-10 / `A-08`） ----------------

    /**
     * 播报文案 + 三个时机 + 首帧不播。
     *
     * 「真的播出去了」只能靠 TalkBack 取证（`U-58`）；这里钉的是更容易悄悄丢的东西：
     * 文案格式、三个 `when` 分支、以及**首帧早退**那行——删掉它，冷启动与 `demo_state`
     * 会一进应用就念「扫描完成 512 张」。
     */
    static void announceCases() {
        check("A-08 扫描完成播报：`Scanned 512 photos`",
                "Scanned 512 photos".equals(UiText.announceScanDone(512)),
                "实际 " + UiText.announceScanDone(512));
        check("A-08 导入完成播报：`Done, 36 photos`",
                "Done, 36 photos".equals(UiText.announceDone(36)),
                "实际 " + UiText.announceDone(36));

        File app = source("com/rea/mtpimport/ui/ImportApp.kt");
        String src = readSourceOrNull(app);
        if (src == null) {
            check("播报：能读到 ImportApp.kt（#374 IA-10）", false, "读不到 " + app);
            return;
        }
        String announcer = slice(src, "private fun StateAnnouncer(model: AppModel) {", "\n}\n");
        boolean hasAnnouncer = announcer != null;
        boolean announces = hasAnnouncer && announcer.contains("announceForAccessibility(");
        boolean effectOnState = hasAnnouncer && announcer.contains("LaunchedEffect(state)");
        boolean firstFrameSkipped = hasAnnouncer
                && hasStatement(announcer, 8, "if (prev == null) return@LaunchedEffect");
        boolean threeMoments = hasAnnouncer
                && announcer.contains("UiState.State.CATALOG_READY -> UiText.announceScanDone(")
                && announcer.contains("UiState.State.DONE -> UiText.announceDone(")
                && announcer.contains("UiState.State.ERROR -> model.errorReason");
        check("播报：三时机 + 首帧不播 + 显式 announce（#374 IA-10 / A-08）",
                hasAnnouncer && announces && effectOnState && firstFrameSkipped && threeMoments,
                "能切到函数体 " + hasAnnouncer + " / announce " + announces + " / LaunchedEffect "
                        + effectOnState + " / 首帧早退 " + firstFrameSkipped + " / 三时机 " + threeMoments);
        // 渐进发布不播报：`CATALOG_PARTIAL` 不在这三个分支里，也不许有人「顺手」加进去。
        check("播报：渐进发布（CATALOG_PARTIAL）不播报（A-08）",
                hasAnnouncer && !announcer.contains("CATALOG_PARTIAL"),
                "StateAnnouncer 里出现了 CATALOG_PARTIAL：每 200 个对象会念一次");
        // 挂在 `ImportApp` 的**根**上：放进 `overlayOpen` 那个分支里的话，开关一次抽屉就重挂一次，
        // 每次都把 `last` 清成 null，下一次状态变化会被当成首帧吞掉。
        int callSites = countLines(src, "StateAnnouncer(model)");
        check("播报器挂在根 composable 上，且不随浮层开关重挂（#374 IA-10）",
                callSites == 1 && src.contains("private fun StateAnnouncer(model: AppModel) {"),
                "调用点 " + callSites + " 行（应为 1），且必须有函数定义");
    }
}
