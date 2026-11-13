package com.rea.mtpimport;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import com.rea.mtpimport.ResumableTransfer.Kind;

import static com.rea.mtpimport.TestSupport.*;
import static com.rea.mtpimport.TempDirs.*;
import static com.rea.mtpimport.Fixtures.*;
import static com.rea.mtpimport.TestFakes.*;
import static com.rea.mtpimport.SourceGuard.*;

/** 源码文本护栏的断言组：注释坐标、索引停写、文案来源等（#318 从 SelfTest.java 拆出）。 */
final class SourceGuardCases {

    // ---------------- 注释里的「文件:行号」引用（issue #66） ----------------

    /** 源码注释里指向别的文件的 `文件:行号` 形态；行号会随改动漂移，只认内容坐标。 */
    private static final Pattern FILE_LINE_REF = Pattern.compile("\\.(java|kt):\\d+");

    /**
     * issue #66：三处注释用 `文件:行号` 指向别处，行号会随代码改动失效。
     *
     * 只锁 issue 点名的三个文件；全局扫描要先定豁免口径，另开单。
     * 文件缺失也判红——文件改名后断言不能静默失效。
     */
    static void commentRefCases() {
        List<String> files = List.of(
                "src/com/rea/mtpimport/Catalog.java",
                "src/com/rea/mtpimport/MtpSession.java",
                "src/com/rea/mtpimport/ui/AppModel.kt");
        StringBuilder hits = new StringBuilder();
        for (String rel : files) {
            File f = new File(REPO_ROOT, rel);
            if (!f.isFile()) {
                hits.append(rel).append("（文件不存在）\n");
                continue;
            }
            try {
                for (String line : Files.readAllLines(f.toPath())) {
                    if (FILE_LINE_REF.matcher(line).find()) {
                        hits.append(rel).append(": ").append(line.trim()).append('\n');
                    }
                }
            } catch (IOException e) {
                hits.append(rel).append("（读取失败: ").append(e).append("）\n");
            }
        }
        check("注释不用 文件:行号 引用（issue #66）", hits.length() == 0, "命中：\n" + hits);
    }

    /**
     * issue #177 的源码级护栏：`MtpTransfer` 依赖 Android `MtpDevice`，JVM 用例覆盖不到
     * 它的读取循环，因此把「不许再按 offset 读、不许保留 append / `from == size` 直通、
     * 成功文案必须说明内容未校验」钉在源码上，避免后续改动悄悄把续传加回来。
     *
     * 只锁 `MtpTransfer` 这一个文件：它不在 G9 的拆分范围，文件级契约不会随重构误红。
     * 「不许按 offset 读」只约束**传输层**；预览读是另一条只读链路
     * （`ThumbnailStore.fetchFull` → `RangeRead`），不受这条断言影响。
     * `ImportService` 的对应行为（起点恒 0、账本完成还要对磁盘）改由
     * {@link #resumeDisabledCases()} 与 `SelfTest` 的「账本完成 + 磁盘截断」用例守着（issue #212）。
     */
    static void noResumeSourceCases() {
        File mtp = source("com/rea/mtpimport/MtpTransfer.java");
        String src = readSourceOrNull(mtp);
        check("续传停用：MtpTransfer 不再按 offset 读相机对象（#177）",
                src != null && !src.contains("getPartialObject("),
                src == null ? "读不到 " + mtp : "仍调用 getPartialObject");
        check("续传停用：MtpTransfer 不再保留 append 或 from == size 直通（#177）",
                src != null && !src.contains("private Result append(")
                        && !src.contains("from == item.size"),
                src == null ? "读不到 " + mtp : "append 或尺寸直通仍在");
        check("完成口径：成功文案写明「内容未校验」（#177）",
                src != null && src.contains("尺寸核对通过") && src.contains("内容未校验"),
                src == null ? "读不到 " + mtp : "文案仍在暗示校验通过");
        check("完成口径：同尺寸已存在文件记 SKIPPED，不冒充 OK（#177）",
                src != null && src.contains("Kind.SKIPPED, UiText.sameSizeExists()")
                        && !src.contains("Kind.OK, UiText.sameSizeExists()"),
                src == null ? "读不到 " + mtp : "同尺寸分支仍可能记 OK");

    }

