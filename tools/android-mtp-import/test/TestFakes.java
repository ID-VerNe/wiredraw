package com.rea.mtpimport;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;
import com.rea.mtpimport.ResumableTransfer.Kind;

import static com.rea.mtpimport.TestSupport.*;
import static com.rea.mtpimport.Fixtures.*;

/** `ResumableTransfer` / `ImportListener` / `Log` 的假实现（#318 从 SelfTest.java 抽出）。 */
final class TestFakes {

    // ---------------- ImportService（真实传输编排，PA / issue #50） ----------------

    /**
     * 记录日志出口（#54：冲突必须在用户看得见的地方，不能只写调试日志）。
     */
    static final class RecordingLog implements Log {
        final List<String> lines = new ArrayList<>();

        @Override
        public void line(String s) {
            lines.add(s);
        }

        boolean contains(String part) {
            for (String line : lines) {
                if (line.contains(part)) return true;
            }
            return false;
        }
    }

    /**
     * 假传输：只验证编排层（跳过、失败计数、进度、日志），不碰 android。
     * 真实 `MtpTransfer` 用 `MtpDevice.importFile`，只能在 ILCE-7C 真机上验。
     *
     * 六个场景共用这一份实现，按 {@link Mode} 分派（issue #212）：
     *
     * - {@link Mode#RECORD_PART}：只写 `.part`，不产生成品（默认构造）；
     * - {@link Mode#FLUKY}：每个文件首次失败、第二次成功（PD / issue #50）；
     * - {@link Mode#RENAME_TO_DEST}：落满后 rename 到 `outDir/<relPath>`，供索引/跳过用例；
     * - {@link Mode#GATED}：第一个文件卡到测试放行，用来钉住「暂停只落在文件边界」；
     * - {@link Mode#OFFSET_IGNORING}：复刻真机 `getPartialObject` 忽略 offset 的旧语义；
     * - {@link Mode#HANGING}：一直卡在 importFile 里，模拟相机休眠/USB 掉线。
     *
     * 「写 `.part` / 落成品」只保留 {@link #writeFakePart} / {@link #writeFakeFile} 一份。
     */
    static final class FakeTransfer implements ResumableTransfer {
        enum Mode { RECORD_PART, FLUKY, RENAME_TO_DEST, GATED, OFFSET_IGNORING, HANGING }

        final Mode mode;
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final List<Long> resumeFroms = Collections.synchronizedList(new ArrayList<>());
        boolean failAll;
        /** FLUKY：首次失败前落盘的字节数；0 表示不落盘。 */
        long firstFailureBytes;
        final CountDownLatch firstEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final CountDownLatch secondEntered = new CountDownLatch(1);
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        private final File outDir;
        private final byte[] payload;
        private final java.util.Set<String> seen = new java.util.HashSet<>();

        /** RECORD_PART：只写 `.part`，不产生成品。 */
        FakeTransfer() {
            this(Mode.RECORD_PART, null, 0);
        }

        /** RENAME_TO_DEST：落满后 rename 到 `outDir/<relPath>`。 */
        FakeTransfer(File outDir) {
            this(Mode.RENAME_TO_DEST, outDir, 0);
        }

        /** OFFSET_IGNORING：与真机旧语义一样，忽略 offset、从 0 给 `payloadSize` 字节。 */
        FakeTransfer(int payloadSize) {
            this(Mode.OFFSET_IGNORING, null, payloadSize);
        }

        FakeTransfer(Mode mode) {
            this(mode, null, 0);
        }

        private FakeTransfer(Mode mode, File outDir, int payloadSize) {
            this.mode = mode;
            this.outDir = outDir;
            if (payloadSize > 0) {
                payload = new byte[payloadSize];
                for (int i = 0; i < payloadSize; i++) payload[i] = (byte) (i % 251);
            } else {
                payload = null;
            }
        }

