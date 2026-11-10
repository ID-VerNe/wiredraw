package com.rea.mtpimport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static com.rea.mtpimport.TestSupport.*;

/** 传输进度、文件内进度与断点日志的断言组（#318 从 SelfTest.java 拆出）。 */
final class ProgressCases {

    // ---------------- TransferProgress ----------------

    static void progressCases() {
        TransferProgress p = new TransferProgress(1000, 2);
        long t0 = 1_000_000_000L;
        p.start(t0);

        p.onBytes(250);
        check("fraction 按已传/总量计算",
                Math.abs(p.fraction() - 0.25) < 1e-9, "实际 " + p.fraction());

        p.onBytes(5000);
        check("超出总量的字节被钳到 1.0（不出现 105%）",
                Math.abs(p.fraction() - 1.0) < 1e-9 && p.bytesDone() == 1000,
                "fraction=" + p.fraction() + " done=" + p.bytesDone());

        p.onBytes(-999);
        check("回退的负数不减少已完字节", p.bytesDone() == 1000, "实际 " + p.bytesDone());

        TransferProgress zero = new TransferProgress(0, 0);
        zero.start(t0);
        check("总量为 0 不除零，fraction 返回 0.0",
                Math.abs(zero.fraction()) < 1e-9, "实际 " + zero.fraction());

        TransferProgress speed = new TransferProgress(10_000_000, 1);
        speed.start(t0);
        speed.onBytes(5_000_000);
        check("bytesPerSec 用注入时钟：5 MB / 1 s = 5 MB/s",
                Math.abs(speed.bytesPerSec(t0 + 1_000_000_000L) - 5_000_000) < 1.0,
                "实际 " + speed.bytesPerSec(t0 + 1_000_000_000L));

        check("etaSeconds = 剩余 / 速度，向上取整",
                speed.etaSeconds(t0 + 1_000_000_000L) == 1,
                "实际 " + speed.etaSeconds(t0 + 1_000_000_000L));

        // 剩余 7 MB / 3 MB/s = 2.33 s：向上取整应为 3，截断会得 2，用来钉住 ceil
        TransferProgress frac = new TransferProgress(10_000_000, 1);
        frac.start(t0);
        frac.onBytes(3_000_000);
        check("etaSeconds 对非整数秒向上取整（2.33 s → 3 s）",
                frac.etaSeconds(t0 + 1_000_000_000L) == 3,
                "实际 " + frac.etaSeconds(t0 + 1_000_000_000L));

        TransferProgress stalled = new TransferProgress(100, 1);
        stalled.start(t0);
        check("速度为 0 时 eta 返回 -1 而不是无穷大",
                stalled.etaSeconds(t0) == -1, "实际 " + stalled.etaSeconds(t0));

        // ---------------- #58：暂停时长不得计入速率/ETA ----------------
        // 场景：10 MB 已传、实际传输 1 s、随后暂停 100 s。
        // 修复前分母是 101 s（≈0.099 MB/s）；修复后应按实际传输的 1 s 算（10 MB/s）。
        long pauseT0 = 1_000_000_000L;
        long pauseEnd = pauseT0 + 101_000_000_000L;

        TransferProgress paused = new TransferProgress(20_000_000, 1);
        paused.start(pauseT0);
        paused.onBytes(10_000_000);
        paused.pause(pauseT0 + 1_000_000_000L);
        paused.resume(pauseEnd);
        check("暂停 100 s 后速率仍按实际传输 1 s 计（10 MB/s）",
                Math.abs(paused.bytesPerSec(pauseEnd) - 10_000_000) < 1.0,
                "实际 " + paused.bytesPerSec(pauseEnd));
        check("暂停 100 s 后 ETA 与不暂停一致（剩余 10 MB / 10 MB/s = 1 s）",
                paused.etaSeconds(pauseEnd) == 1,
                "实际 " + paused.etaSeconds(pauseEnd));

        TransferProgress duringPause = new TransferProgress(20_000_000, 1);
        duringPause.start(pauseT0);
        duringPause.onBytes(10_000_000);
        duringPause.pause(pauseT0 + 1_000_000_000L);
        check("暂停中读取速率不随暂停时长下降",
                Math.abs(duringPause.bytesPerSec(pauseT0 + 51_000_000_000L) - 10_000_000) < 1.0,
                "实际 " + duringPause.bytesPerSec(pauseT0 + 51_000_000_000L));

        TransferProgress doublePause = new TransferProgress(20_000_000, 1);
        doublePause.start(pauseT0);
        doublePause.onBytes(10_000_000);
        doublePause.pause(pauseT0 + 1_000_000_000L);
        doublePause.pause(pauseT0 + 6_000_000_000L);
        doublePause.resume(pauseEnd);
        check("重复 pause 只认第一次，不把暂停拆成两段重复扣",
                Math.abs(doublePause.bytesPerSec(pauseEnd) - 10_000_000) < 1.0,
                "实际 " + doublePause.bytesPerSec(pauseEnd));

        TransferProgress noPair = new TransferProgress(20_000_000, 1);
        noPair.start(pauseT0);
        noPair.onBytes(5_000_000);
        noPair.resume(pauseT0 + 2_000_000_000L);
        check("没有 pause 的 resume 是 no-op（5 MB / 2 s）",
                Math.abs(noPair.bytesPerSec(pauseT0 + 2_000_000_000L) - 2_500_000) < 1.0,
                "实际 " + noPair.bytesPerSec(pauseT0 + 2_000_000_000L));

        // 第二轮 start 必须清掉上一轮的暂停累计，否则新一轮会被上一轮的 100 s 拖成负时长。
        TransferProgress restarted = new TransferProgress(20_000_000, 1);
        restarted.start(pauseT0);
        restarted.onBytes(10_000_000);
        restarted.pause(pauseT0 + 1_000_000_000L);
        restarted.resume(pauseEnd);
        long secondStart = pauseT0 + 200_000_000_000L;
        restarted.start(secondStart);
        restarted.onBytes(10_000_000);
        check("start() 重置暂停累计（第二轮按自己的 1 s 计时）",
                Math.abs(restarted.bytesPerSec(secondStart + 1_000_000_000L) - 10_000_000) < 1.0,
                "实际 " + restarted.bytesPerSec(secondStart + 1_000_000_000L));
    }