    /**
     * 「Skipped N existing」只能由 {@link UiText#skippedLine} 产出（issue #127）。
     *
     * 不锁具体文件名/方法名：`src/` 下出现拼接式字面量即判红，`Main` 拆到别的类也不会误红。
     * `skippedLine` 的取值行为（0 / 负数 / 正数）由 {@link #uiTextCases()} 断言（issue #212）。
     */
    static void skippedSourceCases() {
        File srcRoot = SRC_ROOT;
        List<String> hits = new ArrayList<>();
        int scanned = scanSourceText(srcRoot, "\"Skipped \" +", "src/com/rea/mtpimport/UiText.java", hits);
        check("Skipped 文案只有 UiText.skippedLine 一处拼接（issue #127）",
                scanned > 0 && hits.isEmpty(),
                "扫描 " + scanned + " 个文件，命中：\n" + String.join("\n", hits));

        List<String> uses = new ArrayList<>();
        // 收紧到「日志接线」这一处：`ui/AppModel.kt` 只是把数值塞进状态，不能替这条守卫兜底。
        int scannedUses = scanSourceText(srcRoot, "log.line(UiText.skippedLine(", null, uses);
        check("日志仍接在 UiText.skippedLine 上，不是删掉字面量了事（issue #127）",
                scannedUses > 0 && !uses.isEmpty(),
                "扫描 " + scannedUses + " 个文件，0 处调用");

        // doStart 内部「先清零再早退」的语句顺序断言已随 G9a 迁到 ImportEntry；
        // 由 {@link #importEntryCases()} 直接驱动行为（清零早于所有早退分支），不再扫源码。
    }

    /**
     * 同一句用户可见文案只能由 {@link UiText} 产出（issue #211 / G3）。
     *
     * 只锁「整句重复」的三族：取消、字节不符、同尺寸跳过，外加逐文件跳过 note；
     * `Failed: ` / `[Skipped]` 等状态前缀不在此列。扫描面是 `src/` 全目录，
     * 拆类或改名不会让守卫误红。
     */
    static void duplicateTextCases() {
        File srcRoot = SRC_ROOT;
        String allow = "src/com/rea/mtpimport/UiText.java";
        checkOnlyFromUiText(srcRoot, allow, "已取消，整个半成品已删除");
        checkOnlyFromUiText(srcRoot, allow, "字节数不符：期望 ");
        checkOnlyFromUiText(srcRoot, allow, "已存在同尺寸文件，未覆盖");
        checkOnlyFromUiText(srcRoot, allow, "Skipped existing");
    }

    /**
     * 导入入口的语句顺序（G9a / issue #127、#212）。
     *
     * 原来由 `skippedSourceCases` 的源码位置守卫守着；`Main.doStart` 的前置判定拆到
     * {@link ImportEntry} 后改成语义断言：跳过数清零必须早于所有早退分支，且 demo
     * 判定优先于「没有照片墙」（issue #46：demo 下 `Main.catalog` 恒为 null）。
     */
    static void importEntryCases() {
        List<String> events = new ArrayList<>();
        ImportEntry.Sink sink = new ImportEntry.Sink() {
            @Override
            public void resetSkipped() {
                events.add("clear");
            }

            @Override
            public void logLine(String line) {
                events.add("log:" + line);
            }
        };

        ImportEntry.Stage demo = ImportEntry.begin(sink, true, false);
        check("ImportEntry：demo 优先于「没有照片墙」，且先清零跳过数（#46 / #127）",
                demo == ImportEntry.Stage.DEMO && events.equals(Arrays.asList("clear")),
                "stage=" + demo + " events=" + events);

        events.clear();
        ImportEntry.Stage needsScan = ImportEntry.begin(sink, false, false);
        check("ImportEntry：没有照片墙时提示先扫描，清零仍早于早退（#127）",
                needsScan == ImportEntry.Stage.NEEDS_SCAN
                        && events.equals(Arrays.asList("clear", "log:先扫描")),
                "stage=" + needsScan + " events=" + events);

        events.clear();
        ImportEntry.Stage ready = ImportEntry.begin(sink, false, true);
        check("ImportEntry：有照片墙进入编排，不多打日志",
                ready == ImportEntry.Stage.READY && events.equals(Arrays.asList("clear")),
                "stage=" + ready + " events=" + events);
    }

