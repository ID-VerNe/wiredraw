package com.rea.mtpprobe;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * PC 探针：验证 {@link MtpDevice#getPartialObject(int, long, long, byte[])} 在 ILCE-7C 上的语义
 * （issue #67，issue #50 的 PC 分节；非零 offset 的内容判别与文案门槛见 issue #179）。
 *
 * 只读：只发 GetPartialObject / GetObject（下载到手机私有目录），不删除、不改相机设置、不触发快门。
 * 结果由调用方（{@link Main}）统一写 logcat 与 report.txt。
 */
final class PartialProbe {

    /** 输出回调：由 Main 写到 logcat + 屏幕 + report.txt。 */
    interface Reporter {
        void out(String s);
    }

    /** 语义判别用的小块：64 KiB。 */
    static final int FIRST_BLOCK = 64 * 1024;
    /** 拼接整文件用的块：1 MiB。 */
    static final int CHUNK = 1 << 20;
    /** 非零 offset 判别（issue #179）基线段：1 MiB，必须大于游标推进后的 64 KiB 跳块。 */
    static final int DISC_BASE = 1 << 20;
    /** 非零 offset 判别跳块：64 KiB。 */
    static final int DISC_JUMP = 64 * 1024;
    /** 前向跳块请求的 offset：2 MiB。读完 1 MiB 基线段后游标在 1 MiB，请求 1 MiB 无法区分两种语义。 */
    static final long DISC_FORWARD = 2L * 1024 * 1024;
    /** 回跳请求的 offset：8 KiB。明显小于游标，忽略 offset 时必然拿不到这一片段。 */
    static final long DISC_BACK = 8L * 1024;
    /** offset 越界用例的请求长度：4 KiB。 */
    static final int OVERFLOW_LEN = 4096;
    /** length 越界用例从对象末尾回退的字节数，保证还剩一点尾巴可读。 */
    static final long TAIL_BYTES = 100;

    private final ReadOnlyMtp mtp;
    private final Reporter reporter;

    PartialProbe(ReadOnlyMtp mtp, Reporter reporter) {
        this.mtp = mtp;
        this.reporter = reporter;
    }

    private void report(String s) {
        reporter.out(s);
    }

    /**
     * @param handle 已整文件下载过的一张 ARW
     * @param size   相机侧 ObjectInfo 的字节数
     * @param name   文件名（只用于日志与临时文件名）
     * @param full   手机侧整文件下载结果，用来做 MD5 / 字节对照
     * @param others 其它普通对象样本 {handle, size, name}，各读首块验证支持面
     */
    void run(int handle, long size, String name, File full, List<Object[]> others) {
        report("---- [PC 探针] getPartialObject 语义（" + name + "，" + size + " 字节）----");
        // 非零 offset 判别必须排在该对象的第一条 partial 读：它的四步依赖「游标没有被其它读扰动」。
        // 也排在普通对象支持面之前——按对象分游标是乐观假设，全局游标的 responder 会被别的句柄带跑。
        try {
            testOffsetDiscrimination(handle, size, name, full);
        } catch (Throwable t) {
            report("[PC] 非零 offset 判别异常: " + t);
        }
        // 再测普通对象支持面：越界测试会主动制造 IOException；若它真会让会话进入错误态，
        // 后续读取都会被误判成「不支持」，所以越界测试一律排在最后。
        if (others != null) {
            for (Object[] e : others) {
                int h = (Integer) e[0];
                long sz = (Long) e[1];
                String nm = (String) e[2];
                try {
                    byte[] buf = new byte[FIRST_BLOCK];
                    long n = mtp.getPartialObject(h, 0, buf.length, buf);
                    report(String.format(Locale.US,
                            "[PC] 普通对象 h=0x%08X %-14s %8d 字节 → 首块 %d 字节 %s",
                            h, nm, sz, n, n > 0 ? "✅支持" : "❌不支持"));
                } catch (Throwable t) {
                    report(String.format(Locale.US,
                            "[PC] 普通对象 h=0x%08X %-14s 首块异常: %s", h, nm, t));
                }
            }
        }
        try {
            testRepeat(handle, full);
        } catch (Throwable t) {
            report("[PC] 同段重复读异常: " + t);
        }
        try {
            testAssemble(handle, size, name, full);
        } catch (Throwable t) {
            report("[PC] 拼接整文件异常: " + t);
        }
        try {
            testLengthOverflow(handle, size);
        } catch (Throwable t) {
            report("[PC] length 越界异常: " + t);
        }
        try {
            testOffsetOverflow(handle, size);
        } catch (Throwable t) {
            report("[PC] offset 越界异常（offset=size）: " + t);
        }
        report("---- [PC 探针] 结束 ----");
    }

    /**
     * 非零 offset 内容判别（issue #179）。
     *
     * 为什么必须单独一条：11-06 的用例只比对 offset=0 与「从 0 逐块递增」，
     * 「忽略 offset、按游标顺序推进」和「每次从 0 返回」两种实现都能完整通过；
     * #177 的真机反证（续传产物尾部 == 对象头）就是这么被假阴性放过去的。
     *
     * 真值来自 {@code full}：它是 importFile（GetObject 整对象下载）的产物，与本用例被测的
     * getPartialObject 是两条独立 opcode 路径，不构成「拿被测实现自己当基准」；
     * 且 full 的 MD5 已在 PC 侧核对过（见 README「实测结果」）。
     *
     * 四步固定顺序：R0 offset=0 / 1 MiB 建立对象头参照 → R1 offset=2 MiB / 64 KiB 前向跳块
     * → R2 offset=8 KiB / 64 KiB 回跳 → R3 offset=0 / 64 KiB 非零读后复位。
     * 每次都把返回字节在 full 中的「实际内容位置」定位出来，逐条输出
     * 「请求 offset → 实际位置」，再由位置序列判语义，不只给 pass/fail。
     */
    private void testOffsetDiscrimination(int handle, long size, String name, File full) throws Exception {
        report("[PC] ---- 非零 offset 内容判别（issue #179，基准=" + name + "）----");
        long[] req = {0, DISC_FORWARD, DISC_BACK, 0};
        int[] want = {DISC_BASE, DISC_JUMP, DISC_JUMP, DISC_JUMP};
        String[] tag = {"R0 头部参照", "R1 前向跳块", "R2 回跳", "R3 复位"};
        long[] pos = new long[req.length];
        int[] got = new int[req.length];
        for (int i = 0; i < req.length; i++) {
            // 对象比请求区间短时按剩余截断，避免把「文件太小」误判成语义问题。
            int ask = (int) Math.min((long) want[i], size - req[i]);
            if (ask <= 0) {
                report(String.format(Locale.US,
                        "[PC] %s 请求 offset=%d 超出对象（%d 字节），跳过", tag[i], req[i], size));
                pos[i] = -2;
                continue;
            }
            byte[] buf = new byte[ask];
            long n;
            try {
                n = mtp.getPartialObject(handle, req[i], ask, buf);
            } catch (Throwable t) {
                report(String.format(Locale.US,
                        "[PC] %s 请求 offset=%d len=%d 异常: %s", tag[i], req[i], ask, t));
                pos[i] = -2;
                continue;
            }
            int len = (int) Math.min(n, (long) ask);
            got[i] = len;
            if (n <= 0) {
                report(String.format(Locale.US,
                        "[PC] %s 请求 offset=%d len=%d → 返回 %d（无内容可定位）",
                        tag[i], req[i], ask, n));
                pos[i] = -2;
                continue;
            }
            if (n > ask) {
                report(String.format(Locale.US,
                        "[PC] %s 请求 %d 字节却返回 %d（缓冲区语义不符，按请求长度截断判定）",
                        tag[i], ask, n));
            }
            // 先在请求 offset 处逐字节比对；命中即「真绝对偏移」，不命中再全文件扫描片段的真实位置。
            boolean hit = firstBytesMatch(full, buf, len, (int) req[i]);
            long at = hit ? req[i] : findFirst(full, buf, len);
            pos[i] = at;
            String verdict;
            if (at == req[i]) {
                verdict = "✅ 命中请求 offset";
            } else if (at < 0) {
                verdict = "❌ 返回字节在基准文件中找不到";
            } else {
                verdict = String.format(Locale.US, "❌ 偏离请求 offset（差 %+d）", at - req[i]);
            }
            report(String.format(Locale.US,
                    "[PC] %s 请求 offset=%d len=%d → 返回 %d，实际内容位置=%s %s",
                    tag[i], req[i], ask, len, at < 0 ? "未知" : String.valueOf(at), verdict));
        }
        report("[PC] 语义判定：" + classifyOffsetSemantics(req, got, pos));
    }

    /**
     * 由四次读的「请求 offset / 实际返回长度 / 实际内容位置」判 offset 语义。
     * 纯静态、不碰 Android 类型，便于用 JDK 离线对照三组位置序列（#179）。
     *
     * @param req 每次请求的 offset
     * @param got 每次实际返回的字节数（异常/空返回记 0）
     * @param pos 每次返回内容在基准文件中的起始位置（-1 找不到，-2 本次未取得有效内容）
     * @return 一行人类可读判定
     */
    static String classifyOffsetSemantics(long[] req, int[] got, long[] pos) {
        if (req.length < 4 || got.length < 4 || pos.length < 4) {
            return "无法判定：判别用例不足四条";
        }
        for (long p : pos) {
            if (p < 0) {
                return "无法判定：有请求未返回内容或返回内容不在基准文件中（基准或该次读取不可信）";
            }
        }
        // 1) 四条都落在各自请求的 offset：真绝对偏移
        if (pos[0] == req[0] && pos[1] == req[1] && pos[2] == req[2] && pos[3] == req[3]) {
            return "命中请求 offset（真绝对偏移）—— 四条读出的内容都落在各自请求的 offset";
        }
        // 2) 每条都从对象头返回：offset 被完全忽略
        boolean fromZero = true;
        for (long p : pos) {
            if (p != 0) { fromZero = false; break; }
        }
        if (fromZero) {
            return "每次从 0 返回（offset 被完全忽略，每次都回到对象头）";
        }
        // 3) 位置随实际返回字节数递增、与请求 offset 无关：游标顺序推进
        boolean cursorSeq = true;
        for (int i = 1; i < pos.length; i++) {
            if (pos[i] != pos[i - 1] + got[i - 1]) { cursorSeq = false; break; }
        }
        if (cursorSeq) {
            return "按游标顺序推进（忽略 offset）—— 游标从 " + pos[0]
                    + " 起，每次按实际返回字节数前进，与请求 offset 无关";
        }
        return "混合/未识别 —— 请求 offset 与返回内容位置不成上述三种模式（逐条位置见上）";
    }

    /**
     * 在整文件里查找 buf[0..n) 首个出现的字节位置（找不到返回 -1）。
     * 只在「请求 offset 处不匹配」时调用，用来把「忽略 offset」的返回字节定位到基准文件的真实位置。
     * 先比首 4 字节再比剩余，避免对 24 MB 文件逐位置做全长比较。
     */
    private static long findFirst(File f, byte[] buf, int n) throws IOException {
        if (n <= 0 || n > buf.length) return -1;
        byte[] nd = slice(buf, n);
        int pre = Math.min(n, 4);
        byte[] carry = new byte[Math.max(0, n - 1)];
        int carryLen = 0;
        long base = 0;   // carry[0] 在文件中的绝对位置
        byte[] blk = new byte[1 << 20];
        try (FileInputStream in = new FileInputStream(f)) {
            int r;
            while ((r = in.read(blk)) > 0) {
                int total = carryLen + r;
                byte[] win = new byte[total];
                if (carryLen > 0) System.arraycopy(carry, 0, win, 0, carryLen);
                System.arraycopy(blk, 0, win, carryLen, r);
                int limit = total - n;
                for (int i = 0; i <= limit; i++) {
                    if (win[i] != nd[0]) continue;
                    int j = 1;
                    while (j < pre && win[i + j] == nd[j]) j++;
                    if (j < pre) continue;
                    while (j < n && win[i + j] == nd[j]) j++;
                    if (j == n) return base + i;
                }
                int keep = Math.min(carry.length, total);
                System.arraycopy(win, total - keep, carry, 0, keep);
                carryLen = keep;
                base += total - keep;
            }
        }
        return -1;
    }

    /** 1) offset=0 连读两次：只能证明「offset=0 可重复/可复位」，不能证明非零 offset 生效（#179）。 */
    private void testRepeat(int handle, File full) throws Exception {
        byte[] a = new byte[FIRST_BLOCK];
        byte[] b = new byte[FIRST_BLOCK];
        long t0 = System.nanoTime();
        long na = mtp.getPartialObject(handle, 0, a.length, a);
        long t1 = System.nanoTime();
        long nb = mtp.getPartialObject(handle, 0, b.length, b);
        long t2 = System.nanoTime();
        report(String.format(Locale.US,
                "[PC] offset=0 连读两次: n1=%d (%.2f MB/s) n2=%d (%.2f MB/s)",
                na, rate(na, t1 - t0), nb, rate(nb, t2 - t1)));
        if (na <= 0 || nb <= 0) {
            report("[PC] 首块返回非正，无法判定 offset 语义");
            return;
        }
        int cmp = (int) Math.min(na, nb);
        boolean same = true;
        for (int i = 0; i < cmp; i++) {
            if (a[i] != b[i]) { same = false; break; }
        }
        boolean matchFull = firstBytesMatch(full, a, (int) na);
        report("[PC] 两次 offset=0 内容" + (same
                ? "相同 → offset=0 可重复/可复位（不构成非零 offset 生效的证据，语义看 R0–R3）"
                : "不同 → 疑似「已传字节」/流式语义")
                + "；与整文件前 " + na + " 字节" + (matchFull ? "一致 ✅" : "不一致 ❌"));
        if (!same) {
            // 若第二次拿到的是后续字节，记录下来，便于判定「流式」假设
            boolean bIsNext = firstBytesMatch(full, b, (int) nb, (int) na);
            report("[PC] 第二次内容是否等于整文件 [n1, n1+n2)：" + (bIsNext ? "是（流式语义）" : "否"));
        }
    }

    /**
     * 2) 从 0 递进读满整文件，拼临时文件，与 importFile 结果比字节数 / MD5 / 文件头。
     * offset 与游标天然同步，所以 MD5 一致只能证明「顺序拼接完整性」，不能作为 offset 语义证据（#179）。
     */
    private void testAssemble(int handle, long size, String name, File full) throws Exception {
        File tmp = new File(full.getParentFile(), "partial_" + name);
        if (tmp.exists() && !tmp.delete()) report("[PC] 旧的 " + tmp.getName() + " 删不掉，结果可能不准");
        try {
            byte[] buf = new byte[CHUNK];
            MessageDigest md = MessageDigest.getInstance("MD5");
            long offset = 0;
            int calls = 0;
            long t0 = System.nanoTime();
            try (FileOutputStream fo = new FileOutputStream(tmp)) {
                while (offset < size) {
                    int want = (int) Math.min((long) buf.length, size - offset);
                    long n = mtp.getPartialObject(handle, offset, want, buf);
                    if (n <= 0) {
                        report("[PC] 拼接中断：offset=" + offset + " 返回 " + n);
                        break;
                    }
                    if (n > want) {
                        report("[PC] 异常：请求 " + want + " 字节却返回 " + n + "（缓冲区语义不符）");
                    }
                    int copy = (int) Math.min(n, (long) want);
                    fo.write(buf, 0, copy);
                    md.update(buf, 0, copy);
                    offset += copy;
                    calls++;
                    if (calls % 8 == 0 && offset < size) {
                        report(String.format(Locale.US, "  ...拼接 %d/%d（%d 次调用）", offset, size, calls));
                    }
                }
            }
            double sec = (System.nanoTime() - t0) / 1e9;
            long got = tmp.length();
            String md5Got = md5(tmp);
            String md5Full = md5(full);
            byte[] head = new byte[16];
            int hn = 0;
            try (FileInputStream in = new FileInputStream(tmp)) {
                hn = Math.max(in.read(head), 0);
            }
            StringBuilder hx = new StringBuilder();
            for (int i = 0; i < hn; i++) hx.append(String.format("%02X ", head[i]));
            boolean tiff = hn >= 4 && head[0] == 0x49 && head[1] == 0x49
                    && head[2] == 0x2A && head[3] == 0x00;
            report(String.format(Locale.US,
                    "[PC] 拼接结果: %d 字节 / %d 次调用 / %.2f 秒 = %.2f MB/s",
                    got, calls, sec, (got / Main.MB) / (sec <= 0 ? 1 : sec)));
            report(String.format(Locale.US,
                    "[PC] 顺序拼接结果: 字节数 %s（相机 %d / 拼接 %d）；MD5 %s（拼接 %s / 整文件 %s）—— 只证顺序完整性，不判 offset 语义",
                    got == size ? "一致 ✅" : "不一致 ❌", size, got,
                    md5Got.equals(md5Full) ? "一致 ✅" : "不一致 ❌", md5Got, md5Full));
            report("[PC] 文件头: " + hx + (tiff ? "✅ 合法 ARW(TIFF)" : "❌ 头部不对"));
        } finally {
            // 拼接用的临时文件在手机私有目录，最大 24.4 MB；正常结束与中途异常都要收尾，避免残留。
            if (tmp.exists() && !tmp.delete()) {
                report("[PC] 临时文件 " + tmp.getAbsolutePath() + " 删除失败，需手动清理");
            }
        }
    }

    /** 3) length 超过剩余字节：截断到 EOF 还是抛异常。非零 offset 但只比长度，内容判别见 testOffsetDiscrimination。 */
    private void testLengthOverflow(int handle, long size) throws Exception {
        byte[] buf = new byte[FIRST_BLOCK];
        long offset = Math.max(0, size - TAIL_BYTES);
        long remaining = size - offset;  // size 很小时剩余会小于 TAIL_BYTES，按实际值算
        long n = mtp.getPartialObject(handle, offset, buf.length, buf);
        report(String.format(Locale.US,
                "[PC] length 越界: offset=%d（剩余 %d）、请求 %d → 返回 %d（%s）",
                offset, remaining, buf.length, n,
                n == remaining ? "截断到 EOF" : (n == 0 ? "返回 0" : "返回非预期长度")));
    }

    /** 4) offset 越界：offset == size 与 offset == size + 1 分别记录。 */
    private void testOffsetOverflow(int handle, long size) throws Exception {
        byte[] buf = new byte[OVERFLOW_LEN];
        try {
            long n = mtp.getPartialObject(handle, size, buf.length, buf);
            report("[PC] offset=size（恰好 EOF）返回 " + n);
        } catch (Throwable t) {
            report("[PC] offset=size（恰好 EOF）异常: " + t);
        }
        try {
            // 有意为之的最后一步：只记录越界请求本身的报错；本轮不做会话探活（留待真机收尾批次）。
            long n = mtp.getPartialObject(handle, size + 1, buf.length, buf);
            report("[PC] offset=size+1 返回 " + n);
        } catch (Throwable t) {
            report("[PC] offset=size+1 异常: " + t);
        }
    }

    /** 读整文件前 n 字节，与 buf 比对。 */
    private static boolean firstBytesMatch(File f, byte[] buf, int n) throws IOException {
        return firstBytesMatch(f, buf, n, 0);
    }

    /** 读整文件 [skip, skip+n) 与 buf[0..n) 比对。 */
    private static boolean firstBytesMatch(File f, byte[] buf, int n, int skip) throws IOException {
        if (n <= 0) return false;
        byte[] tmp = new byte[n];
        int got = 0;
        try (FileInputStream in = new FileInputStream(f)) {
            long skipped = 0;
            while (skipped < skip) {
                long s = in.skip(skip - skipped);
                if (s <= 0) break;
                skipped += s;
            }
            while (got < n) {
                int r = in.read(tmp, got, n - got);
                if (r <= 0) break;
                got += r;
            }
        }
        if (got < n) return false;
        return Arrays.equals(tmp, slice(buf, n));
    }

    private static byte[] slice(byte[] a, int n) {
        if (a.length == n) return a;
        byte[] r = new byte[n];
        System.arraycopy(a, 0, r, 0, n);
        return r;
    }

    private static double rate(long bytes, long nanos) {
        if (nanos <= 0) return 0;
        return (bytes / Main.MB) / (nanos / 1e9);
    }

    static String md5(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte x : md.digest()) s.append(String.format("%02x", x));
        return s.toString();
    }
}
