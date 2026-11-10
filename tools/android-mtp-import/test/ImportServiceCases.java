package com.rea.mtpimport;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.rea.mtpimport.TestSupport.*;
import static com.rea.mtpimport.Fixtures.*;
import static com.rea.mtpimport.TestFakes.*;
import static com.rea.mtpimport.SourceGuard.*;
import static com.rea.mtpimport.TempDirs.*;

/** 导入服务编排、轮末落盘与取消令牌的断言组（#318 从 SelfTest.java 拆出）。 */
final class ImportServiceCases {

    static void importServiceCases() throws Exception {
        Catalog c = sample();
        Selection s = new Selection(c);
        // DCIM 下 2 个照片单元：DSC02183（All 档取 JPG 100）、DSC02999（回退 ARW 50）。
        s.setChecked(1, true);
        ImportPlan plan = ImportPlan.of(c, s, PhotoWall.of(c).units(),
                PhotoWall.Export.JPG_FIRST);

        File out = Files.createTempDirectory("wiredraw-import").toFile();
        // 续传停用（#177）：只有真正产出成品的假传输才能让 Journal 记账可核对。
        FakeTransfer t1 = new FakeTransfer(out);
        FinishRecorder r1 = new FinishRecorder();
        List<String> r1Log = new ArrayList<>();
        new ImportService(r1Log::add, t1, out).start(plan, r1);
        check("ImportService：一轮在 5 秒内结束", r1.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("ImportService：2 个单元文件全部 OK（不再有「未实现」）",
                r1.ok == 2 && r1.skipped == 0 && r1.failed == 0,
                "ok=" + r1.ok + " skipped=" + r1.skipped + " failed=" + r1.failed);
        check("ImportService：summary 不再出现「未实现」",
                r1.summary != null && !r1.summary.contains("未实现"), "实际 " + r1.summary);
        check("ImportService：进度按成功文件累加字节",
                r1.lastProgress != null && r1.lastProgress.bytesDone() == 150
                        && r1.lastProgress.filesDone() == 2,
                r1.lastProgress == null ? "无进度"
                        : r1.lastProgress.bytesDone() + " / " + r1.lastProgress.filesDone());
        Journal j = new Journal();
        check("ImportService：成品落在 outDir 的镜像 relPath 处（PC / #50）",
                new File(out, "DCIM/100MSDCF/DSC02183.JPG").isFile()
                        && new File(out, "DCIM/DSC02999.ARW").isFile(),
                "实际 " + Arrays.toString(out.list()));
        check("ImportService：成功一轮不留 .part（续传停用，#177）",
                !new File(j.partPath(out.getAbsolutePath(), "DCIM/100MSDCF/DSC02183.JPG", 100)).exists()
                        && !new File(j.partPath(out.getAbsolutePath(), "DCIM/DSC02999.ARW", 50)).exists(),
                "实际 " + Arrays.toString(out.list()));
        check("ImportService：日志文件写出 import-journal.txt",
                new File(out, "import-journal.txt").isFile(),
                "实际 " + Arrays.toString(out.list()));

        // #20 裁决 B 的轮级断言：源码守卫只能防「名字被删」，这里跑真一轮确认不产索引文件。
        check("ImportService：一轮结束不产 imported-index.txt 与 .tmp（#20 停写）",
                !new File(out, "imported-index.txt").exists()
                        && !new File(out, "imported-index.txt.tmp").exists(),
                "实际 " + Arrays.toString(out.list()));
        check("ImportService：一轮日志不含「已导入索引已重建」（#20 停写）",
                r1Log.stream().noneMatch(line -> line.contains("已导入索引已重建")),
                "实际日志：\n" + String.join("\n", r1Log));

        // 第二轮：同一目录 + 同一 plan，成品与账本都在，应该全部跳过，不再调 transfer
        FakeTransfer t2 = new FakeTransfer();
        FinishRecorder r2 = new FinishRecorder();
        new ImportService(Log.NOOP, t2, out).start(plan, r2);
        check("ImportService：第二轮 5 秒内结束", r2.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("ImportService：第二轮全部 SKIPPED 且不再调 transfer",
                r2.ok == 0 && r2.skipped == 2 && r2.failed == 0 && t2.calls.isEmpty(),
                "ok=" + r2.ok + " skipped=" + r2.skipped + " calls=" + t2.calls.size());

        // 全失败：新目录，不能把失败冒充成完成
        File badOut = Files.createTempDirectory("wiredraw-import-bad").toFile();
        FakeTransfer t3 = new FakeTransfer();
        t3.failAll = true;
        FinishRecorder r3 = new FinishRecorder();
        new ImportService(Log.NOOP, t3, badOut).start(plan, r3);
        check("ImportService：全失败一轮结束", r3.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("ImportService：全失败时 ok=0 / failed=2，不冒充完成",
                r3.ok == 0 && r3.failed == 2 && r3.skipped == 0,
                "ok=" + r3.ok + " failed=" + r3.failed + " skipped=" + r3.skipped);
        // PD / issue #50：重试必须有上限。全失败时每个文件恰好尝试 2 次（硬编码，
        // 不引用 MAX_FILE_ATTEMPTS，避免「断言跟着实现走」的自指）。
        check("PD 重试：全失败时每个文件恰好尝试 2 次（有上限）",
                t3.calls.size() == plan.count() * 2,
                "calls=" + t3.calls.size() + " 期望=" + (plan.count() * 2));

        // PD：首次失败、第二次成功的文件仍算完成，且尝试次数有上限。
        File flakyOut = Files.createTempDirectory("wiredraw-import-flaky").toFile();
        FakeTransfer t4 = new FakeTransfer(FakeTransfer.Mode.FLUKY);
        FinishRecorder r4 = new FinishRecorder();
        new ImportService(Log.NOOP, t4, flakyOut).start(plan, r4);
        check("PD 重试：偶发失败一轮在 5 秒内结束", r4.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("PD 重试：首次失败、第二次成功仍算完成",
                r4.ok == 2 && r4.failed == 0 && r4.skipped == 0,
                "ok=" + r4.ok + " failed=" + r4.failed);
        check("PD 重试：每个文件恰好尝试 2 次（首次 + 1 次同步重试）",
                t4.calls.size() == plan.count() * 2,
                "calls=" + t4.calls.size());

        // #177：续传停用。首次失败即使已落 40 字节，第二次 attempt 也必须从 0 重传，
        // 否则「忽略 offset」的 reader 会把 0 前缀追加到残留后面，尺寸对、内容错。
        File partialOut = Files.createTempDirectory("wiredraw-import-partial").toFile();
        FakeTransfer t5 = new FakeTransfer(FakeTransfer.Mode.FLUKY);
        t5.firstFailureBytes = 40L;
        FinishRecorder r5 = new FinishRecorder();
        new ImportService(Log.NOOP, t5, partialOut).start(plan, r5);
        check("PD 重试：落部分字节的一轮在 5 秒内结束", r5.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("PD 重试：落部分字节后第二次仍算完成",
                r5.ok == 2 && r5.failed == 0 && r5.skipped == 0,
                "ok=" + r5.ok + " failed=" + r5.failed + " skipped=" + r5.skipped);
        boolean resumesOk = t5.resumeFroms.size() == plan.count() * 2;
        for (int i = 0; resumesOk && i < t5.resumeFroms.size(); i++) {
            resumesOk = t5.resumeFroms.get(i) == 0L;
        }
        check("PD 重试：每次 attempt 的 resumeFrom 都是 0（续传停用，#177）",
                resumesOk, "实际 " + t5.resumeFroms);

        // PD / B-2：首次 FAILED 同时已请求取消 → 归为已取消，不计失败、也不再重试。
        File cancelOut = Files.createTempDirectory("wiredraw-import-cancel-fail").toFile();
        CancelOnFailTransfer t6 = new CancelOnFailTransfer();
        ImportService svc6 = new ImportService(Log.NOOP, t6, cancelOut);
        t6.onFirstCall = svc6::cancel;
        FinishRecorder r6 = new FinishRecorder();
        svc6.start(plan, r6);
        check("PD 重试：取消与失败竞态一轮在 5 秒内结束",
                r6.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("PD 重试：FAILED 同时已请求取消归为 CANCELLED，failed 不增",
                r6.cancelled && r6.failed == 0 && r6.ok == 0,
                "cancelled=" + r6.cancelled + " failed=" + r6.failed + " ok=" + r6.ok);
        check("PD 重试：取消优先，不再发起同步重试",
                t6.calls.size() == 1, "calls=" + t6.calls.size());

        deleteTree(out);
        deleteTree(badOut);
        deleteTree(flakyOut);
        deleteTree(partialOut);
        deleteTree(cancelOut);
    }

    /**
     * #20 裁决 B：`imported-index.txt` 有写方、无读方，**先停写**。
     *
     * 停写的完成标准是「没有读方就不写」：轮末写入调用点、纯 JDK 类本身与
     * `pureJdkClasses` 清单项必须一起消失。将来要恢复索引，读方与写方必须
     * 同一个 PR 交付，并由界面真正用上。
     */
    static void indexStoppedCases() throws Exception {
        File src = new File(System.getProperty("user.dir"), "src/com/rea/mtpimport");
        String round = readSourceOrNull(new File(src, "RoundFiles.java"));
        String service = readSourceOrNull(new File(src, "ImportService.java"));
        String gradle = readSourceOrNull(new File(System.getProperty("user.dir"), "gradle/self-test.gradle.kts"));
        check("已导入索引：写入方 rebuildIndex 已移除（#20 裁决 B）",
                round != null && !round.contains("rebuildIndex"),
                round == null ? "读不到 RoundFiles.java" : "仍有 rebuildIndex");
        check("已导入索引：ImportService 不再调写入方（#20 裁决 B）",
                service != null && !service.contains("rebuildIndex"),
                service == null ? "读不到 ImportService.java" : "仍调用 rebuildIndex");
        check("已导入索引：纯 JDK 类已删除（#20 裁决 B）",
                !new File(src, "ImportedIndex.java").exists(),
                "ImportedIndex.java 仍在");
        check("已导入索引：纯 JDK 清单不再列它（#20 裁决 B）",
                gradle != null && !gradle.contains("ImportedIndex.java"),
                gradle == null ? "读不到 gradle/self-test.gradle.kts" : "清单仍列 ImportedIndex.java");
    }

    // ---------------- 取消令牌与暂停/取消/恢复语义（PB / issue #50） ----------------

    static void cancelTokenCases() {
        CancelToken token = new CancelToken();
        check("CancelToken：初始未取消", !token.isCancelled(), "实际 true");
        token.cancel();
        check("CancelToken：cancel() 后置位", token.isCancelled(), "实际 false");

        CancelToken late = new CancelToken();
        late.cancel();
        AtomicInteger lateRuns = new AtomicInteger();
        late.setOnCancel(lateRuns::incrementAndGet);
        check("CancelToken：取消后注册的动作立即执行",
                lateRuns.get() == 1, "实际 " + lateRuns.get());

        CancelToken once = new CancelToken();
        AtomicInteger runs = new AtomicInteger();
        once.setOnCancel(runs::incrementAndGet);
        once.cancel();
        once.cancel();
        check("CancelToken：取消动作只执行一次",
                runs.get() == 1, "实际 " + runs.get());

        CancelToken cleared = new CancelToken();
        AtomicInteger clearedRuns = new AtomicInteger();
        cleared.setOnCancel(clearedRuns::incrementAndGet);
        cleared.clearOnCancel();
        cleared.cancel();
        check("CancelToken：clearOnCancel 后不再执行旧动作",
                clearedRuns.get() == 0, "实际 " + clearedRuns.get());
    }
}
