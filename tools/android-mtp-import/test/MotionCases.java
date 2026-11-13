package com.rea.mtpimport;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static com.rea.mtpimport.TestSupport.*;
import static com.rea.mtpimport.SourceGuard.*;

/**
 * 动效层的行为与结构护栏（#374 / PA+PB；`V-34`～`V-37`、`U-59`～`U-62`）。
 *
 * 自检是纯 JDK，起不了 Compose：能直接跑的只有 `Motion.java` 的**数值与降级行为**
 * （[motionBudgetCases]）；其余靠源码护栏钉住「唯一入口」——按压态、触觉、
 * 动画 spec、浮层宿主、系统倍率读取各只允许有一处，页面里再抄一份即红。
 *
 * 这些断言挡的不是「有没有写动画」这种一句话能自证的事，而是四种真实回退：
 * ① 某个页面又内联 `detectTapGestures(onLongPress` 并漏掉触觉；
 * ② 某个页面裸写 `tween(300)` 绕开 200 ms 预算与减少动效；
 * ③ 浮层退回裸 `if`，退出动画无从播起；
 * ④ 进度环直接对原始 ratio 插值，动画中途越过 99% 破 `U-45`。
 */
final class MotionCases {

    private static final String WIDGETS = "src/com/rea/mtpimport/ui/Widgets.kt";
    private static final String SPECS = "src/com/rea/mtpimport/ui/MotionSpecs.kt";
    private static final String HOST = "src/com/rea/mtpimport/ui/ComposeHost.kt";
    private static final String APP = "src/com/rea/mtpimport/ui/ImportApp.kt";
    private static final String LIBRARY = "src/com/rea/mtpimport/ui/LibraryScreen.kt";

    // ---------------- 数值与「减少动效」降级（可直接跑） ----------------

    static void motionBudgetCases() {
        // 全局静态量：本组结束时必须复位，别把 scale=0 漏给后面所有用例。
        try {
            check("V-36：单段动效硬上限 200 ms", Motion.MOTION_MAX_MS == 200,
                    "实际 " + Motion.MOTION_MAX_MS);
            check("V-36：三段标称时长都在预算内",
                    Motion.withinBudget(Motion.PRESS_MS)
                            && Motion.withinBudget(Motion.STATE_MS)
                            && Motion.withinBudget(Motion.OVERLAY_MS),
                    Motion.PRESS_MS + " / " + Motion.STATE_MS + " / " + Motion.OVERLAY_MS);
            check("V-36：0 与超过上限的标称时长都不合法",
                    !Motion.withinBudget(0) && !Motion.withinBudget(Motion.MOTION_MAX_MS + 1),
                    "预算守卫放行了非法时长");
            check("V-34：按下态缩放到 0.97",
                    Math.abs((1f - Motion.PRESS_SHRINK) - 0.97f) < 1e-6f,
                    "实际 " + (1f - Motion.PRESS_SHRINK));
            check("V-34：临界阻尼，不回弹不过冲", Motion.DAMPING_CRITICAL == 1f,
                    "实际 " + Motion.DAMPING_CRITICAL);
            check("V-36：确认框从 0.96 缩入", Math.abs(Motion.DIALOG_FROM_SCALE - 0.96f) < 1e-6f,
                    "实际 " + Motion.DIALOG_FROM_SCALE);

            Motion.setDurationScale(1f);
            check("常规倍率：时长原样、不进瞬时模式",
                    !Motion.instant() && Motion.duration(Motion.STATE_MS) == Motion.STATE_MS,
                    "duration=" + Motion.duration(Motion.STATE_MS));

            Motion.setDurationScale(0f);
            check("V-37：ANIMATOR_DURATION_SCALE == 0 → instant() 且所有时长为 0",
                    Motion.instant()
                            && Motion.duration(Motion.STATE_MS) == 0
                            && Motion.duration(Motion.OVERLAY_MS) == 0
                            && Motion.duration(0) == 0,
                    "duration=" + Motion.duration(Motion.STATE_MS));

            Motion.setDurationScale(0.5f);
            check("V-37：半速倍率按倍率折算", Motion.duration(120) == 60,
                    "实际 " + Motion.duration(120));

            Motion.setDurationScale(3f);
            check("V-36：倍率 >1 也不得突破 200 ms 上限",
                    Motion.duration(Motion.STATE_MS) == Motion.MOTION_MAX_MS,
                    "实际 " + Motion.duration(Motion.STATE_MS));

            // #282 / U-45：环插值的目标值永远不越 99%，但 done == total 仍须画满。
            check("U-62：未完成环目标钳在 0.99",
                    Motion.ringTarget(0.998f) == Motion.RING_INCOMPLETE_CAP
                            && Motion.RING_INCOMPLETE_CAP == 0.99f,
                    "ringTarget(0.998)=" + Motion.ringTarget(0.998f));
            check("U-62：中间值原样、0/负数归 0、1 允许画满",
                    Motion.ringTarget(0.5f) == 0.5f
                            && Motion.ringTarget(0f) == 0f
                            && Motion.ringTarget(-1f) == 0f
                            && Motion.ringTarget(1f) == 1f,
                    "0.5→" + Motion.ringTarget(0.5f) + " / 1→" + Motion.ringTarget(1f));
        } finally {
            Motion.setDurationScale(1f);
        }
    }

