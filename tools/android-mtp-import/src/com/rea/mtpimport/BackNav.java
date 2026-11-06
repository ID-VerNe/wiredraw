package com.rea.mtpimport;

/**
 * 系统返回键的路由决策（issue #48）。
 *
 * 纯 JDK，不 import android.*：可以直接在 JVM 自检里钉住回归。顺序沿用
 * `Main.onBack` 的历史口径：浮层优先 → 照片页 → 传输/终态 → 启动态。
 *
 * 关键口径：除照片墙本身与启动态外，任何界面都只回退一层到照片墙，
 * 不再落到 `Activity.finish()`；`IMPORTING` 下的 `Pause & exit` 是「退出传输视图」，
 * 不是退出应用。
 */
public final class BackNav {

    /** 系统返回键要做的一层动作。 */
    public enum Action {
        /** 只关闭 `Pause & exit` 确认框。 */
        CLOSE_EXIT_CONFIRM,
        /** 只关闭过滤抽屉。 */
        CLOSE_FILTER,
        /** 只关闭详情抽屉。 */
        CLOSE_DETAILS,
        /** 只关闭照片详情页。 */
        CLOSE_PHOTO,
        /** 传输中：弹 `Pause & exit` / `Resume` 确认框。 */
        OPEN_EXIT_CONFIRM,
        /** 回退一层到照片墙（没有照片墙时落到 `COLD`）。 */
        BACK_TO_CATALOG,
        /** 退出应用。 */
        EXIT
    }

    private BackNav() {
    }

    /**
     * 决定系统返回键落到哪一层。
     *
     * @param state            当前界面态
     * @param exitConfirmOpen  `Pause & exit` 确认框是否开着
     * @param filterOpen       过滤抽屉是否开着
     * @param detailsOpen      详情抽屉是否开着
     * @param photoOpen        照片详情页是否开着
     */
    public static Action decide(UiState.State state,
                                boolean exitConfirmOpen,
                                boolean filterOpen,
                                boolean detailsOpen,
                                boolean photoOpen) {
        // 浮层优先：模态开着时返回键只关模态，不许顺带退出应用或关掉底下的页面。
        if (exitConfirmOpen) return Action.CLOSE_EXIT_CONFIRM;
        if (filterOpen) return Action.CLOSE_FILTER;
        if (detailsOpen) return Action.CLOSE_DETAILS;
        if (photoOpen) return Action.CLOSE_PHOTO;

        // 传输中才需要确认（§1.3）；`Pause & exit` 的 exit 指退出传输视图（issue #48）。
        if (state == UiState.State.IMPORTING) return Action.OPEN_EXIT_CONFIRM;

        // 非主页终态一律回退一层到照片墙：暂停/完成/出错/未实现都不许把用户踢出应用。
        if (state == UiState.State.PAUSED
                || state == UiState.State.DONE
                || state == UiState.State.ERROR
                || state == UiState.State.UNAVAILABLE) {
            return Action.BACK_TO_CATALOG;
        }

        // 照片墙是主页、其余启动态没有照片墙可回，按原口径退出应用。
        return Action.EXIT;
    }
}
