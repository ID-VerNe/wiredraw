package com.rea.mtpimport;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * 一轮导入的回调转发（G9a，从 `Main.doStart` 的匿名 `ImportListener` 抽出，纯 JDK）。
 *
 * 线程约定沿用 {@link ImportListener}：所有回调都在 worker 线程触发；界面投递统一经
 * {@link ImportUi}，由实现方切到 UI 线程。`current` 用来丢弃被放弃/已换轮次的迟到回调
 * （等价于原来的 `service == round` 判定）。
 */
public final class ImportRoundListener implements ImportListener {

    private final Log log;
    private final ImportUi ui;
    /** 该轮是否仍是当前轮次；换来轮/放弃后为 false。 */
    private final BooleanSupplier current;

    /**
     * 进度回调合并闸：worker 每个文件投一次更新，主线程忙时不希望几百个 Runnable
     * 在队列里排队。只在没有待执行更新时投递，执行时直接取 {@link #lastProgress} 的最新值。
     */
    private final AtomicBoolean progressUiPending = new AtomicBoolean(false);

    /** 最近一次进度；恢复暂停轮次重进 IMPORTING 时也读它（没有就返回 null）。 */
    private volatile TransferProgress lastProgress;

    public ImportRoundListener(Log log, ImportUi ui, BooleanSupplier current) {
        this.log = log;
        this.ui = ui;
        this.current = current;
    }

    /** 最近一次进度；没有就返回 null。 */
    public TransferProgress lastProgress() {
        return lastProgress;
    }

    @Override
    public void onPlanReady(ImportPlan p) {
        log.line("计划：" + p.count() + " 个文件 / " + p.totalBytes() + " 字节");
    }

    @Override
    public void onProgress(TransferProgress progress, ImportPlan.Item item) {
        if (!current.getAsBoolean()) return;   // 该轮已被放弃/换轮：迟到的进度不得改写界面
        lastProgress = progress;
        // 已有一个待执行更新时直接丢弃本次投递，待执行那个 Runnable 会读最新值。
        if (!progressUiPending.compareAndSet(false, true)) return;
        ui.post(() -> {
            progressUiPending.set(false);
            TransferProgress p = lastProgress;
            if (p == null) return;
            // 只送文件计数：字节 / 速率不上屏（issue #70；#177 证 offset 不可信）。
            ui.updateProgress(p.filesDone(), p.totalFiles());
        });
    }

    @Override
    public void onFileFinished(ImportPlan.Item item, ResumableTransfer.Kind kind, String note) {
        // issue #177：内部枚举名不得直出到用户可见的 Details 日志（UI-SPEC §0.10）。
        log.line("[" + kindLabel(kind) + "] " + item.relPath
                + (note.isEmpty() ? "" : " · " + note));
        // PD / issue #50：逐文件失败必须进 UI，落到 Details 抽屉的固定失败列表。
        // 被放弃/换轮的迟到回调不改当前轮次的失败列表。
        if (kind == ResumableTransfer.Kind.FAILED && current.getAsBoolean()) {
            String fail = "Failed: " + item.relPath + (note.isEmpty() ? "" : " · " + note);
            ui.post(() -> ui.addFailedLine(fail));
        }
    }

    @Override
    public void onFinished(boolean cancelled, int ok, int skipped, int failed, String summary) {
        if (!current.getAsBoolean()) return;   // 被放弃的卡死轮次收尾时不得动界面
        // issue #127：0 跳过时不打日志（与抽屉口径一致，不出现「跳过 0」）。
        if (skipped > 0) log.line(UiText.skippedLine(skipped));
        ui.post(() -> {
            TransferProgress p = lastProgress;
            ui.finishRound(cancelled, ok, skipped, failed, p == null ? 0 : p.bytesDone());
        });
    }

    @Override
    public void onError(String message, Throwable t) {
        if (!current.getAsBoolean()) return;
        log.line("错误：" + message + (t == null ? "" : " · " + t));
        ui.post(ui::showImportError);
    }

    /** 逐文件日志标签：用 UI-SPEC §0.10 的词，不把内部枚举名（OK/SKIPPED/...）写给用户看。 */
    public static String kindLabel(ResumableTransfer.Kind kind) {
        switch (kind) {
            case OK: return "Done";
            case SKIPPED: return "Skipped";
            case FAILED: return "Failed";
            case CANCELLED: return "Cancelled";
            default: return "";
        }
    }
}