    // ---------------- 文件内进度（issue #70） ----------------

    /**
     * 文件内进度的记账单元：已完成字节（doneBytes）与当前文件字节分开，bytesDone() 是两者之和。
     *
     * 关键两条：
     * - `beginFile` 是**重设**当前文件，不是往已完成字节里再加一遍（重试不得双计）；
     * - `onFileProgress` 收**绝对偏移**，回退被忽略、超出文件大小被钳。
     */
    static void progressFileCases() {
        long t0 = 1_000_000_000L;

        TransferProgress p = new TransferProgress(200, 2);
        p.start(t0);
        p.onBytes(100);
        p.beginFile(40, 100);
        check("文件内进度：beginFile 把已落盘的 40 字节计入总量（100 + 40 = 140）",
                p.bytesDone() == 140, "实际 " + p.bytesDone());

        p.onFileProgress(60);
        check("文件内进度：onFileProgress 按绝对偏移上报（100 + 60 = 160）",
                p.bytesDone() == 160, "实际 " + p.bytesDone());

        p.onFileProgress(50);
        check("文件内进度：绝对偏移回退被忽略（160 不降到 150）",
                p.bytesDone() == 160, "实际 " + p.bytesDone());

        p.beginFile(40, 100);
        check("文件内进度：同一文件重新 beginFile 是重设而不是累加（仍为 140）",
                p.bytesDone() == 140, "实际 " + p.bytesDone());

        p.onFileProgress(60);
        p.commitFile(100);
        check("文件内进度：commitFile 只按 size 记一次，不叠加当前文件字节（总 200）",
                p.bytesDone() == 200 && p.filesDone() == 1,
                "bytes=" + p.bytesDone() + " files=" + p.filesDone());

        TransferProgress rb = new TransferProgress(200, 2);
        rb.start(t0);
        rb.onBytes(100);
        rb.beginFile(40, 100);
        rb.onFileProgress(80);
        rb.rollbackFile();
        check("文件内进度：失败回退只丢掉当前文件，保留已完成部分（100）",
                rb.bytesDone() == 100, "实际 " + rb.bytesDone());

        TransferProgress cl = new TransferProgress(200, 2);
        cl.start(t0);
        cl.beginFile(0, 100);
        cl.onFileProgress(120);
        check("文件内进度：当前文件进度被钳在 size（120 → 100）",
                cl.bytesDone() == 100, "实际 " + cl.bytesDone());
        cl.onFileProgress(-5);
        check("文件内进度：负的绝对偏移被忽略（仍为 100）",
                cl.bytesDone() == 100, "实际 " + cl.bytesDone());
    }

