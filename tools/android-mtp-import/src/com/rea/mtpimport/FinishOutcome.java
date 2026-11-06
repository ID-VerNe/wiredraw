package com.rea.mtpimport;

/**
 * 整轮导入结束后的界面去向（issue #64）。
 *
 * 纯 JDK，不 import android.*：判定顺序直接在 JVM 自检里钉住。
 *
 * 关键口径：**整轮全失败必须优先于「传输视图已关闭」**。视图关闭只表示「别抢回传输屏」，
 * 不该成为吞掉失败提示的条件；否则用户停在照片墙，看不到 ERROR，失败原因被静默丢弃。
 */
public final class FinishOutcome {

    /** `onFinished` 要做的一件事。 */
    public enum Action {
        /** 已取消：只关视图，不抢回界面（`onCancel` 已关过一次）。 */
        LEAVE_TRANSFER_VIEW,
        /** 整轮全失败：一律落 ERROR，与视图是否已关闭无关。 */
        SHOW_ERROR,
        /** 视图已关闭且本轮不是全失败：保持当前视图，不跳 DONE。 */
        STAY_CURRENT_VIEW,
        /** 有成功或跳过：落 DONE。 */
        SHOW_DONE,
        /** 其余（没有文件、也没有失败）：回照片墙。 */
        BACK_TO_CATALOG
    }

    private FinishOutcome() {
    }

    /**
     * @param cancelled 本轮是否被取消
     * @param viewClosed 传输视图是否已被 `Pause & exit` / 顶部返回关闭
     * @param ok 成功文件数
     * @param skipped 已存在而跳过的文件数
     * @param failed 失败文件数
     */
    public static Action of(boolean cancelled, boolean viewClosed,
                            int ok, int skipped, int failed) {
        if (cancelled) return Action.LEAVE_TRANSFER_VIEW;
        // 全失败优先：失败提示不能被「视图已关闭」吞掉（issue #64 的根因）。
        if (ok == 0 && skipped == 0 && failed > 0) return Action.SHOW_ERROR;
        if (viewClosed) return Action.STAY_CURRENT_VIEW;
        if (ok + skipped > 0) return Action.SHOW_DONE;
        return Action.BACK_TO_CATALOG;
    }
}
