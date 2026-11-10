package com.rea.mtpimport;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.LongConsumer;

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
 * - **恢复只恢复本轮**：`resume()` 只清暂停标志，让活着的 worker 从下一个文件继续。
 * - **续传已停用（止血，issue #177）**：2026-11-07 真机判别（E2 MI 9 / Android 15 API 35 /
 *   ILCE-7C fw2.00 / USB MTP）得到精确语义：非零 `offset` 只有在恰好等于相机内部游标时才被
 *   采纳；对不上时忽略 offset、从对象头返回。「游标」是相机侧读流的位置（相机在会话内维护）。
 *   从 0 起逐块顺读永远对；跳读 / 续传一定错，而且续读不省带宽（要先从 0 读到该位置）。
 *   把遗留 `.part` 当可信前缀续写会得到「字节数对、内容错、却报 OK」的文件。因此本层
 *   `resumeFrom` 恒为 0，旧 `.part` 一律视为不可信：传输层先删除残留，再从 0 整段重写；
 *   成功后才 rename 并记完成。半成品名仍带 `size`（`<relPath 压平>.<size>.part`），
 *   但不再作为续传起点。
 * - **完成必须对到磁盘**：Journal 只是上一轮的记录，跳过前还要确认成品存在且长度吻合；
 *   成品缺失 / 尺寸不符时记一行日志并重新导入，不静默跳过。
 * - **失败重试（PD / issue #50）**：单文件首次 FAILED 后同步重试，上限
 *   {@link #MAX_FILE_ATTEMPTS}（首次 + 1 次）；每次尝试都从 0 重写（#177）。
 *   **不做整轮自动重跑**，用户再点 Import 时 Journal 跳过已完成。
 */
public final class ImportService {

    /**
     * 「取消卡住」的判定宽限期（3 秒）。
     *
     * 正常取消时 worker 在毫秒级退出；超过这个宽限还在跑，就按「取消没能打断原生调用」处理。
     * 取值与仓库里扫描重试的 3 秒口径一致，避免另造一个魔法数字。
     */
    static final long CANCEL_STUCK_GRACE_NANOS = 3_000_000_000L;

    /**
     * 「传输长时间无进展」的判定宽限期（60 秒，issue #63）。
     *
     * 与 {@link #CANCEL_STUCK_GRACE_NANOS} 解决的不是同一件事：那条只管「用户点了取消、
     * worker 却退不掉」；这条管「用户没点取消、worker 卡在原生 `importFile` 里」
     * （相机休眠 / USB 掉线），此时取消路径根本不会被调用。
     *
     * 取 60 秒的依据：本链路实测 USB MTP 21.43 MB/s（见主报告第 11 节），单张 24 MP RAW
     * 约 1.1 s；60 s 已是 50 倍余量，足以覆盖相机降速，同时用户点 `Import` 时能等到一次判定。
     * 挂起判定**只按文件边界**计时（见 README「无进展挂起（#63）」）；#70 的块级进度回调
     * 只用于界面数字，不参与挂起判定。
     */
    static final long NO_ACTIVITY_GRACE_NANOS = 60_000_000_000L;

    /**
     * 单个文件的最多尝试次数（首次 + 1 次同步重试，PD / issue #50）。
     *
     * 有上限，绝不无限循环：MTP 断线/失步不会靠重试自愈，重试一次足够覆盖偶发失败；
     * 仍失败就累计到本轮 `failed` 并保留 `.part`，由用户再点 Import（Journal 跳过已完成；
     * 续传已停用 #177，残留 `.part` 下次从 0 重写），**不做整轮自动重跑**。
     */
    static final int MAX_FILE_ATTEMPTS = 2;

    /**
     * 时钟注入缝（package-private，仅自检用）。
     *
     * 暂停/恢复的时刻必须能被用例精确构造，否则只能真等上百秒；生产路径默认
     * {@link System#nanoTime}，行为不变。与 {@link TransferProgress} 的「时钟由外部注入」
     * 同一思路（issue #58 一审 B-1）。
     */
    interface Clock {
        long nanoTime();
    }

    private final Log log;
    private final ResumableTransfer transfer;
    private final File outDir;
    private final Clock clock;

    /**
     * 成品存在性探针（issue #28 分节 PB）：成品进 MediaStore 后不再是 `outDir` 下的 File，
     * 由 Android 侧注入按 `relPath` 查相册条目尺寸的实现。null 时沿用私有 File 判定——
     * 默认行为完全不变，纯 JDK 自检里既有的构造都不注入。
     */
    private volatile SkipRule.ProductProbe productProbe;

    private volatile boolean paused;
    private volatile boolean cancelled;
    private volatile Thread worker;

    /** {@link #cancel()} 被调用的时刻；0 表示本轮还没请求过取消。 */
    private volatile long cancelRequestedAtNanos;

    /**
     * 最后一次「进入文件传输」的时刻（issue #63）；0 表示本轮还没进入过任何文件。
     *
     * 只按文件边界刷新：`transfer.transfer(...)` 调用前记一次，返回后不记——
     * 于是一次整文件 `importFile` 持续多久，都能被 {@link #isStalledAt(long)} 看出
     * （#177 续传停用后只剩整文件路径）。
     * #70 的块级进度回调存在，但**不参与**挂起判定，所以这里仍是文件边界语义
     * （见 README「无进展挂起（#63）」）。
     */
    private volatile long lastActivityNanos;

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

    /**
     * 本轮固化下来的清单（issue #57）：`start()` 时记录，供调用方在恢复前比较
     * 「当前勾选算出的计划」与暂停轮次是否仍是同一批文件。
     */
    private volatile ImportPlan plan;

    public ImportService(Log log, ResumableTransfer transfer, File outDir) {
        this(log, transfer, outDir, System::nanoTime);
    }

    /** 自检用构造器：注入时钟，避免为「暂停 100 秒」真等 100 秒。 */
    ImportService(Log log, ResumableTransfer transfer, File outDir, Clock clock) {
        this.log = log;
        this.transfer = transfer;
        this.outDir = outDir;
        this.clock = clock;
    }

    /**
     * 注入成品探针（issue #28 分节 PB）。只在整轮开始前调用：落点必须在开工前定死，
     * 传输中 `isRunning()` 为真时不许改选（由 Main 的改选回调把关）。
     */
    public void setProductProbe(SkipRule.ProductProbe probe) {
        this.productProbe = probe;
    }

    public boolean isRunning() {
        Thread t = worker;
        return t != null && t.isAlive();
    }

    /**
     * 等待本轮 worker 退出，最多 `millis` 毫秒（PD / issue #50）。
     *
     * 返回 true 表示 worker 已退出（或本来就没在跑），调用方可以安全地关闭 MTP 会话；
     * 返回 false 表示仍有 worker 阻塞在原生调用里，**不得**在调用线程 `session.close()`。
     * 只做一次有界等待，不循环、不无限等。
     */
    public boolean awaitWorker(long millis) {
        Thread t = worker;
        if (t == null) return true;
        return WorkerWait.finishJoin(t, Math.max(1L, millis));
    }

    /**
     * 有界 join 的收尾模板（issue #308）：中断复位 + 存活判定。
     *
     * 两个调用方的时间契约不同——{@link #awaitWorker(long)} 收**相对毫秒**，
     * `Main.awaitIdle` 收**绝对截止时刻**后自己换算成剩余毫秒（三个 worker 共用一个
     * deadline，加起来不超过 1 s）。所以这里只收已经算好的毫秒数，不接管时间语义：
     * 谁算剩余时间谁负责，模板只负责两条必须一致的口径——被中断时复位中断标志并判
     * 「未退出」，睡醒后按 {@link Thread#isAlive()} 判是否真的退出。
     *
     * 放在本文件而不是独立文件：本类是纯 JDK 类，已在 `pureJdkClasses` 清单内；
     * 新增一个未申报的纯 JDK 文件会被 `checkPureJdkList` 判红（清单维护权不在本单）。
     */
    static final class WorkerWait {
        private WorkerWait() {
        }

        static boolean finishJoin(Thread t, long millis) {
            try {
                t.join(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            return !t.isAlive();
        }
    }

    /** 本轮是否停在文件边界等 `Resume`（`Pause & exit` 后从照片墙再点 Import 会用）。 */
    public boolean isPaused() {
        return paused && isRunning();
    }

    /** 本轮固化的清单；尚未 `start()` 时为 null（issue #57）。 */
    public ImportPlan plan() {
        return plan;
    }

    /**
     * 暂停轮次能否用 `next` 继续（issue #57）。
     *
     * 只认同一批文件（`relPath + size`）：勾选 / 档位 / filter 让计划变了，调用方必须按新计划
     * 重开一轮，不得静默沿用旧清单——否则界面上的新勾选永远不生效，且日志只说「继续已暂停的一轮」。
     */
    public boolean canResumeWith(ImportPlan next) {
        ImportPlan current = plan;
        return isPaused() && current != null && ImportPlan.sameFileSet(current, next);
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

    /**
     * 本轮是否「长时间没有传输进展」（issue #63）。
     *
     * 与 {@link #isCancelStuck()} 互补：那条要求 `cancelled=true`，而相机休眠/USB 掉线导致
     * 原生 `importFile` 不返回时，用户根本不会去点取消（`CATALOG_READY` 也没有 Cancel 入口，
     * UI-SPEC §1.3）。没有这条判定，`Import` 就是死键，只能杀进程。
     */
    public boolean isStalled() {
        return isStalledAt(System.nanoTime());
    }

    /** 用指定时刻判定，便于 JVM 自检（不依赖真实等待）。 */
    boolean isStalledAt(long nowNanos) {
        return stalled(isRunning(), cancelled, paused, lastActivityNanos, nowNanos,
                NO_ACTIVITY_GRACE_NANOS);
    }

    /**
     * 纯判定：worker 还活着 + 没取消 + 没暂停 + 进过文件 + 距最后一次文件边界已超过宽限期。
     *
     * 排除 `cancelled`：取消路径交给 {@link #cancelStuck}（3 s 口径），两条判定不重叠，
     * 否则取消后会被两边同时认领，日志与出路重叠。
     * 排除 `paused`：`Pause & exit` 后可以停任意久，暂停本身不是挂起；恢复前有独立的
     * 会话探活（`Main.doStart()` 的暂停分支）。
     */
    static boolean stalled(boolean running, boolean cancelled, boolean paused,
                           long lastActivityNanos, long nowNanos, long graceNanos) {
        if (!running || cancelled || paused || lastActivityNanos == 0L) return false;
        return nowNanos - lastActivityNanos >= graceNanos;
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
        log.line("已请求取消（若正在传文件，会尝试中断；能否打断无跨机型保证）");
    }

    /**
     * 标记本轮已被调用方放弃：worker 之后退出时不再覆写导入日志（三审 B-3）。
     *
     * 只由调用方在「已放弃该轮」时调用。**代价**：{@link Journal} 只在 `run()` 开始时加载一次，
     * `markDone` 只改内存，{@link RoundFiles#save} 只在 `finally` 整文件覆写；放弃后跳过保存意味着
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
        this.plan = plan;
        // 新一轮从「还没进过文件」开始：不在这里读钟，避免与暂停记账的时钟调用次序耦合。
        lastActivityNanos = 0L;
        cancelToken = new CancelToken();
        worker = new Thread(() -> run(plan, listener), "import-worker");
        worker.start();
    }

    private void run(ImportPlan plan, ImportListener l) {
        // 本轮固定使用 start() 建的令牌：cancel() 可能在中途重建字段，worker 不能换令牌。
        final CancelToken token = cancelToken;
        Journal journal = RoundFiles.load(outDir, log);
        TransferProgress progress = new TransferProgress(plan.totalBytes(), plan.count());
        progress.start(clock.nanoTime());
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
                // 暂停时钟以 worker 真正停在文件边界为准：请求暂停后当前文件仍会传完，
                // 那段有真实字节增量，不能当成暂停扣掉（issue #58）。
                boolean pauseClockStarted = false;
                while (paused && !cancelled) {
                    if (!pauseClockStarted) {
                        progress.pause(clock.nanoTime());
                        pauseClockStarted = true;
                    }
                    Thread.sleep(200);
                }
                if (pauseClockStarted) {
                    progress.resume(clock.nanoTime());
                }
                if (cancelled) {
                    stop = "已取消";
                    break;
                }

                // #54：同一 relPath 出现在多个存储时，两个文件会争同一落盘路径。
                // 必须放在 Journal 判定之前：否则同尺寸的第二项会被当成「已完成」静默跳过，
                // 用户看到的是「一切顺利」，实际少了一张。这里显式计入失败，不调 transfer、不写 Journal。
                if (item.conflict) {
                    failed++;
                    String note = "多存储同名路径冲突：拒绝覆盖"
                            + "（" + item.relPath + "，storageId=" + item.storageId + "）";
                    log.line(note);
                    l.onFileFinished(item, ResumableTransfer.Kind.FAILED, note);
                    l.onProgress(progress, item);
                    continue;
                }

                // #177：Journal 只说明上一轮记过完成，不能单独证明成品还在。续传停用后
                // 尺寸对账必须落到实际文件：成品缺失 / 尺寸不符都重新导入，不许静默跳过。
                // #209 / G1：这两个条件的「与」只在 SkipRule 里拼一次。
                SkipRule.ProductProbe probe = productProbe;
                SkipRule.Verdict verdict = probe == null
                        ? SkipRule.check(journal, outDir, item.relPath, item.size)
                        : SkipRule.check(journal, probe, item.relPath, item.size);
                if (verdict == SkipRule.Verdict.SKIP) {
                    skipped++;
                    progress.onBytes(item.size);
                    progress.onFileDone();
                    l.onFileFinished(item, ResumableTransfer.Kind.SKIPPED, UiText.fileSkippedNote());
                    l.onProgress(progress, item);
                    continue;
                }
                if (verdict == SkipRule.Verdict.STALE) {
                    log.line("导入记录显示已完成，但成品缺失或尺寸不符，本轮重新导入："
                            + item.relPath);
                }

                // #177：续传已停用。旧 `.part` 一律视为不可信，起点恒为 0；传输层负责先删除
                // 残留再整段重写。重试也从 0 开始，不能拿失败轮次写下的长度当新起点，否则
                // 真机「忽略 offset、从 0 给数据」的 reader 会把 0 前缀追加到残留后面。
                String part = journal.partPath(outDir.getAbsolutePath(), item.relPath, item.size);

                // PD（issue #50）：单文件失败后同步重试，上限 MAX_FILE_ATTEMPTS。
                // 每次尝试都从 0 重写（#177），取消优先：已取消就不再重试、也不计失败。
                int attempts = 0;
                long resumeFrom = 0L;
                ResumableTransfer.Result r;
                for (;;) {
                    attempts++;
                    // #70：进入本次尝试前重设当前文件进度（从 0 开始；
                    // 失败后重试会再走一次，重设而非累加，保证同一段字节不被记两遍）。
                    progress.beginFile(resumeFrom, item.size);
                    // #70：传输层按块回调绝对偏移，这里同时刷新监听器——界面能拿到文件内进度。
                    LongConsumer sink = bytes -> {
                        progress.onFileProgress(bytes);
                        l.onProgress(progress, item);
                    };
                    // issue #63：进入本次 importFile 前刷新「最后活动时刻」。
                    // 放在 transfer 之前、返回后不刷新，这样一次原生调用卡住多久就能被看出。
                    lastActivityNanos = clock.nanoTime();
                    r = transfer.transfer(item, part, resumeFrom, token, sink);
                    if (r.kind != ResumableTransfer.Kind.FAILED) break;
                    if (cancelled || attempts >= MAX_FILE_ATTEMPTS) break;
                    log.line("第 " + attempts + " 次传输失败，同步重试（上限 "
                            + MAX_FILE_ATTEMPTS + " 次）：" + item.relPath + " · " + r.note);
                }

                if (r.kind == ResumableTransfer.Kind.FAILED && cancelled) {
                    // 取消与失败竞态：以取消为准，不把用户主动取消计入失败。
                    // #70：当前文件没传完，丢掉文件内进度（界面数字会往回走，属预期）。
                    progress.rollbackFile();
                    stop = "已取消";
                    l.onFileFinished(item, ResumableTransfer.Kind.CANCELLED, "已取消");
                    break;
                }

                switch (r.kind) {
                    case OK:
                        ok++;
                        journal.markDone(item.handle, item.size, item.size, item.relPath);
                        // #70：只按对账大小记一次完成字节，块级上报的当前文件进度在此清零。
                        progress.commitFile(item.size);
                        l.onFileFinished(item, r.kind, r.note);
                        l.onProgress(progress, item);
                        break;
                    case SKIPPED:
                        skipped++;
                        // #177 复审：同尺寸成品也必须落账，否则下一轮会再整文件重下再丢弃（不收敛）。
                        // 账本只记 relPath + size，与 SkipRule 的判定口径一致，不放宽校验。
                        journal.markDone(item.handle, item.size, item.size, item.relPath);
                        progress.onBytes(item.size);
                        progress.onFileDone();
                        l.onFileFinished(item, r.kind, r.note);
                        l.onProgress(progress, item);
                        break;
                    case CANCELLED:
                        // 取消的文件既不记完成也不计失败；半成品已由传输层清掉。
                        // 不推进进度，避免给界面一个「这个文件传完了」的假信号。
                        // #70：丢掉当前文件的块级进度，已完成文件不动。
                        progress.rollbackFile();
                        stop = "已取消";
                        l.onFileFinished(item, r.kind, r.note);
                        break;
                    case FAILED:
                    default:
                        failed++;
                        // #70：失败回退当前文件那部分进度（界面数字往回走属预期，不是抖动）。
                        progress.rollbackFile();
                        // PD：重试耗尽后把尝试次数带进 note，界面/日志能看出不是一次就放弃。
                        String note = attempts > 1
                                ? r.note + " · 已尝试 " + attempts + " 次"
                                : r.note;
                        l.onFileFinished(item, r.kind, note);
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
                log.line("本轮已被放弃：跳过导入日志保存（不覆盖磁盘上的现有记录）");
            } else {
                RoundFiles.save(outDir, journal, log);
                // #20 裁决 B：`imported-index.txt` 全仓没有读方，已停写（这里原先还会重建索引）。
            }
            worker = null;
        }

        String summary = UiText.runSummary(ok, skipped, failed, plan.count(), stop);
        log.line("本轮结束：" + summary);
        l.onFinished(cancelled, ok, skipped, failed, summary);
    }

}
