package com.rea.mtpimport;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * 编排层：把「清单 → 逐文件传输 → 记账」串起来，管线程、暂停、取消。
 *
 * 真正搬字节那一步委托给 {@link ResumableTransfer}；PA（issue #50）注入的是
 * {@link MtpTransfer}（`importFile` 整文件传输 + 字节数对账）。
 *
 * PB（issue #50）把暂停 / 取消 / 恢复的口径钉死在这里：
 *
 * - **暂停落在文件边界**：正在传的那个文件会传完并落盘，然后停在下一个文件之前；
 *   已传完的文件立刻进 {@link Journal}，下次按 `relPath + size` 跳过。暂停本身不产生 `.part`。
 * - **取消会尝试中断当前 importFile**：`cancel()` 把 {@link CancelToken} 置位，传输层在
 *   调用者线程关闭正在写的 fd。本次机型实测（2026-11-06，ILCE-7C 2.00 / Android 15 / USB MTP）
 *   约 0.6 s 返回；**能否打断原生调用没有跨机型的保证**，打不断时由 {@link #isCancelStuck()}
 *   给界面出路。返回 {@link ResumableTransfer.Kind#CANCELLED} 的文件不记完成、不计失败，
 *   本次半成品由传输层删掉（失败残留仍归 #52）。
 * - **恢复只恢复本轮**：`resume()` 只清暂停标志，让活着的 worker 从下一个文件继续；
 *   `resumeFrom > 0` 仍被传输层拒绝，不做假续传。
 */
public final class ImportService {

    /**
     * 「取消卡住」的判定宽限期（3 秒）。
     *
     * 正常取消时 worker 在毫秒级退出；超过这个宽限还在跑，就按「取消没能打断原生调用」处理。
     * 取值与仓库里扫描重试的 3 秒口径一致，避免另造一个魔法数字。
     */
    static final long CANCEL_STUCK_GRACE_NANOS = 3_000_000_000L;

    private final Log log;
    private final ResumableTransfer transfer;
    private final File outDir;

    private volatile boolean paused;
    private volatile boolean cancelled;
    private volatile Thread worker;

    /** {@link #cancel()} 被调用的时刻；0 表示本轮还没请求过取消。 */
    private volatile long cancelRequestedAtNanos;

    /**
     * 本轮是否已被调用方放弃（三审 B-3）。
     *
     * 放弃发生在「取消打不断原生调用、worker 迟迟不退」时：调用方已经不再把这轮的进度当数，
     * 而这一轮迟到的 `finally` 仍会整文件覆写 `import-journal.txt`。若此时新一轮已经在跑，
     * 旧轮会把新轮刚记下的记录冲掉（下次重传，Journal 与磁盘自相矛盾）。置位后跳过保存。
     */
    private volatile boolean abandoned;

    /**
     * 本轮取消令牌。每次 {@link #start} 重建，取消时立即执行传输层注册的中断动作
     * （见 {@link CancelToken}），不再等当前文件传完。
     */
    private volatile CancelToken cancelToken = new CancelToken();

    public ImportService(Log log, ResumableTransfer transfer, File outDir) {
        this.log = log;
        this.transfer = transfer;
        this.outDir = outDir;
    }

    public boolean isRunning() {
        Thread t = worker;
        return t != null && t.isAlive();
    }

    /** 本轮是否停在文件边界等 `Resume`（`Pause & exit` 后从照片墙再点 Import 会用）。 */
    public boolean isPaused() {
        return paused && isRunning();
    }

    /**
     * 本轮已请求取消，但 worker 在宽限期内仍未退出 —— 视为「取消卡住」。
     *
     * `MtpTransfer` 用关闭 fd 当作取消手段：本次机型实测约 0.6 s 返回；能否打断阻塞在原生
     * `importFile` 里的调用**没有跨机型保证**（见 `MtpTransfer` 类注释）。打不断时 worker 会
     * 一直活着、`isRunning()` 恒真；任何只看
     * `isRunning()` 的闸门都会把界面锁死在「点 `Import` 没反应，只能杀进程」——`CATALOG_READY`
     * 按 `UI-SPEC.md` §1.3 没有 Cancel 入口。这个口径给调用方一个给 UI 出路的依据。
     */
    public boolean isCancelStuck() {
        return isCancelStuckAt(System.nanoTime());
    }

    /** 用指定时刻判定，便于 JVM 自检（不依赖真实等待）。 */
    boolean isCancelStuckAt(long nowNanos) {
        return cancelStuck(cancelled, isRunning(), cancelRequestedAtNanos, nowNanos,
                CANCEL_STUCK_GRACE_NANOS);
    }

    /** 纯判定：已请求取消 + worker 还活着 + 距取消已超过宽限期。 */
    static boolean cancelStuck(boolean cancelled, boolean running, long cancelAtNanos,
                               long nowNanos, long graceNanos) {
        if (!cancelled || !running || cancelAtNanos == 0L) return false;
        return nowNanos - cancelAtNanos >= graceNanos;
    }

    public void pause() {
        paused = true;
        log.line("已请求暂停（当前文件传完后停在下一个文件之前）");
    }

    public void resume() {
        paused = false;
        log.line("继续");
    }

    public void cancel() {
        cancelled = true;
        paused = false;
        // 记录取消时刻：worker 迟迟不退出时，Main 用它判定「取消卡住」并给 UI 出路。
        cancelRequestedAtNanos = System.nanoTime();
        // 先置位再触发令牌：传输层的中断动作（关 pfd）在调用者线程上同步执行。
        // 本次机型实测 worker 约 0.6 s 返回（不会等整个文件传完）；打不断的情况没有保证，
        // 由 isCancelStuck() + Main 的放弃/探活给界面出路。
        cancelToken.cancel();
        log.line("已请求取消（正在传的文件会被中断）");
    }

    /**
     * 标记本轮已被调用方放弃：worker 之后退出时不再覆写续传日志（三审 B-3）。
     *
     * 只由调用方在「已放弃该轮」时调用。**代价**：{@link Journal} 只在 `run()` 开始时加载一次，
     * `markDone` 只改内存，`saveJournal()` 只在 `finally` 整文件覆写；放弃后跳过保存意味着
     * **本轮所有已完成文件的记录都会丢**，下次全部重传（不会产生假完成，但比「记录已在旧日志里」
     * 的代价大）。
     */
    public void abandon() {
        abandoned = true;
    }

    public void start(final ImportPlan plan, final ImportListener listener) {
        if (isRunning()) {
            listener.onError("已有一轮在跑，先等它结束或取消", null);
            return;
        }
        if (plan == null || plan.isEmpty()) {
            listener.onError("没有勾选任何文件", null);
            return;
        }
        paused = false;
        cancelled = false;
        abandoned = false;
        cancelRequestedAtNanos = 0L;
        cancelToken = new CancelToken();
        worker = new Thread(() -> run(plan, listener), "import-worker");
        worker.start();
    }

    private void run(ImportPlan plan, ImportListener l) {
        // 本轮固定使用 start() 建的令牌：cancel() 可能在中途重建字段，worker 不能换令牌。
        final CancelToken token = cancelToken;
        Journal journal = loadJournal();
        TransferProgress progress = new TransferProgress(plan.totalBytes(), plan.count());
        progress.start(System.nanoTime());
        l.onPlanReady(plan);

        int ok = 0;
        int skipped = 0;
        int failed = 0;
        String stop = "";

        try {
            List<ImportPlan.Item> items = plan.items();
            for (ImportPlan.Item item : items) {
                if (cancelled) {
                    stop = "已取消";
                    break;
                }
                while (paused && !cancelled) {
                    Thread.sleep(200);
                }
                if (cancelled) {
                    stop = "已取消";
                    break;
                }

                if (journal.isComplete(item.relPath, item.size)) {
                    skipped++;
                    progress.onBytes(item.size);
                    progress.onFileDone();
                    l.onFileFinished(item, ResumableTransfer.Kind.SKIPPED, "Skipped existing");
                    l.onProgress(progress, item);
                    continue;
                }

                String part = journal.partPath(outDir.getAbsolutePath(), item.handle);
                ResumableTransfer.Result r = transfer.transfer(
                        item, part, journal.doneBytes(item.relPath, item.size), token);

                switch (r.kind) {
                    case OK:
                        ok++;
                        journal.markDone(item.handle, item.size, item.size, item.relPath);
                        progress.onBytes(item.size);
                        progress.onFileDone();
                        l.onFileFinished(item, r.kind, r.note);
                        l.onProgress(progress, item);
                        break;
                    case SKIPPED:
                        skipped++;
                        progress.onBytes(item.size);
                        progress.onFileDone();
                        l.onFileFinished(item, r.kind, r.note);
                        l.onProgress(progress, item);
                        break;
                    case CANCELLED:
                        // 取消的文件既不记完成也不计失败；半成品已由传输层清掉。
                        // 不推进进度，避免给界面一个「这个文件传完了」的假信号。
                        stop = "已取消";
                        l.onFileFinished(item, r.kind, r.note);
                        break;
                    case FAILED:
                    default:
                        failed++;
                        l.onFileFinished(item, r.kind, r.note);
                        l.onProgress(progress, item);
                        break;
                }
                if (r.kind == ResumableTransfer.Kind.CANCELLED) break;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            stop = "被中断";
        } catch (Throwable t) {
            l.onError("编排层异常", t);
            stop = "异常中断：" + t;
        } finally {
            // 三审 B-3：被放弃的轮次不写日志，避免迟到的整文件覆写冲掉新一轮的记录。
            if (abandoned) {
                log.line("本轮已被放弃：跳过续传日志保存（不覆盖磁盘上的现有记录）");
            } else {
                saveJournal(journal);
            }
            worker = null;
        }

        String summary = String.format(
                "完成 %d / 跳过 %d / 失败 %d（共 %d 个）%s",
                ok, skipped, failed, plan.count(),
                stop.isEmpty() ? "" : " · " + stop);
        log.line("本轮结束：" + summary);
        l.onFinished(cancelled, ok, skipped, failed, summary);
    }

    private File journalFile() {
        return new File(outDir, "import-journal.txt");
    }

    private Journal loadJournal() {
        Journal j = new Journal();
        File f = journalFile();
        if (!f.isFile()) return j;
        try (FileInputStream in = new FileInputStream(f)) {
            j.load(in);
            log.line("续传日志已载入：" + j.size() + " 条记录（" + f.getAbsolutePath() + "）");
        } catch (IOException e) {
            log.line("续传日志读取失败，按空日志继续：" + e);
        }
        return j;
    }

    private void saveJournal(Journal j) {
        File f = journalFile();
        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            log.line("建目录失败，续传日志未保存：" + parent);
            return;
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            j.save(out);
            log.line("续传日志已保存：" + f.getAbsolutePath());
        } catch (IOException e) {
            log.line("续传日志保存失败：" + e);
        }
    }
}
