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
}
