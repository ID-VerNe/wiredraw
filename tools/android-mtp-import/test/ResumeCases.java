package com.rea.mtpimport;

import java.io.File;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.rea.mtpimport.TestSupport.*;
import static com.rea.mtpimport.Fixtures.*;
import static com.rea.mtpimport.TestFakes.*;
import static com.rea.mtpimport.TempDirs.*;

/** 续传停用、续传进度与半成品残留的断言组（#318 从 SelfTest.java 拆出）。 */
final class ResumeCases {

    static void resumeDisabledCases() throws Exception {
        Catalog c = sample();
        Selection s = new Selection(c);
        s.setChecked(3, true);      // DSC02183 单元；用 RAW 档载荷钉住 ARW（300 字节）
        ImportPlan plan = ImportPlan.of(c, s, unitsWithArw(PhotoWall.of(c)),
                PhotoWall.Export.RAW);
        check("续传停用：用例清单恰为 1 个文件", plan.count() == 1, "实际 " + plan.count());
        String rel = "DCIM/100MSDCF/DSC02183.ARW";

        // 1) 部分残留（40 字节错误前缀）→ 必须从 0 重传，成品字节与基准完全一致。
        File out = Files.createTempDirectory("wiredraw-177-residue").toFile();
        Journal j = new Journal();
        File residue = new File(j.partPath(out.getAbsolutePath(), rel, 300));
        writeSizedBytes(residue, 40, (byte) 0x55);
        FakeTransfer t1 = new FakeTransfer(300);
        FinishRecorder r1 = new FinishRecorder();
        new ImportService(Log.NOOP, t1, out).start(plan, r1);
        check("续传停用：部分残留一轮在 5 秒内结束",
                r1.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("续传停用：编排层传给传输层的起点恒为 0（#177）",
                t1.resumeFroms.size() == 1 && t1.resumeFroms.get(0) == 0L,
                "实际 " + t1.resumeFroms);
        check("续传停用：部分残留不会产出「尺寸对、内容错」的坏文件",
                r1.ok == 1 && r1.failed == 0 && payloadMatches(new File(out, rel), 300),
                "ok=" + r1.ok + " failed=" + r1.failed + " 字节对不上=" + !payloadMatches(new File(out, rel), 300));
        check("续传停用：部分残留被删除，不留在 outDir",
                !residue.exists(), "仍存在：" + residue);
        deleteTree(out);

        // 2) 满尺寸残留 → 不得走旧的 `from == size` 直通；必须删掉重写。
        File fullOut = Files.createTempDirectory("wiredraw-177-full").toFile();
        File fullResidue = new File(j.partPath(fullOut.getAbsolutePath(), rel, 300));
        writeSizedBytes(fullResidue, 300, (byte) 0x5A);
        FakeTransfer t2 = new FakeTransfer(300);
        FinishRecorder r2 = new FinishRecorder();
        new ImportService(Log.NOOP, t2, fullOut).start(plan, r2);
        check("续传停用：满尺寸残留一轮在 5 秒内结束",
                r2.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("续传停用：满尺寸残留同样从 0 重传（起点不为 size）",
                t2.resumeFroms.size() == 1 && t2.resumeFroms.get(0) == 0L,
                "实际 " + t2.resumeFroms);
        check("续传停用：满尺寸残渣不会被改名当成品（尺寸凑够不算完成）",
                r2.ok == 1 && r2.failed == 0 && payloadMatches(new File(fullOut, rel), 300),
                "ok=" + r2.ok + " failed=" + r2.failed);
        deleteTree(fullOut);

        // 3) 成品 + 账本都在 → 跳过；删掉成品后不得只看账本就跳过（#177）。
        File doneOut = Files.createTempDirectory("wiredraw-177-done").toFile();
        FakeTransfer t3 = new FakeTransfer(doneOut);
        FinishRecorder r3 = new FinishRecorder();
        new ImportService(Log.NOOP, t3, doneOut).start(plan, r3);
        check("续传停用：正常一轮产出一个成品",
                r3.done.await(5, TimeUnit.SECONDS) && r3.ok == 1,
                "ok=" + r3.ok + " failed=" + r3.failed);
        FakeTransfer t4 = new FakeTransfer();
        FinishRecorder r4 = new FinishRecorder();
        new ImportService(Log.NOOP, t4, doneOut).start(plan, r4);
        check("续传停用：成品与账本都在时按 Skipped 跳过",
                r4.done.await(5, TimeUnit.SECONDS) && r4.skipped == 1 && t4.calls.isEmpty(),
                "skipped=" + r4.skipped + " calls=" + t4.calls.size());
        File dest = new File(doneOut, rel);
        boolean removed = dest.delete();
        check("续传停用：能删掉成品以模拟文件丢失", removed, "delete 失败");
        FakeTransfer t5 = new FakeTransfer();
        FinishRecorder r5 = new FinishRecorder();
        new ImportService(Log.NOOP, t5, doneOut).start(plan, r5);
        check("续传停用：账本记完成但成品缺失 → 重新导入，不静默跳过",
                r5.done.await(5, TimeUnit.SECONDS)
                        && r5.skipped == 0 && t5.calls.size() == 1,
                "skipped=" + r5.skipped + " calls=" + t5.calls.size());

        // 4) 账本完成 + 成品在但尺寸不符 → 也不得跳过（尺寸对账必须对到实际文件）。
        FinishRecorder r6 = new FinishRecorder();
        new ImportService(Log.NOOP, new FakeTransfer(doneOut), doneOut).start(plan, r6);
        check("续传停用：重建成品以便覆盖成错误尺寸",
                r6.done.await(5, TimeUnit.SECONDS) && r6.ok == 1 && r6.skipped == 0,
                "ok=" + r6.ok + " skipped=" + r6.skipped);
        writeSizedBytes(dest, 299, (byte) 0x11);
        FakeTransfer t7 = new FakeTransfer();
        FinishRecorder r7 = new FinishRecorder();
        new ImportService(Log.NOOP, t7, doneOut).start(plan, r7);
        check("续传停用：成品尺寸不符 → 重新导入，不静默跳过",
                r7.done.await(5, TimeUnit.SECONDS)
                        && r7.skipped == 0 && t7.calls.size() == 1,
                "skipped=" + r7.skipped + " calls=" + t7.calls.size());
        deleteTree(doneOut);

        // 5) 同尺寸成品已在、但没有账本 → 首轮的 SKIPPED 必须落账，否则下一轮继续整文件重下再丢弃
        //    （#177 复审：账本只在 OK 分支写，SKIPPED 漏写会让该文件永不收敛）。
        File sameOut = Files.createTempDirectory("wiredraw-177-samesize").toFile();
        writeSizedBytes(new File(sameOut, rel), 300, (byte) 0x33);
        SameSizeSkipTransfer t8 = new SameSizeSkipTransfer();
        FinishRecorder r8 = new FinishRecorder();
        new ImportService(Log.NOOP, t8, sameOut).start(plan, r8);
        check("续传停用：目标同尺寸命中时记 SKIPPED",
                r8.done.await(5, TimeUnit.SECONDS) && r8.skipped == 1 && t8.calls == 1,
                "skipped=" + r8.skipped + " calls=" + t8.calls);
        FakeTransfer t9 = new FakeTransfer();
        FinishRecorder r9 = new FinishRecorder();
        new ImportService(Log.NOOP, t9, sameOut).start(plan, r9);
        check("续传停用：SKIPPED 必须落账，第二轮不再重下同尺寸成品（#177 复审）",
                r9.done.await(5, TimeUnit.SECONDS) && r9.skipped == 1 && t9.calls.isEmpty(),
                "skipped=" + r9.skipped + " calls=" + t9.calls.size());
        deleteTree(sameOut);
    }

    // ---------------- 文件内进度（issue #70） ----------------

    /**
     * issue #70 的两条集成用例（#177 续传停用后按「从 0 重传」改口径）：
     *
     * 1) **旧半成品不计入进度起点**：磁盘上虽有 40 字节 `.part`，首个进度事件必须是 0，
     *    重传期间能看到文件内中间值——只有「文件结束才一次加满」或「bytesDone() 漏算当前文件」
     *    都会让这条红。
     * 2) **同一文件失败后重试不得双计**：重试从 0 开始时总字节必须仍是 0（失败时已回滚）；
     *    `beginFile` 把失败前落盘/已计字节再加一遍会变成 40，这条立刻红。
     */
    static void resumeProgressCases() throws Exception {
        // 1) 旧半成品 40 B：单个 ARW 300 B，本轮仍须从 0 重传。
        Catalog c = sample();
        Selection s = new Selection(c);
        s.setChecked(3, true);
        ImportPlan resumePlan = ImportPlan.of(c, s, unitsWithArw(PhotoWall.of(c)),
                PhotoWall.Export.RAW);
        check("文件内进度：续传用例恰为 1 个文件", resumePlan.count() == 1,
                "实际 " + resumePlan.count());

        File out = Files.createTempDirectory("wiredraw-70-resume").toFile();
        Journal j = new Journal();
        writeSizedFile(new File(j.partPath(out.getAbsolutePath(),
                "DCIM/100MSDCF/DSC02183.ARW", 300)), 40);

        ResumeReportingTransfer t1 = new ResumeReportingTransfer(false, 60);
        FinishRecorder r1 = new FinishRecorder();
        new ImportService(Log.NOOP, t1, out).start(resumePlan, r1);
        check("文件内进度：续传一轮在 5 秒内结束", r1.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("文件内进度：旧半成品不抬起点，首个进度事件是 0（#177）",
                !r1.progressBytes.isEmpty() && r1.progressBytes.get(0) == 0L,
                "实际 " + r1.progressBytes);
        boolean sawMid = false;
        for (long b : r1.progressBytes) {
            if (b > 0L && b < 300L) sawMid = true;
        }
        check("文件内进度：重传期间能看到文件内中间值（0 < x < 300）", sawMid,
                "实际 " + r1.progressBytes);
        check("文件内进度：重传完成后总字节与文件计数正确",
                r1.lastProgress != null && r1.lastProgress.bytesDone() == 300
                        && r1.lastProgress.filesDone() == 1,
                r1.lastProgress == null ? "无进度"
                        : r1.lastProgress.bytesDone() + " / " + r1.lastProgress.filesDone());
        deleteTree(out);

        // 2) 同一文件失败后重试：重试起点不得把已落盘字节再计一遍。
        Catalog c2 = sample();
        Selection s2 = new Selection(c2);
        s2.setChecked(1, true);
        ImportPlan retryPlan = ImportPlan.of(c2, s2, PhotoWall.of(c2).units(),
                PhotoWall.Export.JPG_FIRST);
        check("文件内进度：重试用例恰为 2 个文件", retryPlan.count() == 2,
                "实际 " + retryPlan.count());

        File out2 = Files.createTempDirectory("wiredraw-70-retry").toFile();
        ResumeReportingTransfer t2 = new ResumeReportingTransfer(true, 60);
        FinishRecorder r2 = new FinishRecorder();
        new ImportService(Log.NOOP, t2, out2).start(retryPlan, r2);
        check("文件内进度：重试的文件刚进入第二次尝试",
                t2.retryEntered.await(5, TimeUnit.SECONDS), "等待超时");
        long atRetry = r2.lastProgress == null ? -1L : r2.lastProgress.bytesDone();
        check("文件内进度：重试不得双计（第二次尝试刚上报起点 0 时总字节仍是 0）",
                atRetry == 0L, "实际 " + atRetry);
        t2.releaseRetry.countDown();
        check("文件内进度：重试一轮在 5 秒内结束", r2.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("文件内进度：首次失败后重试仍算完成",
                r2.ok == 2 && r2.failed == 0, "ok=" + r2.ok + " failed=" + r2.failed);
        check("文件内进度：重试的第二次尝试从 0 重传（续传停用，#177）",
                t2.resumeFroms.size() >= 2 && t2.resumeFroms.get(1) == 0L,
                "实际 " + t2.resumeFroms);
        check("文件内进度：每个文件完成点的总字节等于累计大小（100、150）",
                r2.bytesAtFileFinish.size() == 2
                        && r2.bytesAtFileFinish.get(0) == 100L
                        && r2.bytesAtFileFinish.get(1) == 150L,
                "实际 " + r2.bytesAtFileFinish);
        check("文件内进度：全部完成后总字节等于 plan 总量",
                r2.lastProgress != null && r2.lastProgress.bytesDone() == 150,
                r2.lastProgress == null ? "无进度" : "实际 " + r2.lastProgress.bytesDone());
        deleteTree(out2);
    }

    // ---------------- 无主半成品清理（issue #52） ----------------

    /**
     * 钉住清理判据（#177 续传停用后改口径）：保留集恒空。
     *
     * 续传停用后不存在「合法半成品」：
     * - 同 `relPath`、同 size 的旧 `.part` → 不可信，必须删（不再当作可续传前缀）；
     * - 同 `relPath`、旧 size → 必须删（#52 主症状）；
     * - `relPath` 已完全不在目录 → 必须删（对象已不存在，接不上）。
     *
     * 干扰项覆盖账本、索引、`.tmp`、没有 size 段的 `.part`、子目录里的 `.part`；
     * 另钉住 `keepNames == null` 的安全语义：一个都不删。
     *
     * 清理开关另钉住两个方向：枚举完整 → 照常清；枚举不完整（丢弃对象 / 存储无句柄 / 取消）
     * → 一个都不清，并留一行含计数的原因。完整性标志与计数由 {@link ScanResult} 一次算出，
     * {@link CatalogScanner} 只负责填数（Android 侧，JVM 单测覆盖不到的那部分靠代码走查 + 真机）。
     */
    static void partResidueCases() throws Exception {
        File out = Files.createTempDirectory("wiredraw-part-residue").toFile();
        Catalog c = sample();
        Set<String> keep = PartResidue.keepNames(c.all());
        Journal j = new Journal();

        String keepSize = j.partPath(out.getAbsolutePath(), "DCIM/100MSDCF/DSC02183.ARW", 300);
        writeSizedFile(new File(keepSize), 40);

        // 关键方向 1：同 relPath、旧 size —— 目录里已没有这个 size 的对象，必须删。
        String staleSize = j.partPath(out.getAbsolutePath(), "DCIM/100MSDCF/DSC02183.ARW", 299);
        writeSizedFile(new File(staleSize), 40);

        // 关键方向 2：relPath 已完全不在目录 —— 无主，必须删。
        String gone = j.partPath(out.getAbsolutePath(), "DCIM/100MSDCF/DSC99999.ARW", 300);
        writeSizedFile(new File(gone), 40);

        writeSizedFile(new File(out, "import-journal.txt"), 5);
        // 存量 `imported-index.txt`（#20 停写前留下的普通文件）：它不是半成品，必须原样保留。
        writeSizedFile(new File(out, "imported-index.txt"), 5);
        writeSizedFile(new File(out, "imported-index.txt.tmp"), 5);
        writeSizedFile(new File(out, "plain.part"), 5);
        writeSizedFile(new File(out, "DCIM/stray.300.part"), 5);

        check("无主半成品：续传停用后保留集恒空（#177）",
                keep.isEmpty(), "keep=" + keep);
        check("无主半成品：keepNames(null) 返回空集，不退化成全删",
                PartResidue.keepNames(null).isEmpty(), "返回非空");
        check("无主半成品：keep 集为 null 时 orphans 为空",
                PartResidue.orphans(out, null).isEmpty(), "返回非空");

        // issue #52：完整性开关。丢弃过对象 / 存储取句柄失败 / 取消都算不完整。
        ScanResult ok = ScanResult.of(c, 3, 0, 0, false);
        ScanResult dropped = ScanResult.of(c, 3, 1, 0, false);
        ScanResult noHandles = ScanResult.of(c, 3, 0, 1, false);
        ScanResult cancelled = ScanResult.of(c, 3, 0, 0, true);
        check("扫描完整性：无丢弃无失败 → complete",
                ok.complete && ok.scanned == 3 && ok.dropped == 0, "未判为完整或计数不对");
        check("扫描完整性：丢弃对象 → 不完整，且计数对得上",
                !dropped.complete && dropped.scanned == 3 && dropped.dropped == 1,
                "complete=" + dropped.complete + " scanned=" + dropped.scanned + " dropped=" + dropped.dropped);
        check("扫描完整性：存储取句柄失败 → 不完整",
                !noHandles.complete && noHandles.missingHandles == 1, "未判为不完整");
        check("扫描完整性：取消 → 不完整", !cancelled.complete, "取消仍判为完整");

        // 关键方向：枚举不完整 → 一个都不清，并留一行给用户看的原因。
        RecordingLog skipLog = new RecordingLog();
        int skipped = PartResidue.cleanIfComplete(out, keep, dropped, skipLog);
        check("无主半成品：枚举不完整 → 一个都不清",
                skipped == 0 && new File(staleSize).exists() && new File(gone).exists(),
                "删了 " + skipped + " 个或文件已不在");
        check("无主半成品：跳过清理要留原因（含丢弃计数）",
                skipLog.contains("枚举不完整") && skipLog.contains("丢弃 1 个"),
                "日志：" + skipLog.lines);
        check("无主半成品：没有扫描结果 → 不清",
                PartResidue.cleanIfComplete(out, keep, null, Log.NOOP) == 0
                        && new File(staleSize).exists(), "null 结果时删了文件");

        RecordingLog cleanLog = new RecordingLog();
        int cleaned = PartResidue.cleanIfComplete(out, keep, ok, cleanLog);
        check("无主半成品：枚举完整 → 清理全部 .part", cleaned == 3 && cleanLog.contains("已清理无主半成品"),
                "实际 " + cleaned);
        check("无主半成品：同 relPath、旧 size → 被删（#52 主症状）",
                !new File(staleSize).exists(), "仍存在：" + staleSize);
        check("无主半成品：relPath 已不在目录 → 被删",
                !new File(gone).exists(), "仍存在：" + gone);
        check("无主半成品：relPath + size 都能对上也不保留（不再续传，#177）",
                !new File(keepSize).exists(), "仍存在：" + keepSize);
        check("无主半成品：账本 / 存量索引 / .tmp / 无 size 段的 .part 不被动",
                new File(out, "import-journal.txt").isFile()
                        && new File(out, "imported-index.txt").isFile()
                        && new File(out, "imported-index.txt.tmp").isFile()
                        && new File(out, "plain.part").isFile(),
                "干扰项被删");
        check("无主半成品：子目录里的 .part 不被动（半成品只平铺在 outDir）",
                new File(out, "DCIM/stray.300.part").isFile(), "子目录项被删");

        // 命名口径（#210）：生成侧与识别侧必须同一口径；用户自己的 `.part` 不算半成品。
        String generated = new File(j.partPath(out.getAbsolutePath(),
                "DCIM/100MSDCF/DSC02183.ARW", 24389632)).getName();
        check("半成品命名：partPath 生成的名字必被 isPartName 认出（#210）",
                PartResidue.isPartName(generated), "生成=" + generated);
        check("半成品命名：不是本工具命名的 .part 一律不算半成品（#210）",
                !PartResidue.isPartName("plain.part")
                        && !PartResidue.isPartName("x.12a.part")
                        && !PartResidue.isPartName("x..part")
                        && !PartResidue.isPartName("x.300.part.bak"),
                "判据放宽了");

        deleteTree(out);
    }
}
