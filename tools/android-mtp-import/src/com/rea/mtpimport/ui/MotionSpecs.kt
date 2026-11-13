package com.rea.mtpimport.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import com.rea.mtpimport.Motion

/**
 * `V-36` / `V-37` 的**唯一 spec 出口**：全应用所有 `tween` / `spring` / `snap` 只在这里出现。
 *
 * 数值全在纯 JDK 的 [Motion] 里（§3.6：单一事实源，页面不得各写一份常量）；
 * 减少动效时全部返回 [SnapSpec]，终态不变、只是没有过程 —— 这正是 `V-37` 要求的
 * 「时间轴降级」，不是一个 if 套在页面上把动画组件整个绕过。
 *
 * 自检是纯 JDK，起不了 Compose，所以「页面有没有偷偷裸写 tween(...)」由
 * `MotionCases` 的源码护栏兜住：全 src 只有本文件能出现这些工厂调用。
 */
internal object MotionSpecs {

    /** 状态类：三屏交叉淡入、确认框、进度环、选中态、`photo` 覆盖层。 */
    fun <T> state(): FiniteAnimationSpec<T> = state(Motion.STATE_MS)

    /** 大面积位移类：抽屉滑入滑出、遮罩淡入淡出。 */
    fun <T> overlay(): FiniteAnimationSpec<T> = state(Motion.OVERLAY_MS)

    private fun <T> state(nominalMs: Int): FiniteAnimationSpec<T> {
        require(Motion.withinBudget(nominalMs)) { "动效超过 V-36 的单段上限：$nominalMs ms" }
        return if (Motion.instant()) {
            SnapSpec()
        } else {
            TweenSpec(durationMillis = Motion.duration(nominalMs))
        }
    }

    /**
     * 按压态用**临界阻尼**弹簧（`V-34`）：按下当帧就有位移、不回弹、不过冲。
     * 不用 `tween` 是因为弹簧在「按下 60 ms 就松手」这种极短按压里也能从当前值平滑折返，
     * 而 `tween` 会先把自己那一轮走完再掉头 —— 那正是「先播完再响应」，违反 `V-37`。
     */
    fun press(): AnimationSpec<Float> = if (Motion.instant()) {
        SnapSpec()
    } else {
        SpringSpec(
            dampingRatio = Motion.DAMPING_CRITICAL,
            stiffness = Spring.StiffnessMedium,
            // 弹簧没有标称时长；给它一个可见性阈值，避免终值附近无限细算。
            visibilityThreshold = 0.001f
        )
    }
}