        @Override
        public Result transfer(ImportPlan.Item item, String partPath, long resumeFrom,
                               CancelToken token, LongConsumer progress) {
            calls.add(item.relPath);
            if (mode == Mode.HANGING) {
                entered.countDown();
                awaitLatch(release, 10000L);
                return writeFakePart(item, partPath);
            }
            resumeFroms.add(resumeFrom);
            if (mode == Mode.FLUKY) {
                // 同一个 relPath 首次失败，第二次成功（模拟一次偶发读失败）。
                if (!seen.add(item.relPath)) return writeFakePart(item, partPath);
                if (firstFailureBytes > 0) {
                    try {
                        File part = new File(partPath);
                        indexMkdirs(part.getParentFile());
                        try (FileOutputStream out = new FileOutputStream(part)) {
                            out.write(new byte[(int) firstFailureBytes]);
                        }
                    } catch (IOException e) {
                        return new Result(Kind.FAILED, e.toString());
                    }
                }
                return new Result(Kind.FAILED, "fake first failure");
            }
            if (mode == Mode.GATED) {
                int n = calls.size();
                if (n == 1) {
                    firstEntered.countDown();
                    if (!awaitLatch(releaseFirst)) {
                        return new Result(Kind.FAILED, "测试未放行第一个文件");
                    }
                } else if (n == 2) {
                    secondEntered.countDown();
                }
                return writeFakeFile(item, partPath);
            }
            if (failAll) return new Result(Kind.FAILED, "fake failure");
            if (mode == Mode.OFFSET_IGNORING) return offsetIgnoring(item, partPath, resumeFrom, progress);
            if (mode == Mode.RENAME_TO_DEST) {
                try {
                    File part = new File(partPath);
                    indexMkdirs(part.getParentFile());
                    try (FileOutputStream out = new FileOutputStream(part)) {
                        out.write(new byte[(int) item.size]);
                    }
                    File dest = new File(outDir, item.relPath);
                    indexMkdirs(dest.getParentFile());
                    if (!part.renameTo(dest)) return new Result(Kind.FAILED, "rename failed");
                    return Result.of(Kind.OK);
                } catch (IOException e) {
                    return new Result(Kind.FAILED, e.toString());
                }
            }
            return writeFakePart(item, partPath);
        }

        /** 旧 reader 语义：`from == size` 直通、非 0 起点按 append 写，用于钉住「续传已停用」。 */
        private Result offsetIgnoring(ImportPlan.Item item, String partPath, long resumeFrom,
                                      LongConsumer progress) {
            try {
                File part = new File(partPath);
                indexMkdirs(part.getParentFile());
                File dest = new File(part.getParentFile(), item.relPath);
                if (resumeFrom == item.size) {
                    indexMkdirs(dest.getParentFile());
                    return part.renameTo(dest)
                            ? Result.of(Kind.OK)
                            : new Result(Kind.FAILED, "rename failed");
                }
                if (resumeFrom > 0) {
                    try (FileOutputStream out = new FileOutputStream(part, true)) {
                        long offset = resumeFrom;
                        while (offset < item.size) {
                            long n = Math.min(64L, item.size - offset);
                            out.write(payload, 0, (int) n);
                            offset += n;
                            progress.accept(offset);
                        }
                    }
                } else {
                    // 新契约：旧 `.part` 一律作废（删除），从 0 整段写。
                    if (part.exists() && !part.delete()) {
                        return new Result(Kind.FAILED, "旧 .part 删除失败");
                    }
                    try (FileOutputStream out = new FileOutputStream(part)) {
                        out.write(payload);
                    }
                    progress.accept(item.size);
                }
                indexMkdirs(dest.getParentFile());
                if (!part.renameTo(dest)) return new Result(Kind.FAILED, "rename failed");
                return Result.of(Kind.OK);
            } catch (IOException e) {
                return new Result(Kind.FAILED, e.toString());
            }
        }
    }

    /**
     * issue #70：按块上报**当前文件的绝对偏移**，并可选「首次失败 + 重试闸门」。
     *
     * - 每次调用都从 0 截断重写（续传已停用，#177：旧 `.part` 不得作为前缀）。
     * - `failFirst=true`：同一 relPath 的第一次调用在落 40 字节后返回 FAILED；
     *   第二次调用（重试）刚上报起点 0 就停住，等用例核对「重试不双计」后再放行。
     * - 闸门只挂在第 2 次调用（第一个文件的重试），不干扰后续文件。
     */
    static final class ResumeReportingTransfer implements ResumableTransfer {
        final List<String> calls = new ArrayList<>();
        final List<Long> resumeFroms = new ArrayList<>();
        final CountDownLatch retryEntered = new CountDownLatch(1);
        final CountDownLatch releaseRetry = new CountDownLatch(1);
        private final boolean failFirst;
        private final long chunk;
        private final Set<String> failed = new HashSet<>();

