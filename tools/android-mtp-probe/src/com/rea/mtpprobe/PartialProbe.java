package com.rea.mtpprobe;

import android.mtp.MtpDevice;

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
 * （issue #67，issue #50 的 PC 分节）。
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

    private final MtpDevice mtp;
    private final Reporter reporter;

    PartialProbe(MtpDevice mtp, Reporter reporter) {
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
        // 先测普通对象支持面：越界测试会主动制造 IOException；若它真会让会话进入错误态，
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

    /** 1) offset=0 连读两次：判 offset 是绝对偏移还是「已传字节」（流式）语义。 */
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
                ? "相同 → 绝对 offset 语义"
                : "不同 → 疑似「已传字节」/流式语义")
                + "；与整文件前 " + na + " 字节" + (matchFull ? "一致 ✅" : "不一致 ❌"));
        if (!same) {
            // 若第二次拿到的是后续字节，记录下来，便于判定「流式」假设
            boolean bIsNext = firstBytesMatch(full, b, (int) nb, (int) na);
            report("[PC] 第二次内容是否等于整文件 [n1, n1+n2)：" + (bIsNext ? "是（流式语义）" : "否"));
        }
    }

    /** 2) 从 0 递进读满整文件，拼临时文件，与 importFile 结果比字节数 / MD5 / 文件头。 */
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
                    got, calls, sec, (got / 1048576.0) / (sec <= 0 ? 1 : sec)));
            report(String.format(Locale.US,
                    "[PC] 字节数 %s（相机 %d / 拼接 %d）；MD5 %s（拼接 %s / 整文件 %s）",
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

    /** 3) length 超过剩余字节：截断到 EOF 还是抛异常。 */
    private void testLengthOverflow(int handle, long size) throws Exception {
        byte[] buf = new byte[64 * 1024];
        long offset = Math.max(0, size - 100);
        long remaining = size - offset;  // size < 100 时剩余会小于 100，按实际值算
        long n = mtp.getPartialObject(handle, offset, buf.length, buf);
        report(String.format(Locale.US,
                "[PC] length 越界: offset=%d（剩余 %d）、请求 %d → 返回 %d（%s）",
                offset, remaining, buf.length, n,
                n == remaining ? "截断到 EOF" : (n == 0 ? "返回 0" : "返回非预期长度")));
    }

    /** 4) offset 越界：offset == size 与 offset == size + 1 分别记录。 */
    private void testOffsetOverflow(int handle, long size) throws Exception {
        byte[] buf = new byte[4096];
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
        return (bytes / 1048576.0) / (nanos / 1e9);
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
