package com.rea.mtpimport;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.rea.mtpimport.ResumableTransfer.Kind;

import static com.rea.mtpimport.TestSupport.*;
import static com.rea.mtpimport.Fixtures.*;
import static com.rea.mtpimport.TestFakes.*;
import static com.rea.mtpimport.TempDirs.*;

/** 多存储冲突、目标策略、暂停取消与恢复计划的断言组（#318 从 SelfTest.java 拆出）。 */
final class ConflictCases {

    static void storageConflictCases() throws Exception {
        Catalog c = conflictCatalog();
        List<Catalog.Entry> conflicts = c.storageConflicts(c.all());
        check("多存储同名：storageConflicts 暴露两个同 relPath 同尺寸文件（#54）",
                conflicts.size() == 2
                        && "DCIM/100MSDCF/DSC02183.ARW".equals(conflicts.get(0).relPath)
                        && conflicts.get(1).relPath.equals(conflicts.get(0).relPath)
                        && conflicts.get(0).size == conflicts.get(1).size,
                "conflicts=" + conflicts);

        Selection s = new Selection(c);
        ImportPlan p = ImportPlan.of(c, s, PhotoWall.of(c).units(), PhotoWall.Export.RAW);
        boolean marked = p.count() == 2;
        for (ImportPlan.Item it : p.items()) marked &= it.conflict;
        check("多存储同名：清单把两项都标成冲突（#54）", marked,
                "count=" + p.count() + " items=" + p.items());

        // 冲突只应在「本次实际选中」的集合内判定：两张卡同时插着、只勾一张时没有争用。
        Selection one = new Selection(c);
        one.setChecked(13, true);   // 只勾第二个存储卷的那个文件
        ImportPlan single = ImportPlan.of(c, one, PhotoWall.of(c).units(),
                PhotoWall.Export.RAW);
        check("多存储同名：只勾一个时不算冲突（#54）",
                single.count() == 1 && !single.items().get(0).conflict
                        && single.items().get(0).storageId == 2,
                "count=" + single.count() + " items=" + single.items());

        File singleOut = Files.createTempDirectory("wiredraw-single").toFile();
        FakeTransfer st = new FakeTransfer();
        FinishRecorder sr = new FinishRecorder();
        new ImportService(new RecordingLog(), st, singleOut).start(single, sr);
        check("多存储同名：只勾一个能正常导入（#54）",
                sr.done.await(5, TimeUnit.SECONDS) && sr.ok == 1 && sr.failed == 0
                        && sr.skipped == 0 && st.calls.size() == 1,
                "ok=" + sr.ok + " failed=" + sr.failed + " skipped=" + sr.skipped
                        + " calls=" + st.calls.size());

        File out = Files.createTempDirectory("wiredraw-conflict").toFile();
        FakeTransfer t = new FakeTransfer();
        RecordingLog logs = new RecordingLog();
        FinishRecorder r = new FinishRecorder();
        new ImportService(logs, t, out).start(p, r);
        check("多存储同名：一轮在 5 秒内结束", r.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("多存储同名：两项都失败、不调 transfer、不冒充跳过",
                r.ok == 0 && r.failed == 2 && r.skipped == 0 && t.calls.isEmpty(),
                "ok=" + r.ok + " failed=" + r.failed + " skipped=" + r.skipped
                        + " calls=" + t.calls.size());
        check("多存储同名：失败进入 summary（不是一切顺利）",
                r.summary != null && r.summary.contains("失败 2"), "实际 " + r.summary);
        check("多存储同名：日志有醒目出口（不只调试日志）", logs.contains("拒绝覆盖"),
                "日志=" + logs.lines);

        File jf = new File(out, "import-journal.txt");
        Journal jr = new Journal();
        try (ByteArrayInputStream in = new ByteArrayInputStream(Files.readAllBytes(jf.toPath()))) {
            jr.load(in);
        }
        check("多存储同名：冲突项不写 Journal（不产生假完成）", jr.size() == 0,
                "journal 条数=" + jr.size());

        // DestPolicy：落盘冲突策略本身（MtpTransfer.finish 是唯一调用方，真机验）。
        File base = Files.createTempDirectory("wiredraw-dest").toFile();
        check("DestPolicy：正常 relPath 落到 outDir 下",
                DestPolicy.resolve(base, "DCIM/x.jpg").getCanonicalFile()
                        .equals(new File(base, "DCIM/x.jpg").getCanonicalFile()),
                "实际 " + DestPolicy.resolve(base, "DCIM/x.jpg"));
        check("DestPolicy：`..` 越界返回 null", DestPolicy.resolve(base, "../evil") == null,
                "实际 " + DestPolicy.resolve(base, "../evil"));
        File same = writeSizedFile(new File(base, "same.jpg"), 300);
        File diff = writeSizedFile(new File(base, "diff.jpg"), 100);
        check("DestPolicy：目标不存在 → NONE",
                DestPolicy.existing(new File(base, "missing.jpg"), 300) == DestPolicy.Existing.NONE,
                "实际 " + DestPolicy.existing(new File(base, "missing.jpg"), 300));
        check("DestPolicy：同尺寸视为同一文件，不覆盖（#20 口径）",
                DestPolicy.existing(same, 300) == DestPolicy.Existing.SAME_SIZE,
                "实际 " + DestPolicy.existing(same, 300));
        check("DestPolicy：异尺寸拒绝覆盖（不静默丢数据）",
                DestPolicy.existing(diff, 300) == DestPolicy.Existing.DIFFERENT_SIZE,
                "实际 " + DestPolicy.existing(diff, 300));
    }

    /**
     * 落点策略（issue #28）：默认私有根、相册请求显式回退、镜像 relPath 布局、存量兼容。
     * 这些都不需要真机；相册实际写入与权限行为仍是真机待验项。
     */
    static void destinationPolicyCases() throws Exception {
        File filesDir = Files.createTempDirectory("wiredraw-destpolicy").toFile();
        // 用字面量而不是 DestPolicy.PRIVATE_DIR：常量被改名时这条断言必须能红。
        File privateRoot = new File(filesDir, "imported");

        DestPolicy.Destination def = DestPolicy.choose(filesDir, DestPolicy.Kind.PRIVATE);
        check("落点：默认私有根 = getFilesDir()/imported",
                def.kind == DestPolicy.Kind.PRIVATE
                        && def.root.getCanonicalFile().equals(privateRoot.getCanonicalFile())
                        && !def.fellBack() && def.fallbackReason.isEmpty(),
                "kind=" + def.kind + " root=" + def.root + " reason=[" + def.fallbackReason + "]");

        DestPolicy.Destination none = DestPolicy.choose(filesDir, null);
        check("落点：未提供请求按默认私有",
                none.kind == DestPolicy.Kind.PRIVATE
                        && none.root.getCanonicalFile().equals(privateRoot.getCanonicalFile())
                        && !none.fellBack(),
                "kind=" + none.kind + " root=" + none.root + " reason=[" + none.fallbackReason + "]");

        DestPolicy.Destination gallery = DestPolicy.choose(filesDir, DestPolicy.Kind.GALLERY);
        check("落点：相册请求回退私有（不能静默落到未验位置）",
                gallery.kind == DestPolicy.Kind.PRIVATE
                        && gallery.root.getCanonicalFile().equals(privateRoot.getCanonicalFile()),
                "kind=" + gallery.kind + " root=" + gallery.root);
        check("落点：回退必须带非空原因（日志/界面要能看出来）",
                gallery.fellBack() && gallery.fallbackReason.contains("回退"),
                "reason=[" + gallery.fallbackReason + "]");
        check("落点：回退根与默认根相同（存量文件不会被当冲突）",
                gallery.root.getCanonicalFile().equals(def.root.getCanonicalFile()),
                "fallback=" + gallery.root + " default=" + def.root);

        File mirrorDate = DestPolicy.resolve(def.root, "2025-06-07/C0001.MP4");
        check("落点：镜像相机日期目录，不额外重排",
                mirrorDate != null && mirrorDate.getCanonicalFile()
                        .equals(new File(def.root, "2025-06-07/C0001.MP4").getCanonicalFile()),
                "实际 " + mirrorDate);
        File mirrorDcim = DestPolicy.resolve(def.root, "DCIM/100MSDCF/DSC09782.ARW");
        check("落点：镜像 DCIM 目录，不加机型/日期层",
                mirrorDcim != null && mirrorDcim.getCanonicalFile()
                        .equals(new File(def.root, "DCIM/100MSDCF/DSC09782.ARW").getCanonicalFile()),
                "实际 " + mirrorDcim);
    }

    static void pauseCancelCases() throws Exception {
        Catalog c = sample();
        Selection s = new Selection(c);
        s.setChecked(3, true);      // DSC02183 单元（All 档取 JPG 100，先）
        s.setChecked(6, true);      // DSC02999 单元（回退 ARW 50，后）
        ImportPlan plan = ImportPlan.of(c, s, PhotoWall.of(c).units(),
                PhotoWall.Export.JPG_FIRST);
        check("PB 用例清单恰为 2 个单元文件", plan.count() == 2, "实际 " + plan.count());

        // 1) 暂停落在文件边界：在传的完成，下一个等 resume
        File out = Files.createTempDirectory("wiredraw-pb-pause").toFile();
        FakeTransfer g = new FakeTransfer(FakeTransfer.Mode.GATED);
        FinishRecorder r1 = new FinishRecorder();
        ImportService svc = new ImportService(Log.NOOP, g, out);
        svc.start(plan, r1);
        check("PB 暂停：第一个文件已进入传输",
                g.firstEntered.await(5, TimeUnit.SECONDS), "等待超时");
        svc.pause();
        check("PB 暂停：service.isPaused() 为真", svc.isPaused(), "实际 false");
        g.releaseFirst.countDown();
        check("PB 暂停：当前文件传完后停在边界，第二个文件不启动",
                !g.secondEntered.await(400, TimeUnit.MILLISECONDS),
                "第二个文件在暂停期间启动了");
        svc.resume();
        check("PB 恢复：resume 后第二个文件开始",
                g.secondEntered.await(5, TimeUnit.SECONDS), "等待超时");
        check("PB 恢复：本轮 5 秒内结束", r1.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("PB 恢复：两个文件都完成，无跳过/失败",
                r1.ok == 2 && r1.skipped == 0 && r1.failed == 0,
                "ok=" + r1.ok + " skipped=" + r1.skipped + " failed=" + r1.failed);
        check("PB 恢复：两次调用的 resumeFrom 都是 0（不做假续传）",
                g.resumeFroms.size() == 2 && g.resumeFroms.get(0) == 0 && g.resumeFroms.get(1) == 0,
                "实际 " + g.resumeFroms);

        // 暂停期间完成的那个文件已记账：同目录重跑应全部跳过
        FakeTransfer t2 = new FakeTransfer();
        FinishRecorder r2 = new FinishRecorder();
        new ImportService(Log.NOOP, t2, out).start(plan, r2);
        check("PB 暂停：完成的文件下次按 Skipped 跳过",
                r2.done.await(5, TimeUnit.SECONDS) && r2.skipped == 2 && t2.calls.isEmpty(),
                "skipped=" + r2.skipped + " calls=" + t2.calls.size());
        deleteTree(out);

        // 2) 取消后模拟传输在当前文件上返回，且不把该文件记成完成
        File out2 = Files.createTempDirectory("wiredraw-pb-cancel").toFile();
        CancellingTransfer ct = new CancellingTransfer();
        FinishRecorder r3 = new FinishRecorder();
        // #65：取消日志必须能核对，不能再用 Log.NOOP 把文案放过去。
        List<String> cancelLogs = Collections.synchronizedList(new ArrayList<>());
        ImportService svc2 = new ImportService(cancelLogs::add, ct, out2);
        svc2.start(plan, r3);
        check("PB 取消：第一个文件已进入传输",
                ct.entered.await(5, TimeUnit.SECONDS), "等待超时");
        svc2.cancel();
        check("PB 取消：取消动作在传输中被触发（不是只停界面）",
                ct.tokenCancelled.await(5, TimeUnit.SECONDS), "取消动作未触发");
        check("PB 取消：本轮 5 秒内结束", r3.done.await(5, TimeUnit.SECONDS), "等待超时");
        // #65：关闭 fd 只是「尝试」中断原生调用；本机型实测约 0.6 s 返回，能否打断没有
        // 跨机型保证（见 ImportService 类注释与 README）。日志串不得写成已经打断。
        // 负向断言用 noneMatch 扫全部行，不能只对 anyMatch 命中的那一行生效。
        check("PB 取消：日志不把「打断」写成保证（#65）",
                cancelLogs.stream().noneMatch(l -> l.contains("会被中断"))
                        && cancelLogs.stream().anyMatch(l -> l.contains("已请求取消")
                                && l.contains("无跨机型保证")),
                "实际 " + cancelLogs);
        check("PB 取消：cancelled=true 且取消的文件不计失败",
                r3.cancelled && r3.ok == 0 && r3.skipped == 0 && r3.failed == 0,
                "cancelled=" + r3.cancelled + " ok=" + r3.ok + " failed=" + r3.failed);
        check("PB 取消：只尝试了当前文件，后续文件未启动",
                ct.calls.size() == 1, "实际调用 " + ct.calls.size() + " 个文件");
        check("PB 取消：摘要标明已取消",
                r3.summary != null && r3.summary.contains("已取消"), "实际 " + r3.summary);

        FakeTransfer t4 = new FakeTransfer();
        FinishRecorder r4 = new FinishRecorder();
        new ImportService(Log.NOOP, t4, out2).start(plan, r4);
        check("PB 取消：被取消的文件下次重传（不能留下假完成记录）",
                r4.done.await(5, TimeUnit.SECONDS) && r4.ok == 2 && t4.calls.size() == 2,
                "ok=" + r4.ok + " calls=" + t4.calls.size());
        check("PB 取消：重跑时 resumeFrom 仍为 0（不假装续传）",
                t4.resumeFroms.size() == 2 && t4.resumeFroms.get(0) == 0 && t4.resumeFroms.get(1) == 0,
                "实际 " + t4.resumeFroms);
        deleteTree(out2);

        // 3) 「取消卡住」纯判定（二审 B-1）：本机型实测约 0.6 s 返回，打断的跨机型保证未验，
        //    打不断时 worker 永活，界面必须能判出卡住并给 ERROR 出路，不能静默变死键。
        long grace = ImportService.CANCEL_STUCK_GRACE_NANOS;
        check("PB 卡死判定：未请求取消 → 不判定卡死",
                !ImportService.cancelStuck(false, true, 100L, 100L + grace, grace), "实际 true");
        check("PB 卡死判定：已请求取消但 worker 已退出 → 不判定卡死",
                !ImportService.cancelStuck(true, false, 100L, 100L + grace, grace), "实际 true");
        check("PB 卡死判定：宽限期内 → 不判定卡死（正常取消窗口不得误报）",
                !ImportService.cancelStuck(true, true, 100L, 100L + grace - 1, grace), "实际 true");
        check("PB 卡死判定：恰好到宽限期 → 判定卡死",
                ImportService.cancelStuck(true, true, 100L, 100L + grace, grace), "实际 false");
        check("PB 卡死判定：没有记录取消时刻 → 不判定卡死",
                !ImportService.cancelStuck(true, true, 0L, 100L + grace, grace), "实际 true");

        // 4) 真实线程：取消动作打不断传输时 worker 不退出，必须能判定卡住
        File out3 = Files.createTempDirectory("wiredraw-pb-stuck").toFile();
        StuckTransfer st = new StuckTransfer();
        FinishRecorder r5 = new FinishRecorder();
        ImportService svc3 = new ImportService(Log.NOOP, st, out3);
        svc3.start(plan, r5);
        check("PB 卡死：worker 已进入传输", st.entered.await(5, TimeUnit.SECONDS), "等待超时");
        svc3.cancel();
        long afterCancel = System.nanoTime();
        check("PB 卡死：取消后 worker 仍未退出（模拟 pfd.close 打不断原生调用）",
                svc3.isRunning(), "worker 已退出");
        // PD / issue #50：onDestroy 关闭会话前先有界 join；worker 卡住时必须如实返回 false。
        check("PD onDestroy：worker 卡住时 awaitWorker 超时返回 false",
                !svc3.awaitWorker(50), "实际返回 true");
        check("PB 卡死：宽限期内不判定卡死",
                !svc3.isCancelStuckAt(afterCancel), "实际 true");
        check("PB 卡死：超过宽限期判定为卡死（界面必须能给 ERROR 出路）",
                svc3.isCancelStuckAt(afterCancel + grace), "实际 false");
        // 三审 B-3：Main 判定卡死后会放弃该轮；旧轮迟到的 finally 不得覆写 journal。
        svc3.abandon();
        st.release.countDown();
        check("PB 卡死：放行后本轮 5 秒内结束", r5.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("PB 放弃：被放弃的轮次跳过导入日志保存（不覆盖磁盘上的现有记录）",
                !new File(out3, "import-journal.txt").exists(), "日志仍被写出");
        check("PB 卡死：worker 退出后不再判定卡死", !svc3.isCancelStuck(), "实际 true");
        check("PD onDestroy：worker 退出后 awaitWorker 返回 true",
                svc3.awaitWorker(5000), "实际返回 false");
        deleteTree(out3);

        // 5) #65：PAUSED 稳态取消时没有在传的文件，取消动作槽也已空（上一个文件返回时
        //    走 clearOnCancel），日志必须用条件句，不能声称「正在传的文件」在被打断。
        File out4 = Files.createTempDirectory("wiredraw-pb-cancel-paused").toFile();
        FakeTransfer g2 = new FakeTransfer(FakeTransfer.Mode.GATED);
        FinishRecorder r6 = new FinishRecorder();
        List<String> pausedCancelLogs = Collections.synchronizedList(new ArrayList<>());
        ImportService svc4 = new ImportService(pausedCancelLogs::add, g2, out4);
        svc4.start(plan, r6);
        check("PAUSED 取消：第一个文件已进入传输",
                g2.firstEntered.await(5, TimeUnit.SECONDS), "等待超时");
        svc4.pause();
        g2.releaseFirst.countDown();
        check("PAUSED 取消：暂停期间第二个文件不启动",
                !g2.secondEntered.await(400, TimeUnit.MILLISECONDS), "第二个文件启动了");
        check("PAUSED 取消：worker 停在暂停边界（活着但没有文件在传）",
                svc4.isPaused() && svc4.isRunning(),
                "paused=" + svc4.isPaused() + " running=" + svc4.isRunning());
        svc4.cancel();
        check("PAUSED 取消：本轮 5 秒内结束", r6.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("PAUSED 取消：日志不声称正在传文件（#65）",
                pausedCancelLogs.stream().noneMatch(l -> l.contains("会被中断"))
                        && pausedCancelLogs.stream().anyMatch(l -> l.contains("已请求取消")
                                && l.contains("若正在传文件") && l.contains("无跨机型保证")),
                "实际 " + pausedCancelLogs);
        deleteTree(out4);
    }

    // ---------------- 恢复前比较暂停轮次的清单（issue #57） ----------------

    /**
     * 暂停轮次只认自己那份清单：同一批文件可恢复，勾选 / 档位 / filter 变了必须按新计划重开。
     * 这里直接验证 {@link ImportService#canResumeWith(ImportPlan)} 的判定，不模拟 Main 的界面分支。
     */
    static void resumePlanCases() throws Exception {
        Catalog c = sample();
        PhotoWall wall = PhotoWall.of(c);
        Selection sa = new Selection(c);
        sa.setChecked(3, true);   // DSC02183 单元，JPG 优先 → 1 个文件
        ImportPlan planA = ImportPlan.of(c, sa, wall.units(), PhotoWall.Export.JPG_FIRST);
        Selection sb = new Selection(c);
        sb.setChecked(6, true);   // DSC02999 仅 ARW → 1 个文件，与 planA 不同
        ImportPlan planB = ImportPlan.of(c, sb, wall.units(), PhotoWall.Export.JPG_FIRST);
        check("issue #57 用例：两份清单条数相同但文件不同",
                planA.count() == planB.count() && !ImportPlan.sameFileSet(planA, planB),
                "A=" + planA.count() + " B=" + planB.count());

        File out = Files.createTempDirectory("wiredraw-57-resume").toFile();
        FakeTransfer g = new FakeTransfer(FakeTransfer.Mode.GATED);
        FinishRecorder r = new FinishRecorder();
        ImportService svc = new ImportService(Log.NOOP, g, out);
        svc.start(planA, r);
        check("issue #57：start() 固化的清单可由 plan() 读出",
                svc.plan() != null && svc.plan().count() == planA.count(),
                "实际 " + (svc.plan() == null ? "null" : svc.plan().count()));
        check("issue #57：第一个文件已进入传输", g.firstEntered.await(5, TimeUnit.SECONDS), "等待超时");
        svc.pause();
        check("issue #57：暂停轮次可用同一清单恢复", svc.canResumeWith(planA), "实际 false");
        check("issue #57：暂停轮次不可用不同清单恢复", !svc.canResumeWith(planB), "实际 true");
        check("issue #57：next=null 不可恢复", !svc.canResumeWith(null), "实际 true");
        g.releaseFirst.countDown();
        svc.cancel();
        check("issue #57：取消后 worker 在 5 秒内退出", svc.awaitWorker(5000), "等待超时");
        check("issue #57：轮次结束后 canResumeWith 为 false",
                !svc.canResumeWith(planA), "实际 true");
        check("issue #57：取消轮次收尾回调到达", r.done.await(5, TimeUnit.SECONDS), "等待超时");
        deleteTree(out);
    }

    /**
     * issue #58 一审 B-1：暂停必须真的从生产路径（{@link ImportService}）接到
     * {@link TransferProgress}，而不是只在自检里对 TransferProgress 直接注入时钟。
     *
     * 时间线（注入时钟，全程不真等）：
     *   t0     —— start；
     *   +1 s   —— 第一个文件传完（300 B），worker 停在边界并调用 pause；
     *   +101 s —— resume（也就是暂停了 100 s）；
     *   +102 s —— 第二个文件开始，此处断言速率与 ETA；
     *   +103 s —— 第二个文件传完（再 1 s 的实际传输）。
     *
     * 健康实现：+102 s 时 300 B / 实际 1 s = 300 B/s、ETA 1 s；+103 s 时 400 B / 实际 2 s = 200 B/s。
     * 删掉 pause(...)：{@code pauseRead} 等不到，且速率按 101 s 墙钟掉到约 2.97 B/s；
     * 删掉 resume(...)：暂停永不结束，+103 s 仍把后 1 s 扣掉，速率算出 400 B/s（期望 200 B/s）。
     */
    static void importServicePauseClockCases() throws Exception {
        // 暂停时钟只需要「两个文件依次传」，与照片单元聚合无关：
        // 单元 A 是 ARW 300 B（无同名 JPG，JPG_FIRST 档回退 ARW），单元 B 是 JPG 100 B。
        Catalog c = new Catalog();
        c.add(new Catalog.Entry(1, 0, Catalog.FMT_FOLDER, 0, "DCIM"));
        c.add(new Catalog.Entry(2, 1, Catalog.FMT_FOLDER, 0, "100MSDCF"));
        c.add(new Catalog.Entry(3, 2, Catalog.FMT_ARW, 300, "DSC02183.ARW"));
        c.add(new Catalog.Entry(4, 2, Catalog.FMT_JPEG, 100, "DSC02184.JPG"));
        c.build();
        Selection s = new Selection(c);
        s.setChecked(3, true);      // 单元 A：DSC02183.ARW（300 B，先）
        s.setChecked(4, true);      // 单元 B：DSC02184.JPG（100 B，后）
        ImportPlan plan = ImportPlan.of(c, s, PhotoWall.of(c).units(), PhotoWall.Export.JPG_FIRST);
        check("B-1 暂停时钟：用例清单恰为 2 个文件", plan.count() == 2, "实际 " + plan.count());

        long t0 = 1_000_000_000L;
        long pausedAt = t0 + 1_000_000_000L;          // 第一个文件实际传了 1 s
        long resumedAt = pausedAt + 100_000_000_000L; // 暂停 100 s
        long finalAt = resumedAt + 1_000_000_000L;    // 第二个文件又传了 1 s

        File out = Files.createTempDirectory("wiredraw-58-clock").toFile();
        ManualClock clock = new ManualClock(t0);
        TwoGateTransfer g = new TwoGateTransfer();
        FinishRecorder r = new FinishRecorder();
        ImportService svc = new ImportService(Log.NOOP, g, out, clock);

        svc.start(plan, r);
        check("B-1 暂停时钟：第一个文件已进入传输",
                g.firstEntered.await(5, TimeUnit.SECONDS), "等待超时");
        clock.set(pausedAt);
        svc.pause();
        g.releaseFirst.countDown();
        boolean pauseRecorded = clock.pauseRead.await(5, TimeUnit.SECONDS);
        check("B-1 暂停时钟：worker 停在文件边界后调用了 progress.pause(...)",
                pauseRecorded, "5 秒内没看到暂停记账（删掉 pause(...) 会在这里红）");

        clock.set(resumedAt);
        svc.resume();
        check("B-1 暂停时钟：resume 后第二个文件开始",
                g.secondEntered.await(5, TimeUnit.SECONDS), "等待超时");

        TransferProgress p = r.lastProgress;
        boolean firstDone = p != null && p.bytesDone() == 300 && p.filesDone() == 1;
        check("B-1 暂停时钟：暂停前完成的 300 B 已计入进度", firstDone,
                p == null ? "无进度" : p.bytesDone() + " B / " + p.filesDone() + " 个");
        check("B-1 暂停时钟：速率分母扣掉了暂停的 100 s（300 B / 实际 1 s = 300 B/s）",
                firstDone && Math.abs(p.bytesPerSec(resumedAt) - 300) < 0.001,
                p == null ? "无进度" : "实际 " + p.bytesPerSec(resumedAt));
        check("B-1 暂停时钟：ETA 用扣掉暂停后的速率（剩余 100 B / 300 B/s = 1 s）",
                firstDone && p.etaSeconds(resumedAt) == 1,
                p == null ? "无进度" : "实际 " + p.etaSeconds(resumedAt));

        clock.set(finalAt);
        g.releaseSecond.countDown();
        check("B-1 暂停时钟：本轮 5 秒内结束", r.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("B-1 暂停时钟：两个文件都完成",
                r.ok == 2 && r.failed == 0, "ok=" + r.ok + " failed=" + r.failed);
        TransferProgress end = r.lastProgress;
        boolean allDone = end != null && end.bytesDone() == 400;
        check("B-1 暂停时钟：resume 后恢复计时（400 B / 实际 2 s = 200 B/s）",
                allDone && Math.abs(end.bytesPerSec(finalAt) - 200) < 0.001,
                end == null ? "无进度" : "实际 " + end.bytesPerSec(finalAt));
        check("B-1 暂停时钟：全部传完 ETA 为 0",
                allDone && end.etaSeconds(finalAt) == 0,
                end == null ? "无进度" : "实际 " + end.etaSeconds(finalAt));
        deleteTree(out);
    }
}
