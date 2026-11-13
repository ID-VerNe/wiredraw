package com.rea.mtpimport;

/**
 * 动效数值与「减少动效」判定的唯一出处（UI-SPEC §3.6，V-34～V-37 / U-59～U-62）。
 *
 * 纯 JDK：进 selfTest 的**空 classpath** 编译（清单见 gradle/self-test.gradle.kts 的
 * pureJdkClasses），所以这里不准 import android.*；Compose 侧只通过 MotionSpecs.kt
 * 把这里的值翻成 AnimationSpec，页面不得各写一份时长常量或动画工厂（§10 第 6 / 10 条）。
 *
 * 系统倍率由 ComposeHost 在唯一挂载点读一次写进来。默认 1f 让 JVM 自检与「还没读系统设置」
 * 的首帧行为一致：倍率只改**时间轴**，不改终态、语义、热区与 dump（V-37）。
 *
 * #282 / U-45 的 99% 钳位也在这里：进度环插值目标必须过 [ringTarget]，
 * 否则弹簧或过冲会在动画中途把「未完成」画成满环。
 */
public final class Motion {

    /** 任何单段动效的硬上限（V-36）。所有标称时长都要过 [withinBudget]。 */
    public static final int MOTION_MAX_MS = 200;

    /** 按压态的弹簧标称时长（V-34）：按下当帧生效，松手即回，不设最小展示时长。 */
    public static final int PRESS_MS = 90;

    /** 状态类动效：三屏交叉淡入、确认框缩入、选中态与进度环插值（V-36）。 */
    public static final int STATE_MS = 120;

    /** 大面积位移类动效：抽屉滑入 / 滑出与遮罩淡入 / 淡出（V-36）。 */
    public static final int OVERLAY_MS = 180;

    /** 按下态缩放 = 1 - PRESS_SHRINK = 0.97（V-34）。 */
    public static final float PRESS_SHRINK = 0.03f;

    /** 按压态的装饰底色最大 alpha（drawBehind 的 wash，不承载文字，不受 V-07 约束）。 */
    public static final float PRESS_WASH_ALPHA = 0.06f;

    /** 确认框从 0.96 缩入（V-36）。 */
    public static final float DIALOG_FROM_SCALE = 0.96f;

    /** 临界阻尼：不回弹、不过冲（V-34）。Kotlin 侧的 spring 只准取这个值。 */
    public static final float DAMPING_CRITICAL = 1f;

    /** done < total 时环弧的钳位（#282 / U-45）。 */
    public static final float RING_INCOMPLETE_CAP = 0.99f;

    private static volatile float durationScale = 1f;

    private Motion() {
    }

    /** 只由 ComposeHost 调用；其余调用点一律视为「各自读系统设置」的回退。 */
    public static void setDurationScale(float scale) {
        durationScale = scale <= 0f ? 0f : scale;
    }

    public static float durationScale() {
        return durationScale;
    }

    /** V-37：系统动画倍率为 0 时全部动效退化成瞬时（Compose 侧走 SnapSpec）。 */
    public static boolean instant() {
        return durationScale == 0f;
    }

    /**
     * 标称时长按系统倍率折算。倍率 >1 时仍钳在 [MOTION_MAX_MS]，
     * 免得「无障碍里把动画调慢」反而突破「单段 ≤200 ms」的预算。
     */
    public static int duration(int nominalMs) {
        if (nominalMs <= 0 || instant()) {
            return 0;
        }
        long scaled = Math.round(nominalMs * (double) durationScale);
        return (int) Math.min(Math.max(scaled, 1L), (long) MOTION_MAX_MS);
    }

    /** MotionSpecs 产 spec 前的守卫：标称时长必须为正且在上限内。 */
    public static boolean withinBudget(int nominalMs) {
        return nominalMs > 0 && nominalMs <= MOTION_MAX_MS;
    }

    /**
     * 进度环插值目标：done == total 才允许 1f，未完成一律钳在 0.99f（#282 / U-45）。
     * 插值本身也不得越过目标 —— 因此环走无过冲的 tween，不走 spring。
     */
    public static float ringTarget(float fraction) {
        if (fraction >= 1f) {
            return 1f;
        }
        if (fraction <= 0f) {
            return 0f;
        }
        return Math.min(fraction, RING_INCOMPLETE_CAP);
    }
}