    /** 记录界面投递的假 {@link ImportUi}：`post` 只入队，测试自己 `flush()` 执行。 */
    static final class RecordingUi implements ImportUi {
        final List<Runnable> posted = new ArrayList<>();
        int progressDone = -1;
        int progressTotal = -1;
        final List<String> failed = new ArrayList<>();
        int errors;
        final List<String> finished = new ArrayList<>();

        @Override
        public void post(Runnable r) {
            posted.add(r);
        }

        @Override
        public void updateProgress(int done, int total) {
            progressDone = done;
            progressTotal = total;
        }

        @Override
        public void addFailedLine(String line) {
            failed.add(line);
        }

        @Override
        public void showImportError() {
            errors++;
        }

        @Override
        public void finishRound(boolean cancelled, int ok, int skipped, int failedCount, long bytesDone) {
            finished.add(cancelled + "/" + ok + "/" + skipped + "/" + failedCount + "/" + bytesDone);
        }

        void flush() {
            List<Runnable> queue = new ArrayList<>(posted);
            posted.clear();
            for (Runnable r : queue) r.run();
        }
    }

    /**
     * 回调转发与迟到回调丢弃（G9a，从 `Main.doStart` 的匿名 `ImportListener` 抽出）。
     *
     * 重点是三件会静默出错的事：被放弃/换轮的迟到回调不得动界面、进度合并闸只留一个
     * 待执行更新、逐文件日志用 UI 词而不是内部枚举名。
     */
    static void importRoundListenerCases() {
        Catalog c = sample();
        Selection s = new Selection(c);
        s.setChecked(1, true);
        ImportPlan plan = ImportPlan.of(c, s, PhotoWall.of(c).units(), PhotoWall.Export.JPG_FIRST);
        ImportPlan.Item item = plan.items().get(0);

        RecordingUi ui = new RecordingUi();
        List<String> logs = new ArrayList<>();
        AtomicBoolean current = new AtomicBoolean(true);
        ImportRoundListener listener = new ImportRoundListener(logs::add, ui, current::get);

        listener.onPlanReady(plan);
        check("ImportRoundListener：计划日志带文件数与字节数",
                logs.size() == 1 && logs.get(0).contains(plan.count() + " 个文件 / " + plan.totalBytes()),
                "实际 " + logs);

        TransferProgress p1 = new TransferProgress(plan.totalBytes(), plan.count());
        p1.commitFile(10);
        listener.onProgress(p1, item);
        check("ImportRoundListener：首次进度投递一个更新，且记住 lastProgress",
                listener.lastProgress() == p1 && ui.posted.size() == 1,
                "posted=" + ui.posted.size());

        TransferProgress p2 = new TransferProgress(plan.totalBytes(), plan.count());
        p2.commitFile(10);
        p2.commitFile(10);
        listener.onProgress(p2, item);
        check("ImportRoundListener：已有待执行更新时不重复投递（合并闸）",
                ui.posted.size() == 1 && listener.lastProgress() == p2,
                "posted=" + ui.posted.size());
        ui.flush();
        check("ImportRoundListener：合并后的更新读最新进度",
                ui.progressDone == p2.filesDone() && ui.progressTotal == p2.totalFiles(),
                "done=" + ui.progressDone + " total=" + ui.progressTotal);

        current.set(false);
        TransferProgress p3 = new TransferProgress(plan.totalBytes(), plan.count());
        p3.commitFile(10);
        listener.onProgress(p3, item);
        listener.onFileFinished(item, Kind.FAILED, "被放弃的轮次");
        listener.onFinished(false, 0, 0, 1, "被放弃");
        listener.onError("被放弃", null);
        check("ImportRoundListener：换轮后迟到进度不改 lastProgress、不投递",
                listener.lastProgress() == p2 && ui.posted.isEmpty(),
                "posted=" + ui.posted.size());
        // 逐文件日志不受 current 影响：它只写日志，不动界面（原来也是先打日志再判 current）。
        check("ImportRoundListener：迟到文件回调仍留日志，但不进失败抽屉",
                logs.get(logs.size() - 1).startsWith("[Failed] ") && ui.failed.isEmpty(),
                "logs=" + logs + " failed=" + ui.failed);

        current.set(true);
        listener.onFileFinished(item, Kind.OK, "");
        listener.onFileFinished(item, Kind.SKIPPED, "已存在同尺寸文件，未覆盖");
        check("ImportRoundListener：逐文件日志用 UI 词，不出现内部枚举名",
                logs.get(logs.size() - 2).startsWith("[Done] ")
                        && logs.get(logs.size() - 1).startsWith("[Skipped] ")
                        && !logs.get(logs.size() - 2).contains("Kind."),
                "实际 " + logs);

        listener.onFileFinished(item, Kind.FAILED, "读失败");
        listener.onFinished(false, 1, 1, 1, "摘要");
        // 界面动作都是投递式：先执行队列，再核对（否则只看到「已投递」而不是「做了什么」）。
        ui.flush();
        check("ImportRoundListener：失败文件投递 Failed: 行（PD / #50）",
                ui.failed.size() == 1 && ui.failed.get(0).startsWith("Failed: " + item.relPath),
                "实际 " + ui.failed);
        check("ImportRoundListener：跳过数 > 0 才打 Skipped 日志（#127）",
                logs.stream().anyMatch(l -> l.equals(UiText.skippedLine(1))),
                "实际 " + logs);
        check("ImportRoundListener：收尾带上当前进度字节数",
                ui.finished.size() == 1 && ui.finished.get(0).equals("false/1/1/1/" + p2.bytesDone()),
                "实际 " + ui.finished);

        RecordingUi ui2 = new RecordingUi();
        ImportRoundListener zero = new ImportRoundListener(Log.NOOP::line, ui2, () -> true);
        zero.onFinished(false, 1, 0, 0, "摘要");
        check("ImportRoundListener：0 跳过不打「跳过 0」（#127）",
                ui2.posted.size() == 1,
                "实际 " + ui2.posted.size());
        ui2.flush();
        check("ImportRoundListener：没有进度时收尾字节数为 0",
                ui2.finished.size() == 1 && ui2.finished.get(0).endsWith("/0"),
                "实际 " + ui2.finished);

        check("ImportRoundListener：kindLabel 不直出内部枚举名（#177）",
                ImportRoundListener.kindLabel(Kind.OK).equals("Done")
                        && ImportRoundListener.kindLabel(Kind.SKIPPED).equals("Skipped")
                        && ImportRoundListener.kindLabel(Kind.FAILED).equals("Failed")
                        && ImportRoundListener.kindLabel(Kind.CANCELLED).equals("Cancelled"),
                "映射走偏");
    }

