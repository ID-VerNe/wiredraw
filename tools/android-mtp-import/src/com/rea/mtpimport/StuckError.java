package com.rea.mtpimport;

/**
 * 卡死轮次的屏幕 ERROR 文案（issue #116）。
 *
 * 从 `Main` 抽成纯 JDK 类：自检要断言「两种成因的文案可区分」，
 * 但 `Main` 依赖 android.*，进不了自检 classpath。抽出来以后，
 * 自检直接调 {@link #message(Kind)}，不必再匹配 `doStart()` 的源码文本（issue #212）。
 */
public final class StuckError {

    /** 会话可能已失步时统一用这句（拔插数据线可恢复）。 */
    public static final String SESSION_LOST = "相机连接可能已失步，请拔插数据线";

    /** 挂起：什么都没做就长时间无进展。 */
    public static final String STALLED = "相机长时间无响应，请拔插数据线";

    /** 放弃卡死轮次的成因；`NONE` = 无需放弃（调用方不会取文案）。 */
    public enum Kind { NONE, CANCEL_STUCK, STALLED }

    private StuckError() {
    }

    /** 屏幕 ERROR 文案：挂起说「长时间无响应」，取消卡住说「可能已失步」。 */
    public static String message(Kind kind) {
        return kind == Kind.STALLED ? STALLED : SESSION_LOST;
    }
}