    // ---------------- Journal ----------------

    static void journalCases() throws RuntimeException {
        Journal j = new Journal();
        j.markDone(12345, 7340032, 24389632, "DCIM/100MSDCF/DSC02183.ARW");
        j.markDone(22222, 1024, 1024, "DCIM/100MSDCF/DSC02184.ARW");

        check("doneBytes(relPath,size) 记住已落盘字节",
                j.doneBytes("DCIM/100MSDCF/DSC02183.ARW", 24389632) == 7340032,
                "实际 " + j.doneBytes("DCIM/100MSDCF/DSC02183.ARW", 24389632));
        check("未知路径的 doneBytes 为 0",
                j.doneBytes("DCIM/100MSDCF/DSC99999.ARW", 1024) == 0,
                "实际 " + j.doneBytes("DCIM/100MSDCF/DSC99999.ARW", 1024));
        check("isComplete(relPath,size)：size<=0 一律不算完成",
                !j.isComplete("DCIM/100MSDCF/DSC02184.ARW", 0),
                "实际 " + j.isComplete("DCIM/100MSDCF/DSC02184.ARW", 0));
        check("isComplete(relPath,size)：空 relPath 一律不算完成",
                !j.isComplete("", 1024), "实际 " + j.isComplete("", 1024));
        check("isComplete(relPath,size)：未传满不算完成",
                !j.isComplete("DCIM/100MSDCF/DSC02183.ARW", 24389632),
                "实际 " + j.isComplete("DCIM/100MSDCF/DSC02183.ARW", 24389632));
        check("isComplete(relPath,size)：跨会话按路径+大小也认完成",
                j.isComplete("DCIM/100MSDCF/DSC02184.ARW", 1024),
                "实际 " + j.isComplete("DCIM/100MSDCF/DSC02184.ARW", 1024));
        check("isComplete(relPath,size)：同名但大小变了不算完成",
                !j.isComplete("DCIM/100MSDCF/DSC02184.ARW", 2048),
                "实际 " + j.isComplete("DCIM/100MSDCF/DSC02184.ARW", 2048));
        check("isComplete(relPath,size)：未知路径不算完成",
                !j.isComplete("DCIM/100MSDCF/DSC99999.ARW", 1024),
                "实际 " + j.isComplete("DCIM/100MSDCF/DSC99999.ARW", 1024));
        check("doneBytes(relPath,size)：只认同路径同大小的记录",
                j.doneBytes("DCIM/100MSDCF/DSC02183.ARW", 24389632) == 7340032
                        && j.doneBytes("DCIM/100MSDCF/DSC02183.ARW", 1) == 0,
                "实际 " + j.doneBytes("DCIM/100MSDCF/DSC02183.ARW", 24389632));
        check("partPath 按 relPath + size 稳定命名、跨会话不变（PC / #50）",
                j.partPath("/sdcard/out", "DCIM/100MSDCF/DSC02183.ARW", 24389632)
                        .replace('\\', '/')
                        .equals("/sdcard/out/DCIM_100MSDCF_DSC02183.ARW.24389632.part"),
                "实际 " + j.partPath("/sdcard/out", "DCIM/100MSDCF/DSC02183.ARW", 24389632));
        check("partPath 同名同大小两次生成同一个半成品名",
                j.partPath("/sdcard/out", "DCIM/100MSDCF/DSC02183.ARW", 24389632)
                        .equals(j.partPath("/sdcard/out", "DCIM/100MSDCF/DSC02183.ARW", 24389632)),
                "实际 " + j.partPath("/sdcard/out", "DCIM/100MSDCF/DSC02183.ARW", 24389632));
        check("partPath 同名不同大小 → 不同半成品名（B-1：旧前缀不能续进新对象）",
                !j.partPath("/sdcard/out", "DCIM/100MSDCF/DSC02183.ARW", 300)
                        .equals(j.partPath("/sdcard/out", "DCIM/100MSDCF/DSC02183.ARW", 301)),
                "实际 " + j.partPath("/sdcard/out", "DCIM/100MSDCF/DSC02183.ARW", 300)
                        + " vs " + j.partPath("/sdcard/out", "DCIM/100MSDCF/DSC02183.ARW", 301));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            j.save(bos);
        } catch (Exception e) {
            check("save() 不抛异常", false, e.toString());
            return;
        }
        String text = bos.toString(java.nio.charset.StandardCharsets.UTF_8);
        check("save() 写出表头", text.startsWith(Journal.HEADER), "实际首行 " + text.split("\n")[0]);

