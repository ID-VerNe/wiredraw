package com.rea.mtpimport;

/**
 * 导入轮次向界面投递的唯一出口（G9a，纯 JDK）。
 *
 * 实现方负责线程切换：生产是 `runOnUiThread`，自检是直调。投递一律走 {@link #post(Runnable)}，
 * 由实现方保证「同一个 UI 任务里做完一组相邻动作」。
 *
 * 为什么把「轮次结束」做成一个方法而不是四个分散调用：skip 数必须先写进 Details 抽屉，
 * 再按 {@link FinishOutcome} 落视图；拆成多次投递会让 DONE / ERROR 抢在 skip 之前。
 */
public interface ImportUi {

    /** 切到 UI 线程执行；自检实现可以直接 `r.run()`。 */
    void post(Runnable r);

    void updateProgress(int done, int total);

    void addFailedLine(String line);

    /** 导入被中断（`onError`）：落 ERROR + Connect camera。 */
    void showImportError();

    /**
     * 一轮结束（实现方必须已在本方法内处于 UI 线程）。
     *
     * @param bytesDone 本轮已确认落盘的字节数（`lastProgress` 的最新值；没有则 0）
     */
    void finishRound(boolean cancelled, int ok, int skipped, int failed, long bytesDone);
}
