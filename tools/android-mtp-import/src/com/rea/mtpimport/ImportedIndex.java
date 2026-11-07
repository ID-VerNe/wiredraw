package com.rea.mtpimport;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 已导入索引：`outDir` 磁盘镜像的**派生快照**（`relPath → size`），不是事件账本。
 *
 * 一次成功导入的终点是 `.part` rename 到 `outDir/<relPath>`（`MtpTransfer` 的 `finish`），
 * 所以「成品文件在不在」本身就是「已导入」的事实。本类不接受任何外部写入：每轮导入结束时
 * 递归扫一遍 `outDir` 重新生成，再原子重写文件。**不读旧索引**——缺失、截断、乱码都等价于
 * 「还没生成」，重写即恢复；这样不存在「旧索引说导入过、磁盘却没有」的分歧，也就不会因为
 * 索引本身状态异常而把片子判成新片。
 *
 * **主键 `relPath + size`**，与 {@link Journal} 的跨会话跳过、`.part` 命名、{@link ImportPlan}
 * 的同一批文件判定同口径。**已知边界**：同路径同尺寸但内容变了（换卡后编号复用、相机内替换、
 * 格式化后重拍同名文件）分辨不出来，会被当成已导入。这不是本类独有的问题——Journal 与
 * `.part` 命名同样靠 `relPath + size`；要真正区分内容得读出哈希，代价是整文件读一遍。
 *
 * 排除项：`import-journal.txt`（续传日志，不是成品）、本文件与它的 `.tmp`、本工具命名的半成品
 * （`<stem>.<十进制 size>.part`，口径见 {@link PartResidue#isPartName}）。用户自己的 `foo.part`
 * 不算半成品，照常收录（#210）。
 * 只收普通文件且 `size > 0`。
 *
 * **消费方**：本期（#20）没有。跳过判定仍由 {@link Journal} 负责，索引不参与；后续 UI 单
 * （首屏「新片」计数、网格已导入标记、过滤抽屉）在扫描完成后读本文件，按 `relPath` 与
 * 相机侧条目求交。
 *
 * 本类只用 JDK 类型，不 import android.*，便于在 JVM 上直接测。
 */
public final class ImportedIndex {

    /** 索引文件名，落在 `outDir`（`getFilesDir()/imported`，应用私有持久目录）。 */
    public static final String FILE_NAME = "imported-index.txt";

    /** 行式文本版本头；改格式必须同时改它，消费方据此拒绝未知版本。 */
    public static final String HEADER = "# wiredraw-import index v1";

    private static final String TMP_NAME = FILE_NAME + ".tmp";

    private final Map<String, Long> sizes = new LinkedHashMap<>();

    private ImportedIndex() {
    }

    /** 已收录的成品条数。 */
    public int size() {
        return sizes.size();
    }

    /** 扫描 `outDir` 得到当前磁盘镜像对应的索引（不写文件）。 */
    public static ImportedIndex scan(File outDir) {
        ImportedIndex index = new ImportedIndex();
        if (outDir == null || !outDir.isDirectory()) return index;
        collect(outDir, "", index.sizes);
        return index;
    }

    private static void collect(File dir, String prefix, Map<String, Long> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File kid : kids) {
            String name = kid.getName();
            if (excluded(name)) continue;
            String relPath = prefix.isEmpty() ? name : prefix + "/" + name;
            if (kid.isDirectory()) {
                collect(kid, relPath, out);
            } else if (kid.isFile() && kid.length() > 0) {
                out.put(relPath, kid.length());
            }
        }
    }

    /**
     * 这些名字不是成品：续传日志、索引自身与临时文件、本工具命名的半成品。
     *
     * 半成品的口径见 {@link PartResidue#isPartName}（`<stem>.<十进制 size>.part`）：只有本工具
     * 自己生成的名字才算，用户自己的 `foo.part` 是普通文件，照常收录（#210）。
     */
    static boolean excluded(String name) {
        return name.equals(FILE_NAME)
                || name.equals(TMP_NAME)
                || name.equals(RoundFiles.JOURNAL_NAME)
                || PartResidue.isPartName(name);
    }

    /**
     * 扫一遍 `outDir` 并原子重写索引文件，返回条数。
     *
     * 先写同目录 `.tmp` 再 `ATOMIC_MOVE` 替换：半截写入不会留下「半个索引」。
     * 文件按 `relPath` 排序，`run-as cat` 出来的内容可逐行比对。
     */
    public static int save(File outDir) throws IOException {
        ImportedIndex index = scan(outDir);
        index.write(outDir);
        return index.size();
    }

    private void write(File outDir) throws IOException {
        Dirs.ensure(outDir);
        File target = new File(outDir, FILE_NAME);
        File tmp = new File(outDir, TMP_NAME);
        List<String> keys = new ArrayList<>(sizes.keySet());
        Collections.sort(keys);
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            writer.write(HEADER);
            writer.write('\n');
            for (String key : keys) {
                writer.write(Long.toString(sizes.get(key)));
                writer.write('\t');
                // relPath 里的 tab / 换行会破坏行式格式，复用 Journal 的清洗口径。
                writer.write(Journal.sanitize(key));
                writer.write('\n');
            }
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
