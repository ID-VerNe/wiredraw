package com.rea.mtpimport;

import java.io.File;
import java.io.IOException;

/**
 * 目录创建的**唯一**入口（issue #219 / A14）。
 *
 * 原来五处各写一遍 `!dir.exists() && !dir.mkdirs()`，失败时四种文案、三种处理方式
 * （抛 IOException / 返回 FAILED / 只记日志继续跑）。判定逻辑复制五份，改一处忘一处
 * 就会出现「某条路径建目录失败却静默继续」。这里只回答一个问题：「这个目录现在能不能用」，
 * 失败一律抛带路径的 {@link IOException}，由调用方按自己的语境转成 Result / 日志 / UI 文案。
 */
public final class Dirs {

    private Dirs() { }

    /**
     * 确保目录存在（含多级）。已存在即为成功；建不出来抛 {@link IOException}。
     *
     * 不用 `exists()` 判「已可用」：同名文件占位时 `exists()` 为 true 但不是目录，
     * 后面的写文件仍会失败。`mkdirs()` 返回 false 后再判一次 `isDirectory()`，
     * 是为了容忍「别的线程/进程抢先建好」这种竞态。
     */
    public static void ensure(File dir) throws IOException {
        if (dir == null) throw new IOException("目录为空");
        if (dir.isDirectory()) return;
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("建目录失败：" + dir);
    }
}