    // ---------------- 唯一入口：按压态与长按触觉 ----------------

    static void motionSourceCases() {
        File srcRoot = SRC_ROOT;
        List<String> hits = new ArrayList<>();

        // 1. 按压态：`InteractionSource` 只被消费一次（"建好就扔" 是 IA-1 的原症状）。
        int scanned = scanSourceText(srcRoot, "collectIsPressedAsState(", WIDGETS, hits);
        check("V-34：InteractionSource 的消费点只在 Widgets（页面不得各写一份）",
                scanned > 0 && hits.isEmpty(),
                "扫描 " + scanned + " 个文件，命中：\n" + String.join("\n", hits));

        String widgets = readSourceOrNull(source(rel(WIDGETS)));
        String tappable = between(widgets, "fun Modifier.tappable(", "fun Modifier.tapOrHold(");
        check("V-34：tappable 把同一个 source 同时交给按压视觉与 clickable",
                tappable != null
                        && tappable.contains("pressFeedback(source, enabled, shrinkOnPress)")
                        && tappable.contains("interactionSource = source")
                        && tappable.contains("indication = null"),
                "tappable 切片：\n" + tappable);
        check("V-34：按压进度只在 draw phase 读（不触发重组）",
                widgets != null
                        && widgets.contains(".graphicsLayer {")
                        && widgets.contains(".drawBehind {")
                        && widgets.contains("Motion.PRESS_SHRINK * progress"),
                "Widgets.kt 未找到 graphicsLayer/drawBehind 的按压读值");

        // 2. 手势与触觉：全 src 的 detectTapGestures / performHapticFeedback 只在 Widgets。
        hits.clear();
        scanned = scanSourceText(srcRoot, "detectTapGestures(", WIDGETS, hits);
        check("V-35：真实触摸手势只在统一入口，页面不得内联 detector",
                scanned > 0 && hits.isEmpty(),
                "扫描 " + scanned + " 个文件，命中：\n" + String.join("\n", hits));
        hits.clear();
        scanSourceText(srcRoot, "HapticFeedbackType.LongPress", null, hits);
        boolean allInWidgets = true;
        for (String h : hits) {
            if (!h.contains("Widgets.kt")) allInWidgets = false;
        }
        check("V-35：三条长按路径共用恰好 2 处触觉调用（tapOrHold / longPressOnly）",
                hits.size() == 2 && allInWidgets,
                "命中 " + hits.size() + " 处：\n" + String.join("\n", hits));
        check("V-35：按压生命周期有 Release/Cancel 复位",
                widgets != null
                        && widgets.contains("PressInteraction.Press(offset)")
                        && widgets.contains("tryAwaitRelease()")
                        && widgets.contains("PressInteraction.Cancel(press)"),
                "缺 PressInteraction 的复位路径，按压态会一直压着");

        String library = readSourceOrNull(source(rel(LIBRARY)));
        int taps = countLines(library, ".tapOrHold(");
        check("V-35：组头与照片格都走 tapOrHold（恰 2 处）", taps == 2,
                "实际 " + taps + " 处");
        hits.clear();
        scanSourceText(srcRoot, "longPressOnly(key = Unit)", null, hits);
        check("V-35：顶栏 Details 长按也走统一入口（恰 1 处）", hits.size() == 1,
                "实际 " + hits.size() + " 处：\n" + String.join("\n", hits));

        // 3. 动画 spec 只由 MotionSpecs 产出：页面上裸写 tween/spring 会绕开预算与降级。
        hits.clear();
        scanned = scanSourceText(srcRoot, "tween(", SPECS, hits);
        check("V-36：全 src 不得裸写 tween(...)",
                scanned > 0 && hits.isEmpty(),
                "命中：\n" + String.join("\n", hits));
        hits.clear();
        scanSourceText(srcRoot, "spring(", SPECS, hits);
        check("V-36：全 src 不得裸写 spring(...)", hits.isEmpty(),
                "命中：\n" + String.join("\n", hits));
        String[] factories = {"fadeIn(", "fadeOut(",
                "scaleIn(", "scaleOut(", "slideInVertically(", "slideOutVertically("};
        for (String factory : factories) {
            hits.clear();
            scanSourceText(srcRoot, factory, null, hits);
            boolean everySpecFromMotion = !hits.isEmpty();
            for (String h : hits) {
                if (!h.contains("MotionSpecs")) everySpecFromMotion = false;
            }
            check("V-36：每个 " + factory + " 都取 MotionSpecs 的 spec", everySpecFromMotion,
                    "命中 " + hits.size() + " 处：\n" + String.join("\n", hits));
        }
        String specs = readSourceOrNull(source(rel(SPECS)));
        check("V-37：MotionSpecs 在 instant() 时统一退化成 SnapSpec",
                specs != null
                        && specs.contains("if (Motion.instant())")
                        && specs.contains("SnapSpec()")
                        && specs.contains("Motion.withinBudget(nominalMs)")
                        && specs.contains("Motion.DAMPING_CRITICAL"),
                "MotionSpecs.kt 未找到降级/预算/临界阻尼三件事");

        // 4. 浮层骨架常驻：三个浮层都走 OverlayHost，裸 if 会让退出动画没机会播。
        hits.clear();
        scanSourceText(srcRoot, "OverlayHost(", null, hits);
        check("V-36：三个浮层共用 OverlayHost（定义 1 + 调用 2 = 3 处）",
                hits.size() == 3, "实际 " + hits.size() + " 处：\n" + String.join("\n", hits));
        hits.clear();
        scanSourceText(srcRoot, "AnimatedVisibility(", WIDGETS, hits);
        int avScanned = scanSourceText(srcRoot, "AnimatedVisibility(", null, new ArrayList<String>());
        check("V-36：AnimatedVisibility 只在 OverlayHost 的两层",
                avScanned > 0 && hits.isEmpty(),
                "命中：\n" + String.join("\n", hits));
        String app = readSourceOrNull(source(rel(APP)));
        check("V-36：三屏与 photo 覆盖层都做 Crossfade，且没有位移",
                app != null && countLines(app, "Crossfade(") == 2
                        && app.contains("targetState = model.openedPhoto")
                        && !app.contains("slideInHorizontally")
                        && !app.contains("slideOutHorizontally"),
                "ImportApp.kt 的 Crossfade 计数=" + countLines(app, "Crossfade("));
        check("V-36：三个浮层常驻组合（不再裸 if 一帧移除）",
                app != null
                        && app.contains("DetailsDrawer(model)")
                        && app.contains("FilterDrawer(model)")
                        && app.contains("ExitConfirmDialog(model, visible = model.exitConfirmOpen)")
                        && !app.contains("if (model.detailsOpen)")
                        && !app.contains("if (model.filterOpen)"),
                "ImportApp.kt 仍有裸 if 浮层");

        // 5. 系统倍率只在唯一挂载点读，页面不得各自读 Settings。
        hits.clear();
        scanned = scanSourceText(srcRoot, "ANIMATOR_DURATION_SCALE", HOST, hits);
        check("V-37：动画倍率只在 ComposeHost 读一次",
                scanned > 0 && hits.isEmpty(),
                "命中：\n" + String.join("\n", hits));
        hits.clear();
        scanSourceText(srcRoot, "Motion.setDurationScale(", HOST, hits);
        check("V-37：写 Motion 的入口只有 ComposeHost", hits.isEmpty(),
                "命中：\n" + String.join("\n", hits));

        // 6. 环与选中态：都从插值进度派生，不再阶跃。
        String ring = between(widgets, "fun ProgressRing(", "internal fun OverlayHost(");
        check("U-62：进度环画插值、目标过 ringTarget，语义仍读逻辑进度",
                ring != null
                        && ring.contains("Motion.ringTarget(progress)")
                        && ring.contains("sweepAngle = 360f * sweep")
                        && ring.contains("progressBarRangeInfo = ProgressBarRangeInfo(clamped"),
                "ProgressRing 切片：\n" + ring);
        check("U-62：环的插值用无过冲的 state spec，不用 spring",
                ring != null && ring.contains("MotionSpecs.state<Float>()")
                        && !ring.contains("MotionSpecs.press()"),
                "ProgressRing 的 spec 不对");
        String cell = between(library, "private fun PhotoCell(", "private fun ImportBar(");
        check("U-62：选中态三处视觉都由同一个 selection 进度派生",
                cell != null
                        && cell.contains("val selection by animateFloatAsState(")
                        && cell.contains("0.6f + 0.4f * selection")
                        && cell.contains("softOutline(selection)")
                        && cell.contains("setToSaturation(saturation)")
                        && !cell.contains("colorFilter = if (!checked)"),
                "PhotoCell 切片：\n" + cell);
    }

    /**
     * `scanSourceText` 的 `allow` 要带 `src/` 前缀（它的相对路径从工程根算），
     * 而 `SourceGuard.source()` 的入参是**相对 src/** 的路径。两个口径差一个前缀，
     * 这里统一收口，免得新护栏拿空切片写出「恒假绿 / 恒假红」的断言。
     */
    private static String rel(String allowPath) {
        return allowPath.substring("src/".length());
    }

    /** 某段源码里含 needle 的行数（同一行多次只算一次，与 scanSourceText 口径一致）。 */
    private static int countLines(String src, String needle) {
        if (src == null) return 0;
        int n = 0;
        for (String line : src.split("\n", -1)) {
            if (line.contains(needle)) n++;
        }
        return n;
    }

    /** 取 from（含）到其后首个 to（不含）之间的切片；任一锚点缺失返回 null。 */
    private static String between(String src, String from, String to) {
        if (src == null) return null;
        int a = src.indexOf(from);
        if (a < 0) return null;
        int b = src.indexOf(to, a + from.length());
        return b < 0 ? null : src.substring(a, b);
    }
}
