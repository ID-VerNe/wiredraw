package com.rea.mtpimport;

/**
 * 顶栏形态的纯 JDK 判定（唯一归属，N-1）。
 *
 * 之前「哪些状态有顶栏」「哪个状态出计数胶囊」只写在 Compose 里，`SelfTest` 只能间接
 * 断言其中一部分；把计数胶囊条件改坏（例如去掉 `CATALOG_READY` 限定）没有任何用例变红。
 * 现在 Compose 页面与 {@link SelfTest} 共用本类。
 */
public final class TopBarSpec {

    private TopBarSpec() {
    }

    /**
     * 顶栏在 10 态 + `photo` + `transfer` 全部常驻（issue #44 决策 2），
     * 内容按 UI-SPEC §2.2 的逐态分化表。
     */
    public static boolean hasBar(UiState.State s) {
        return true;
    }

    /**
     * 顶栏计数胶囊**只在** `CATALOG_READY` 出现，且计数非零（UI-SPEC `V-27`）。
     *
     * 它与主 CTA `Import all N` 同分母，不承担「唯一分母」职责。
     */
    public static boolean showsCountPill(UiState.State s, int photoCount) {
        return s == UiState.State.CATALOG_READY && photoCount > 0;
    }

    /**
     * 顶栏连接指示是否在该状态渲染（#44 复审 / P1-1）。
     *
     * 2026-11-08 起传输态（`IMPORTING` / `PAUSED`）不再「让位给环」：环仍是内容区
     * 唯一焦点，顶栏右端另给一个常驻连接指示。`photo` 视图不在此列（只留 `Back` / `Location`）。
     */
    public static boolean showsConnectionIndicator(UiState.State s) {
        return s != null;
    }

    /**
     * 顶栏连接指示的文案（#44 复审 / P1-1）：断线取 `Disconnected`，否则取设备短名。
     *
     * 断线由 {@link UiTransition} 的 `USB_DETACHED` 事件置位，不是轮询探测。
     */
    public static String connectionLabel(boolean lost, String deviceName) {
        return lost ? "Disconnected" : deviceName;
    }
}
