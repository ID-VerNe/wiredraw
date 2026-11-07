package com.rea.mtpprobe;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

/**
 * 纯 JDK 的探针文件工具（不 import android.*），由 {@link Main} 与 {@link PartialProbe} 共用。
 *
 * 存在的理由：md5 与「文件头 hex + TIFF 判定」原先在 {@code Main} 与 {@code PartialProbe}
 * 各写一份，两份实现会各自漂移；收敛到这里之后，{@code test/.../ProbeFilesTest.java}
 * 能脱离 android.jar 直接跑在 JVM 上，md5 的分块循环被改坏时自检会红。
 */
final class ProbeFiles {

    /** 摘要分块大小：1 MiB。 */
    static final int BLOCK = 1 << 20;
    /** 文件头预览的字节数。 */
    static final int HEAD_BYTES = 16;

    private ProbeFiles() {
    }

    /** 整文件 MD5（小写 hex），分块读，覆盖到 EOF。 */
    static String md5(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] buf = new byte[BLOCK];
        try (FileInputStream in = new FileInputStream(f)) {
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte x : md.digest()) s.append(String.format("%02x", x));
        return s.toString();
    }

    /** 读文件头前 {@code head.length} 字节，返回实际读到的字节数（文件更短时不足）。 */
    static int readHead(File f, byte[] head) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            return Math.max(in.read(head), 0);
        }
    }

    /** 把前 n 字节格式化成「AA BB … 」（每字节后跟一个空格，沿用原先的运行文案）。 */
    static String hexHead(byte[] head, int n) {
        StringBuilder hx = new StringBuilder();
        for (int i = 0; i < n; i++) hx.append(String.format("%02X ", head[i]));
        return hx.toString();
    }

    /** 合法 ARW 的 TIFF 头：字节序 II + 魔数 42。 */
    static boolean isTiffArw(byte[] head, int n) {
        return n >= 4 && head[0] == 0x49 && head[1] == 0x49
                && head[2] == 0x2A && head[3] == 0x00;
    }

    /** 一行「文件头: <hex> ✅/❌」——报告里文件头只保留这一种写法。 */
    static String headLine(File f) throws Exception {
        byte[] head = new byte[HEAD_BYTES];
        int n = readHead(f, head);
        return "文件头: " + hexHead(head, n)
                + (isTiffArw(head, n) ? "✅ 合法 ARW(TIFF)" : "❌ 头部不对");
    }
}
