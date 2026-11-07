package com.rea.mtpimport;

/**
 * 一轮导入的入口判定（G9a，纯 JDK）。
 *
 * issue #127：本轮跳过数必须在**任何早退之前**清零，否则上一轮的 `Skipped N` 会留在
 * Details 抽屉里冒充本轮结果。把「先清零、再判分支」收进一个纯 JDK 方法，
 * 自检就能直接驱动它断言顺序（issue #212 拆 `Main` 时把源码文本断言换回行为断言）。
 */
public final class ImportEntry {

    /** 入口处要走哪条路。 */
    public enum Stage {
        /** 假数据模式：不碰 USB，直接进 demo 扫描/传输。 */
        DEMO,
        /** 还没有照片墙：只提示先扫描，本轮不开始。 */
        NEEDS_SCAN,
        /** 可以继续往下编排。 */
        READY
    }

    /** 入口需要做的界面/日志动作。 */
    public interface Sink {
        /** 清零上一轮的跳过数（`model.showSkipped(0)`）。 */
        void resetSkipped();

        void logLine(String line);
    }

    private ImportEntry() {
    }

    /**
     * 入口判定：**永远先 {@link Sink#resetSkipped()}**，再判 demo / 清单。
     *
     * @param demoActive  是否处于 debug 假数据模式
     * @param catalogReady 是否已有照片墙（`catalog != null && selection != null`）
     */
    public static Stage begin(Sink sink, boolean demoActive, boolean catalogReady) {
        sink.resetSkipped();
        if (demoActive) return Stage.DEMO;
        if (!catalogReady) {
            sink.logLine("先扫描");
            return Stage.NEEDS_SCAN;
        }
        return Stage.READY;
    }
}