    /**
     * 被放弃的轮次闸门（G9a，三审 B-3）。
     *
     * 旧 worker 未退出前必须拦住重建会话；退出后自动放行；被 `abandon()` 的轮次
     * 不再覆写导入日志（否则会冲掉新一轮的 `import-journal.txt`）。
     */
    static void orphanRoundsCases() throws Exception {
        OrphanRounds rounds = new OrphanRounds();
        List<String> logs = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        OrphanRounds.Sink sink = new OrphanRounds.Sink() {
            @Override
            public void logLine(String line) {
                logs.add(line);
            }

            @Override
            public void showError(String message) {
                errors.add(message);
            }
        };

        check("OrphanRounds：没有登记时不拦",
                !rounds.stillRunning(sink) && rounds.current() == null,
                "current=" + rounds.current());

        Catalog c = sample();
        Selection s = new Selection(c);
        s.setChecked(3, true);
        s.setChecked(6, true);
        ImportPlan plan = ImportPlan.of(c, s, PhotoWall.of(c).units(), PhotoWall.Export.JPG_FIRST);

        withTempDir("wiredraw-orphan-abandon", abandonedOut -> {
            FakeTransfer hanging = new FakeTransfer(FakeTransfer.Mode.HANGING);
            ImportService stuck = new ImportService(Log.NOOP, hanging, abandonedOut);
            stuck.start(plan, new FinishRecorder());
            check("OrphanRounds：卡住的 worker 已进入传输", awaitLatch(hanging.entered), "等待超时");

            rounds.abandon(stuck);
            check("OrphanRounds：abandon 之后登记为被放弃轮次",
                    rounds.current() == stuck, "current=" + rounds.current());
            check("OrphanRounds：worker 未退出时拦下重建会话（三审 B-3）",
                    rounds.stillRunning(sink) && errors.equals(Collections.singletonList(StuckError.SESSION_LOST)),
                    "errors=" + errors);
            check("OrphanRounds：拦下时留下一条可复现的日志",
                    logs.size() == 1 && logs.get(0).contains("上一轮传输线程仍未退出"),
                    "实际 " + logs);

            hanging.release.countDown();
            check("OrphanRounds：worker 退出后自动放行并清掉登记",
                    stuck.awaitWorker(5000) && !rounds.stillRunning(sink) && rounds.current() == null,
                    "仍活着或未清空");
            check("OrphanRounds：被放弃的轮次不写导入日志（不冲掉新一轮记录）",
                    !new File(abandonedOut, "import-journal.txt").isFile(),
                    "日志被写了");
        });

        withTempDir("wiredraw-orphan-adopt", adoptedOut -> {
            FakeTransfer hanging2 = new FakeTransfer(FakeTransfer.Mode.HANGING);
            ImportService adopted = new ImportService(Log.NOOP, hanging2, adoptedOut);
            adopted.start(plan, new FinishRecorder());
            check("OrphanRounds：adopt 用例 worker 已进入传输",
                    awaitLatch(hanging2.entered), "等待超时");
            rounds.adopt(adopted);
            check("OrphanRounds：adopt 只登记，不调 abandon()",
                    !new File(adoptedOut, "import-journal.txt").isFile(),
                    "还没收尾就出现日志");
            hanging2.release.countDown();
            check("OrphanRounds：adopt 的轮次正常收尾仍保存日志",
                    adopted.awaitWorker(5000) && new File(adoptedOut, "import-journal.txt").isFile(),
                    "日志未保存");
        });
    }

