package com.rea.mtpimport.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import com.rea.mtpimport.Motion

/**
 * `V-36` / `V-37` 的**唯一 spec 出口**：全应用所有 `TweenSpec` / `SnapSpec` 只在这里出现。
 *
 * 数值全在纯 JDK 的 [Motion] 里（§3.6：单一事实源，页面不得各写一份常量）；
 * 减少动效时全部返回 [SnapSpec]，终态不变、只是没有过程 —— 这正是 `V-37` 要求的
 * 「时间轴降级」，不是一个 if 套在页面上把动画组件整个绕过。
 *
 * 自检是纯 JDK，起不了 Compose，所以「页面有没有偷偷裸写动画工厂」由
 * `MotionCases` 的源码护栏兜住：全 src 只有本文件能出现这些工厂调用。
 */
internal object MotionSpecs {

    /** 状态类：三屏交叉淡入、确认框、进度环、选中态、`photo` 覆盖层。 */
    fun <T> state(): FiniteAnimationSpec<T> = state(Motion.STATE_MS)

    /** 大面积位移类：抽屉滑入滑出、遮罩淡入淡出。 */
    fun <T> overlay(): FiniteAnimationSpec<T> = state(Motion.OVERLAY_MS)

    /** 松手回落用按压档的短时长（`V-34`）。 */
    fun <T> press(): FiniteAnimationSpec<T> = state(Motion.PRESS_MS)

    /**
     * 按下当帧到位（`V-34`）：按压缩放/底色不走弹簧，否则「按住几百毫秒才看见反馈」。
     * 真机实测：弹簧方案在 300 ms 时底色才刚起步，违反 `pointerdown` 当帧响应的硬要求。
     */
    fun <T> immediate(): FiniteAnimationSpec<T> = SnapSpec()

    private fun <T> state(nominalMs: Int): FiniteAnimationSpec<T> {
        require(Motion.withinBudget(nominalMs)) { "动效超过 V-36 的单段上限：$nominalMs ms" }
        return if (Motion.instant()) {
            SnapSpec()
        } else {
            TweenSpec(durationMillis = Motion.duration(nominalMs))
        }
    }
}
