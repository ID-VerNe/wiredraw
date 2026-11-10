package com.rea.mtpimport;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/**
 * 临时目录的创建与清理（#318 从 SelfTest.java 抽出，清理口径在 #320 收口）。
 *
 * 全仓唯一的临时目录创建点就在这里：用例只写 `withTempDir(前缀, dir -> { ... })`，
 * 不再各自建目录 + 手工清理。
 */
final class TempDirs {

    /** 临时目录里的用例体；允许抛受检异常，调用方本来就大多声明了 `throws Exception`。 */
    @FunctionalInterface
    interface TempDirBody {
        void run(File dir) throws Exception;
    }

    /**
     * 在临时目录里跑一段用例，结束就删。
     *
     * 清理放在 `finally`：正常返回、断言失败、中途抛 IO / 运行时异常都走同一条出口（#320）。
     * 原来的写法把 `deleteTree` 堆在方法末尾，`check()` 又不抛异常，于是「断言失败照样清理」成立，
     * 但真异常会跳过清理——这一版把两种情况都收进 finally。
     *
     * 前缀保持调用点原值（`wiredraw-…`）：排障时要靠它认出是哪一组用例留下的目录。
     */
    static void withTempDir(String prefix, TempDirBody body) throws Exception {
        File dir = create(prefix);
        try {
            body.run(dir);
        } finally {
            deleteTree(dir);
        }
    }

    private static File create(String prefix) {
        try {
            return Files.createTempDirectory(prefix).toFile();
        } catch (IOException e) {
            throw new UncheckedIOException("建临时目录失败：" + prefix, e);
        }
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        if (!f.delete()) {
            System.err.println("清理临时文件失败：" + f);
        }
    }
}