    /**
     * 空间预检（G9a，从 `Main.refreshSpaceCheck` / `precheckSpace` 抽出）。
     *
     * 两段共用 `SpaceCheck` 的判据，但语义不同：`refresh` 只刷禁用态，计划为空时清态且
     * **不取磁盘数字**（原实现在 `plan.isEmpty()` 早退之后才 `StatFs`）；`precheck` 不足时
     * 返回 false 并落一行日志，调用方据此早退。
     */
    static void spaceGateCases() {
        List<String> statuses = new ArrayList<>();
        List<String> logs = new ArrayList<>();
        SpaceGate.Sink sink = new SpaceGate.Sink() {
            @Override
            public void setSpaceStatus(boolean low, String detail) {
                statuses.add(low + "/" + detail);
            }

            @Override
            public void logLine(String line) {
                logs.add(line);
            }
        };

        Catalog c = sample();
        Selection s = new Selection(c);
        // 空勾选会兜底成「当前可见集合」（issue #80），所以空计划只能用可见集合为空来构造：
        // 例如筛选后一个可导出文件都不剩（issue #83）。
        ImportPlan emptyPlan = ImportPlan.of(c, s, Collections.emptyList(), PhotoWall.Export.JPG_FIRST);
        check("SpaceGate：可见集合为空时计划为空", emptyPlan.isEmpty(), "实际非空");

        boolean[] asked = {false};
        SpaceGate.refresh(sink, emptyPlan, () -> {
            asked[0] = true;
            return 0L;
        });
        check("SpaceGate.refresh：空计划清禁用态，且不取磁盘数字",
                statuses.equals(Collections.singletonList("false/")) && !asked[0],
                "statuses=" + statuses + " asked=" + asked[0]);

        s.setChecked(1, true);
        ImportPlan plan = ImportPlan.of(c, s, PhotoWall.of(c).units(), PhotoWall.Export.JPG_FIRST);
        statuses.clear();
        SpaceGate.refresh(sink, plan, () -> 10_000_000L);
        check("SpaceGate.refresh：空间足够时不留 Low space",
                statuses.size() == 1 && statuses.get(0).equals("false/"),
                "实际 " + statuses);

        statuses.clear();
        SpaceGate.refresh(sink, plan, () -> 0L);
        check("SpaceGate.refresh：空间不足时落同口径差额",
                statuses.size() == 1 && statuses.get(0).equals("true/" + SpaceCheck.describe(0, plan.totalBytes())),
                "实际 " + statuses);

        statuses.clear();
        check("SpaceGate.precheck：空间足够返回 true 并清禁用态",
                SpaceGate.precheck(sink, plan, 10_000_000L) && statuses.equals(Collections.singletonList("false/")),
                "实际 " + statuses);

        statuses.clear();
        logs.clear();
        check("SpaceGate.precheck：空间不足返回 false，落 Low space 并记一行日志",
                !SpaceGate.precheck(sink, plan, 0L)
                        && statuses.equals(Collections.singletonList("true/" + SpaceCheck.describe(0, plan.totalBytes())))
                        && logs.equals(Collections.singletonList("空间不足：计划 " + plan.totalBytes() + " 字节，可用 0 字节")),
                "statuses=" + statuses + " logs=" + logs);
    }