        Journal reloaded = new Journal();
        try {
            reloaded.load(new ByteArrayInputStream(bos.toByteArray()));
        } catch (Exception e) {
            check("load() 不抛异常", false, e.toString());
            return;
        }
        check("save → load 往返保留条数", reloaded.size() == 2, "实际 " + reloaded.size());
        check("save → load 往返保留已传字节与大小",
                reloaded.doneBytes("DCIM/100MSDCF/DSC02183.ARW", 24389632) == 7340032
                        && reloaded.isComplete("DCIM/100MSDCF/DSC02184.ARW", 1024),
                "done=" + reloaded.doneBytes("DCIM/100MSDCF/DSC02183.ARW", 24389632));
        check("save → load 后仍可按 relPath+size 跳过（跨会话）",
                reloaded.isComplete("DCIM/100MSDCF/DSC02184.ARW", 1024)
                        && !reloaded.isComplete("DCIM/100MSDCF/DSC02184.ARW", 2048),
                "实际 " + reloaded.isComplete("DCIM/100MSDCF/DSC02184.ARW", 1024));

        // B-1（PR #51 评审）：handle 只在单次会话内有效，重连后同一个 handle 可能指向另一个文件。
        // 记录若以 handle 为主键，下一次会话导入新文件时会冲掉上一会话旧路径的完成记录，
        // 再连回来就会把已导入的照片重传一遍。这里用真实的两会话流程钉死该行为。
        Journal session1 = new Journal();
        session1.markDone(7, 24389632, 24389632, "DCIM/100MSDCF/DSC02183.ARW");
        ByteArrayOutputStream session1Bytes = new ByteArrayOutputStream();
        try {
            session1.save(session1Bytes);
        } catch (Exception e) {
            check("Journal：会话 1 落盘不抛异常", false, e.toString());
            return;
        }
        Journal session2 = new Journal();
        try {
            session2.load(new ByteArrayInputStream(session1Bytes.toByteArray()));
        } catch (Exception e) {
            check("Journal：会话 2 读取不抛异常", false, e.toString());
            return;
        }
        // 会话 2 里 handle 7 指向 2184 并导入成功：这里只记新文件，不应影响 2183 的记录
        session2.markDone(7, 1024, 1024, "DCIM/100MSDCF/DSC02184.ARW");
        boolean oldStillComplete = session2.isComplete("DCIM/100MSDCF/DSC02183.ARW", 24389632);
        check("Journal：handle 复用不冲掉上一会话的完成记录",
                oldStillComplete
                        && session2.isComplete("DCIM/100MSDCF/DSC02184.ARW", 1024)
                        && session2.size() == 2,
                "2183=" + oldStillComplete
                        + " 2184=" + session2.isComplete("DCIM/100MSDCF/DSC02184.ARW", 1024)
                        + " size=" + session2.size());

        Journal junk = new Journal();
        try {
            junk.load(new ByteArrayInputStream(
                    "# garbage\n\nnot-a-row\nabc\tdef\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            check("load() 遇到坏行不该崩", false, e.toString());
            return;
        }
        check("load() 忽略坏行而不是抛异常", junk.size() == 0, "实际 " + junk.size());

        check("sanitize 把制表符与换行换成下划线",
                "_a_b_".equals(Journal.sanitize("\ta\nb\r")),
                "实际 " + Journal.sanitize("\ta\nb\r"));
    }
}