        ResumeReportingTransfer(boolean failFirst, long chunk) {
            this.failFirst = failFirst;
            this.chunk = chunk;
        }

        @Override
        public Result transfer(ImportPlan.Item item, String partPath, long resumeFrom,
                               CancelToken token, LongConsumer progress) {
            calls.add(item.relPath);
            resumeFroms.add(resumeFrom);
            int index = calls.size();
            try {
                File part = new File(partPath);
                indexMkdirs(part.getParentFile());
                long offset = resumeFrom;
                // 续传停用（#177）：无论磁盘上有没有残留，都从 0 截断重写。
                try (FileOutputStream out = new FileOutputStream(part, false)) {
                    progress.accept(offset);
                    if (failFirst && index == 2) {
                        retryEntered.countDown();
                        if (!awaitLatch(releaseRetry)) {
                            return new Result(Kind.FAILED, "重试闸门超时");
                        }
                    }
                    if (failFirst && failed.add(item.relPath)) {
                        long end = Math.min(item.size, offset + 40L);
                        out.write(new byte[(int) (end - offset)]);
                        offset = end;
                        progress.accept(offset);
                        return new Result(Kind.FAILED, "伪首次失败");
                    }
                    while (offset < item.size) {
                        long n = Math.min(chunk, item.size - offset);
                        out.write(new byte[(int) n]);
                        offset += n;
                        progress.accept(offset);
                    }
                }
                return Result.of(Kind.OK);
            } catch (IOException e) {
                return new Result(Kind.FAILED, e.toString());
            }
        }
    }

    /**
     * 第一次调用先请求取消、再返回 FAILED：钉住「取消优先于失败」的分支（PD / issue #50）。
     * 若编排层把这条计成失败，`failed` 会变成 1，用例即红。
     */
    static final class CancelOnFailTransfer implements ResumableTransfer {
        final List<String> calls = new ArrayList<>();
        Runnable onFirstCall;

        @Override
        public Result transfer(ImportPlan.Item item, String partPath, long resumeFrom,
                               CancelToken token, LongConsumer progress) {
            calls.add(item.relPath);
            Runnable r = onFirstCall;
            if (r != null) {
                onFirstCall = null;
                r.run();
            }
            return new Result(Kind.FAILED, "fake failure after cancel");
        }
    }

    static final class FinishRecorder implements ImportListener {
        final CountDownLatch done = new CountDownLatch(1);
        /** issue #70：每次 onProgress 时的 bytesDone() 快照，用来核对文件内进度与重试不双计。 */
        final List<Long> progressBytes = new ArrayList<>();
        /** issue #70：每个文件结束（onFileFinished）那一刻的 bytesDone()。 */
        final List<Long> bytesAtFileFinish = new ArrayList<>();
        boolean cancelled;
        int ok;
        int skipped;
        int failed;
        String summary;
        TransferProgress lastProgress;

        @Override
        public void onProgress(TransferProgress progress, ImportPlan.Item current) {
            lastProgress = progress;
            progressBytes.add(progress.bytesDone());
        }

        @Override
        public void onFileFinished(ImportPlan.Item item, ResumableTransfer.Kind kind, String note) {
            if (lastProgress != null) bytesAtFileFinish.add(lastProgress.bytesDone());
        }

        @Override
        public void onFinished(boolean cancelled, int ok, int skipped, int failed, String summary) {
            this.cancelled = cancelled;
            this.ok = ok;
            this.skipped = skipped;
            this.failed = failed;
            this.summary = summary;
            done.countDown();
        }
    }

    /**
     * 模拟「取消真的打断了当前 importFile」：传输实现等到取消令牌的动作触发（真实路径里
     * 那是关闭 ParcelFileDescriptor），然后返回 CANCELLED。
     */
    static final class CancellingTransfer implements ResumableTransfer {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch tokenCancelled = new CountDownLatch(1);