    // ---------------- 缩略图缓存与视口感知调度护栏（issue #370 / #373） ----------------

    /**
     * 墓碑（#370 / #373）：
     * 1. LibraryScreen 不再在格子移出视口时通过 DisposableEffect / finally 强制 evict，
     *    保持 ThumbnailStore 的 24 MiB LruCache 容量控制正常生效（#370）；
     * 2. ThumbnailStore 维持 24 MiB LruCache 上限不变（#370）；
     * 3. ThumbnailStore 提供 loadAsync 队列接口，采用 LIFO 调度与 isCancelled 离屏取消（#373）；
     * 4. LibraryScreen PhotoCell 使用 loadAsync 发起生命周期感知调度（#373）。
     */
    static void thumbnailGuardCases() {
        File lib = source("com/rea/mtpimport/ui/LibraryScreen.kt");
        String libSrc = readSourceOrNull(lib);
        check("缩略图缓存：LibraryScreen 不再对移出视口做强制驱逐（#370）",
                libSrc != null && !libSrc.contains("store.evict(") && !libSrc.contains("store?.evict("),
                libSrc == null ? "读不到 " + lib : "仍包含 store.evict 调用");

        check("缩略图调度：LibraryScreen PhotoCell 使用 loadAsync 调度（#373）",
                libSrc != null && libSrc.contains("loadAsync("),
                libSrc == null ? "读不到 " + lib : "未找到 loadAsync 调用");

        File store = source("com/rea/mtpimport/ThumbnailStore.java");
        String storeSrc = readSourceOrNull(store);
        check("缩略图缓存：保持 24 MiB LruCache 内存上限（#370）",
                storeSrc != null && storeSrc.contains("CACHE_BYTES = 24 << 20"),
                storeSrc == null ? "读不到 " + store : "未找到 CACHE_BYTES = 24 << 20");

        check("缩略图调度：ThumbnailStore 提供 loadAsync 队列接口（#373）",
                storeSrc != null && storeSrc.contains("loadAsync("),
                storeSrc == null ? "读不到 " + store : "未找到 loadAsync 接口");

        check("缩略图调度：采用 LIFO 优先队列调度（#373）",
                storeSrc != null && (storeSrc.contains("addFirst(") || storeSrc.contains("push(")),
                storeSrc == null ? "读不到 " + store : "未找到 LIFO 入队操作");

        check("缩略图调度：执行前校验 isCancelled 放弃离屏任务（#373）",
                storeSrc != null && storeSrc.contains("isCancelled("),
                storeSrc == null ? "读不到 " + store : "未找到 isCancelled 校验");
    }

