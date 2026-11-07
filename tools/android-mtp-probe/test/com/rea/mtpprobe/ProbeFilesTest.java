package com.rea.mtpprobe;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;

/**
 * ProbeFiles 的 JVM 自检（纯 JDK，不 import android），由 build.sh 的「纯 JDK 自检」一步运行。
 *
 * 重点锁两件事：
 * 1. {@code md5} 必须吃满整个文件——把分块循环改成只读一块时这里必须红；
 * 2. 文件头 hex / TIFF 判定的边界（非 TIFF、合法 49 49 2A 00、不足 4 字节）。
 */
public final class ProbeFilesTest {

    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) throws Exception {
        File big = File.createTempFile("probe-md5-", ".bin");
        try {
            // 2.5 MiB 出头：跑满多轮 1 MiB 读块，只读第一块会得到不同摘要
            writePattern(big, 2_621_441);

            check("md5 覆盖整个文件（2.5 MiB 多块）",
                    ProbeFiles.md5(big).equals(wholeFileMd5(big)),
                    "实际 " + ProbeFiles.md5(big) + " / 期望 " + wholeFileMd5(big));

            byte[] head = new byte[16];
            int n = ProbeFiles.readHead(big, head);
            check("readHead 读满 16 字节", n == 16, "实际 " + n);
            check("hexHead：16 个大写字节、空格分隔",
                    ProbeFiles.hexHead(head, n).trim().split(" ").length == 16,
                    "实际 " + ProbeFiles.hexHead(head, n));
            check("isTiffArw：非 TIFF 头判 false",
                    !ProbeFiles.isTiffArw(head, n), "非 TIFF 头被误判");

            byte[] tiff = {0x49, 0x49, 0x2A, 0x00, 0x08, 0, 0, 0};
            check("isTiffArw：49 49 2A 00 判 true", ProbeFiles.isTiffArw(tiff, 8), "合法头被误判");
            check("isTiffArw：不足 4 字节判 false", !ProbeFiles.isTiffArw(tiff, 3), "短头被误判");
        } finally {
            big.delete();
        }

        if (failed == 0) {
            System.out.println("== ProbeFiles 自检全绿：" + passed + " 项 ==");
            System.exit(0);
        }
        System.out.println("== ProbeFiles 自检红 " + failed + " 项（绿 " + passed + " 项）==");
        System.exit(1);
    }

    private static void writePattern(File f, int size) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int left = size;
            while (left > 0) {
                int n = Math.min(buf.length, left);
                for (int i = 0; i < n; i++) buf[i] = (byte) ((left - i) % 251);
                out.write(buf, 0, n);
                left -= n;
            }
        }
    }

    /** 独立实现：整文件一次摘要，作为 md5 的对照真值。 */
    private static String wholeFileMd5(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(Files.readAllBytes(f.toPath()));
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            return;
        }
        failed++;
        System.out.println("[红] " + name + "：" + detail);
    }
}
