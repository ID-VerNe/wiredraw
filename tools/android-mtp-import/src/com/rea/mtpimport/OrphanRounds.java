package com.rea.mtpimport;

/**
 * 被放弃的导入轮次（G9a，从 `Main.orphanService` 抽出，纯 JDK）。
 *
 * `abandonStuckService()` 只摘掉当前轮引用；旧 worker 可能仍阻塞在原生 `importFile` 上、
 * 占着同一个 `MtpDevice`。此时若扫描线程去 `session.close()` 并重建会话，正好撞上那条
 * 原生调用（与「不在 UI 线程 close」同一理由）。拔线后旧调用会失败返回、worker 退出，
 * {@link #stillRunning(Sink)} 自动放行；未拔线前一律保持 `ERROR`。
 */
public final class OrphanRounds {

    /** 存活判定需要投递的界面/日志动作。 */
    public interface Sink {
        void logLine(String line);

        void showError(String message);
    }

    private volatile ImportService orphan;

    /** 登记一个被放弃的轮次；`abandon()` 必须先于登记，旧轮 finally 才不会覆写新一轮的日志。 */
    public void abandon(ImportService dead) {
        if (dead == null) return;
        dead.abandon();
        orphan = dead;
    }

    /**
     * 登记一个已取消、但 worker 可能还没退出的轮次；**不**调 `abandon()`。
     *
     * 用在暂停轮次被换掉 / 恢复前探活失败两条路径：那两轮是正常 `cancel()`，收尾时仍应保存
     * Journal，只是不能再动界面（`service` 已置空，`ImportRoundListener` 的 `current` 判据会丢弃）。
     */
    public void adopt(ImportService round) {
        orphan = round;
    }

    public ImportService current() {
        return orphan;
    }

    public void clear() {
        orphan = null;
    }

    /** @return true = 旧 worker 仍活着，调用方不得重建会话 / 开新轮。 */
    public boolean stillRunning(Sink sink) {
        ImportService o = orphan;
        if (o == null) return false;
        if (!o.isRunning()) {
            orphan = null;
            return false;
        }
        sink.logLine("上一轮传输线程仍未退出：暂不重建会话（请拔插数据线后重试）");
        sink.showError(StuckError.SESSION_LOST);
        return true;
    }
}