    // ---------------- 平滑扫描进度指示与优先发现日期文件夹（issue #371 / #372） ----------------

    /**
     * 墓碑（#371 / #372）：
     * 1. CatalogScanner 移除硬编码 50 步长，引入时间节流 PROGRESS_INTERVAL_MS 实现平滑进度回调（#371）；
     * 2. CatalogScanner ScanProgress 支持 onFoldersDiscovered 优先发现文件夹回调（#372）；
     * 3. CatalogScanner 在扁平扫描前执行优先文件夹探查，免除后续重复 MTP 访问（#372）；
     * 4. ScanTask.Host 具备 onFoldersDiscovered 回调（#372）。
     */
    static void scannerGuardCases() {
        File scanner = source("com/rea/mtpimport/CatalogScanner.java");
        String scannerSrc = readSourceOrNull(scanner);
        check("扫描进度平滑：移除硬编码 50 步长（#371）",
                scannerSrc != null && !scannerSrc.contains("scanned % 50 == 0"),
                scannerSrc == null ? "读不到 " + scanner : "仍包含 scanned % 50 == 0 粗粒度判断");

        check("扫描进度平滑：定义时间节流常量 PROGRESS_INTERVAL_MS（#371）",
                scannerSrc != null && scannerSrc.contains("PROGRESS_INTERVAL_MS"),
                scannerSrc == null ? "读不到 " + scanner : "未找到 PROGRESS_INTERVAL_MS");

        check("文件夹优先发现：ScanProgress 声明 onFoldersDiscovered（#372）",
                scannerSrc != null && scannerSrc.contains("onFoldersDiscovered"),
                scannerSrc == null ? "读不到 " + scanner : "ScanProgress 未声明 onFoldersDiscovered");

        check("文件夹优先发现：CatalogScanner 包含 discoverFolders 探查逻辑（#372）",
                scannerSrc != null && scannerSrc.contains("discoverFolders("),
                scannerSrc == null ? "读不到 " + scanner : "未找到 discoverFolders 探查调用");

        File task = source("com/rea/mtpimport/ScanTask.java");
        String taskSrc = readSourceOrNull(task);
        check("文件夹优先发现：ScanTask.Host 声明 onFoldersDiscovered 回调（#372）",
                taskSrc != null && taskSrc.contains("onFoldersDiscovered"),
                taskSrc == null ? "读不到 " + task : "ScanTask.Host 未声明 onFoldersDiscovered");
    }
}
