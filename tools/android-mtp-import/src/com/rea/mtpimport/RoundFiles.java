package com.rea.mtpimport;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 一轮导入的落盘文件：导入日志与「已导入索引」（issue #219 / A2）。
 *
 * 从 {@link ImportService} 搬出来的原因：`run()` 只管编排（冲突判定 / 重试 / 结果分派），
 * 不该同时持有「文件叫什么、建不建目录、失败记哪句日志」这些持久化细节。
 * 这里不碰任何编排状态，纯 IO，可在 JVM 自检里直接覆盖。
 *
 * 两条既有口径原样保留：
 * - 日志读不了（不存在 / 损坏）一律按空日志继续——日志坏了不该让整个导片流程起不来；
 * - 日志与索引写不了**只记日志、不抛**——本轮成败由文件对账决定，不因附属文件失败而翻案。
 */
public final class RoundFiles {

    /**
     * 账本文件名。包内可见：{@link ImportedIndex} 要按同名排除它，
     * 字面量只能有这一处，否则两边改名不同步就会把账本误算成成品。
     */
    static final String JOURNAL_NAME = "import-journal.txt";

    private RoundFiles() { }

    public static File journalFile(File outDir) {
        return new File(outDir, JOURNAL_NAME);
    }

    /** 载入账本；文件不存在或读取失败都返回空账本，不抛异常。 */
    public static Journal load(File outDir, Log log) {
        Journal j = new Journal();
        File f = journalFile(outDir);
        if (!f.isFile()) return j;
        try (FileInputStream in = new FileInputStream(f)) {
            j.load(in);
            log.line("导入日志已载入：" + j.size() + " 条记录（" + f.getAbsolutePath() + "）");
        } catch (IOException e) {
            log.line("导入日志读取失败，按空日志继续：" + e);
        }
        return j;
    }

    /** 整文件覆写账本；父目录不存在时先建，建不出来只记日志、不抛。 */
    public static void save(File outDir, Journal j, Log log) {
        File f = journalFile(outDir);
        File parent = f.getParentFile();
        try {
            Dirs.ensure(parent);
        } catch (IOException e) {
            log.line("建目录失败，导入日志未保存：" + parent);
            return;
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            j.save(out);
            log.line("导入日志已保存：" + f.getAbsolutePath());
        } catch (IOException e) {
            log.line("导入日志保存失败：" + e);
        }
    }

    /**
     * 从磁盘真值重建「已导入索引」（#20）。
     *
     * 索引是 `outDir` 磁盘镜像的派生快照，不是事件账本：每轮结束都从磁盘重算，
     * 缺失 / 截断 / 乱码都等价于「还没生成」，直接覆盖；**不读旧文件**。
     * 失败只记日志——索引是后续单的输入，不是本轮成败的一部分。
     */
    public static void rebuildIndex(File outDir, Log log) {
        File f = new File(outDir, ImportedIndex.FILE_NAME);
        try {
            int n = ImportedIndex.save(outDir);
            log.line("已导入索引已重建：" + n + " 条（" + f.getAbsolutePath() + "）");
        } catch (IOException e) {
            log.line("已导入索引保存失败（不影响本轮结果）：" + e);
        }
    }
}
