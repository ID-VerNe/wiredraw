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
 * {@link MtpTransfer}（`importFile` 整文件传输 + 字节数对账）。暂停/取消仍只停在
 * 文件边界，单文件内中断留给 PB。
 */
public final class ImportService {

    private final Log log;
    private final ResumableTransfer transfer;
    private final File outDir;

    private volatile boolean paused;
    private volatile boolean cancelled;
    private volatile Thread worker;

    public ImportService(Log log, ResumableTransfer transfer, File outDir) {
        this.log = log;
        this.transfer = transfer;
        this.outDir = outDir;
    }

    public boolean isRunning() {
        Thread t = worker;
        return t != null && t.isAlive();
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
        log.line("已请求取消");
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
        worker = new Thread(() -> run(plan, listener), "import-worker");
        worker.start();
    }

    private void run(ImportPlan plan, ImportListener l) {
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
                        item, part, journal.doneBytes(item.relPath, item.size));

                switch (r.kind) {
                    case OK:
                        ok++;
                        journal.markDone(item.handle, item.size, item.size, item.relPath);
                        progress.onBytes(item.size);
                        progress.onFileDone();
                        break;
                    case SKIPPED:
                        skipped++;
                        progress.onBytes(item.size);
                        progress.onFileDone();
                        break;
                    case FAILED:
                        failed++;
                        break;
                    default:
                        failed++;
                        break;
                }
                l.onFileFinished(item, r.kind, r.note);
                l.onProgress(progress, item);
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
