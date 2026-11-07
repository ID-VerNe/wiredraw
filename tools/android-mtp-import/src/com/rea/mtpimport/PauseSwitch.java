package com.rea.mtpimport;

/**
 * 暂停轮次遇到「清单已变化」时的取舍（issue #124）。
 *
 * 空间不足时必须保留旧暂停轮：否则用户改选到更大的集合、空间却不够时，
 * 旧轮的剩余待传列表会被白丢（Journal 只能保住已落盘的文件）。
 * 只有空间预检通过才允许取消旧轮次并按新清单重开。
 *
 * 抽成纯 JDK 的判定函数，自检可以直接断言这两种输入，
 * 不必再匹配 `doStart()` 里的语句先后（issue #212）。
 */
public final class PauseSwitch {

    public enum Action { KEEP_PAUSED, CANCEL_AND_RESTART }

    private PauseSwitch() {
    }

    /** 预检通过才取消旧轮；不足则保留，等用户补足空间后再点 Import。 */
    public static Action of(boolean spaceEnough) {
        return spaceEnough ? Action.CANCEL_AND_RESTART : Action.KEEP_PAUSED;
    }
}