        @Override
        public Result transfer(ImportPlan.Item item, String partPath, long resumeFrom,
                               CancelToken token, LongConsumer progress) {
            calls.add(item.relPath);
            token.setOnCancel(tokenCancelled::countDown);
            entered.countDown();
            if (!awaitLatch(tokenCancelled)) {
                token.clearOnCancel();
                return new Result(Kind.FAILED, "取消动作未触发，传输无法被中断");
            }
            token.clearOnCancel();
            return new Result(Kind.CANCELLED, "已取消，本次半成品已删除");
        }
    }

    /**
     * 模拟「取消动作打不断传输」：完全忽略取消令牌，直到测试放行才返回。
     * 用来钉住二审 B-1 的死路判定：worker 不退出时界面必须能判出「卡住」并给 ERROR 出路。
     */
    static final class StuckTransfer implements ResumableTransfer {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public Result transfer(ImportPlan.Item item, String partPath, long resumeFrom,
                               CancelToken token, LongConsumer progress) {
            entered.countDown();
            awaitLatch(release, 10000L);
            return new Result(Kind.FAILED, "测试放行");
        }
    }

    // ---------------- 生产路径的暂停时钟接线（issue #58 一审 B-1） ----------------

    /**
     * 可手动拨动的时钟。用例先 {@link #set(long)} 到暂停时刻，worker 读到该值即放行
     * {@link #pauseRead}，让用例确认「worker 已停在文件边界并开始记账」。
     */
    static final class ManualClock implements ImportService.Clock {
        private volatile long now;
        private final long initial;
        private final AtomicInteger reads = new AtomicInteger();
        final CountDownLatch pauseRead = new CountDownLatch(1);

        ManualClock(long initial) {
            this.now = initial;
            this.initial = initial;
        }

        void set(long value) {
            now = value;
        }

        /**
         * 读到「非初始值」即视为 worker 已走到暂停记账点（issue #63）。
         *
         * 旧实现用「第 2 次读」当哨兵，本单在 `transfer.transfer(...)` 前新增了一次读钟
         * （刷新最后活动时刻），次数就错位了。按值判定与读次数无关，新增读钟点不会再让它失效。
         */
        @Override
        public long nanoTime() {
            long value = now;
            reads.incrementAndGet();
            if (value != initial) pauseRead.countDown();
            return value;
        }
    }

    /**
     * 两个文件各一道闸门：第一个放行后 worker 才能推进进度，第二个必须等用例断言完才放行。
     * 不能直接用 {@link FakeTransfer}（GATED 模式）——它的第二个文件不阻塞，进度会在断言时继续涨。
     */
    static final class TwoGateTransfer implements ResumableTransfer {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch firstEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final CountDownLatch secondEntered = new CountDownLatch(1);
        final CountDownLatch releaseSecond = new CountDownLatch(1);

        @Override
        public Result transfer(ImportPlan.Item item, String partPath, long resumeFrom,
                               CancelToken token, LongConsumer progress) {
            calls.add(item.relPath);
            int n = calls.size();
            CountDownLatch entered = n == 1 ? firstEntered : secondEntered;
            CountDownLatch release = n == 1 ? releaseFirst : releaseSecond;
            entered.countDown();
            if (!awaitLatch(release)) {
                return new Result(Kind.FAILED, "测试未放行第 " + n + " 个文件");
            }
            return writeFakePart(item, partPath);
        }
    }

    // ---------------- 续传停用：旧 `.part` 一律作废（#177） ----------------

    /**
     * 复刻 `MtpTransfer` 的「目标同尺寸」分支：整文件已经下完，发现目标同尺寸 → 删 `.part`、
     * 返回 SKIPPED（仅尺寸核对，内容未校验）。用来钉住「SKIPPED 必须落账」，否则下一轮会再整文件重下。
     */
    static final class SameSizeSkipTransfer implements ResumableTransfer {
        int calls;

        @Override
        public Result transfer(ImportPlan.Item item, String partPath, long resumeFrom,
                               CancelToken token, LongConsumer progress) {
            calls++;
            File part = new File(partPath);
            if (part.exists()) part.delete();
            return new Result(Kind.SKIPPED, "已存在同尺寸文件，未覆盖（仅尺寸核对，内容未校验）");
        }
    }
}
