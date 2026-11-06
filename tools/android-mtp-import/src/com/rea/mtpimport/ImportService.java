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
 * - **取消是真的中断**：`cancel()` 把 {@link CancelToken} 置位，传输层随即关闭正在写的 fd，
 *   阻塞中的 `importFile` 会提前返回；返回 {@link ResumableTransfer.Kind#CANCELLED} 的文件
 *   不记完成、不计失败，本次半成品由传输层删掉（失败残留仍归 #52）。
 * - **恢复只恢复本轮**：`resume()` 只清暂停标志，让活着的 worker 从下一个文件继续；
 *   `resumeFrom > 0` 仍被传输层拒绝，不做假续传。
 */
public final class ImportService {

    private final Log log;
    private final ResumableTransfer transfer;
    private final File outDir;

    private volatile boolean paused;
    private volatile boolean cancelled;
    private volatile Thread worker;

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
        // 先置位再触发令牌：传输层的中断动作（关 pfd）在调用者线程上同步执行，
        // 阻塞在 importFile 里的 worker 随后返回，不会等整个文件传完。
        cancelToken.cancel();
        log.line("已请求取消（正在传的文件会被中断）");
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
            saveJournal(journal);
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
