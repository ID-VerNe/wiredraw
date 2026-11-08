package com.rea.mtpimport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 导入完成日志：记住「哪个文件已经完成」，让下次开工能跳过。
 *
 * 续传已停用（issue #177）：这里不再提供「从 `.part` 长度推导起点」的能力；
 * 记录里只有完成态，残留半成品一律视为不可信。并且「日志说完成」不等于「成品还在」，
 * 编排层必须再对磁盘做一次存在性 + 尺寸核对才会跳过。
 *
 * 格式故意用行式文本而不是 JSON：不引依赖、出了问题能直接 cat 出来看。
 *
 * <pre>
 * # wiredraw-import journal v1
 * 12345   7340032   24389632   DCIM/100MSDCF/DSC02183.ARW
 * </pre>
 *
 * 注意：句柄是**单次会话内**有效的（MTP 的 object handle 不跨会话稳定），而且下一次会话
 * 里同一个 handle 可能指向另一个文件。所以账本主键是 `relPath`，`size` 留在 {@link Rec}
 * 里做同路径不同文件的判别；handle 只作为文件格式的一列保留，不再作索引，也没有任何
 * 按句柄查询的 API（#209 删掉了 `doneBytes(int)` / `isComplete(int,long)` 这两条死路径）。
 */
public final class Journal {

    public static final String HEADER = "# wiredraw-import journal v1";

    private static final class Rec {
        int handle;
        long done;
        long size;
        String relPath;
    }

    private final Map<String, Rec> recs = new LinkedHashMap<>();

    /**
     * 按「大小 + 相对路径」查已落盘字节数。
     *
     * MTP handle 不跨会话稳定，重连后同一个文件会换句柄；跨会话的完成判定必须走这条，
     * 否则可能把别的文件（或上一次会话里复用同一 handle 的文件）的进度当成自己的。
     * 只有大小与 relPath 都匹配才认。
     */
    public long doneBytes(String relPath, long size) {
        if (relPath == null || relPath.isEmpty() || size <= 0) return 0;
        Rec r = recs.get(relPath);
        if (r == null || r.size != size) return 0;
        return r.done;
    }

    /** 记录进度。doneBytes 被钳在 [0, size]，避免写进不可能的值。 */
    public void markDone(int handle, long doneBytes, long size, String relPath) {
        String safePath = relPath == null ? "" : relPath;
        // 主键是 relPath：下一次会话同一 handle 指向另一个文件时，不能把旧记录冲掉。
        String key = safePath.isEmpty() ? "#" + handle : safePath;
        Rec r = recs.get(key);
        if (r == null) {
            r = new Rec();
            recs.put(key, r);
        }
        r.handle = handle;
        r.size = Math.max(0, size);
        r.done = Math.min(r.size, Math.max(0, doneBytes));
        r.relPath = safePath;
    }

    /**
     * 跨会话完成判定：按「大小 + 相对路径」找记录，不依赖 handle。
     * 编排层不要直接用它决定跳过——账本说了完成，成品仍可能没了，统一走 {@link SkipRule}。
     *
     * **#177**：本方法只看日志。调用方必须再确认成品仍在磁盘且长度吻合，才能据此跳过。
     */
    public boolean isComplete(String relPath, long size) {
        if (relPath == null || relPath.isEmpty() || size <= 0) return false;
        Rec r = recs.get(relPath);
        if (r == null || r.size != size) return false;
        return r.done >= r.size;
    }

    public int size() {
        return recs.size();
    }

    /**
     * 半成品文件路径：`<relPath 压平>.<size>.part`，与完成判定 {@link #isComplete(String, long)}
     * 的 `relPath + size` 口径完全一致。
     *
     * 为什么不用句柄命名（PC / issue #50）：MTP handle 不跨会话稳定，重连后同一个句柄
     * 可能指向另一个文件；按句柄命名的 `.part` 在旧续传逻辑里会接错文件。
     *
     * 为什么带 `size`（B-1）：只按 `relPath` 命名时，换卡 / 格式化后编号复用 / 卡上文件被
     * 替换都会让「同路径、不同内容」的新对象看见旧半成品。size 进文件名后，异 size 的残留
     * 不会与本对象混淆；续传停用后（#177）所有残留都不可信，最终清理由 `MtpTransfer` 与
     * `PartResidue` 负责。
     */
    public String partPath(String outDir, String relPath, long size) {
        String dir = outDir == null ? "" : outDir.replace('\\', '/');
        while (dir.endsWith("/")) dir = dir.substring(0, dir.length() - 1);
        return dir + "/" + partName(relPath) + "." + size + ".part";
    }

    /** 半成品文件名：路径分隔符换成下划线，跨会话稳定且不会把文件写到子目录外。 */
    static String partName(String relPath) {
        String s = relPath == null ? "" : relPath;
        s = sanitize(s.replace('\\', '_').replace('/', '_'));
        return s.isEmpty() ? "unnamed" : s;
    }

    public void save(OutputStream out) throws IOException {
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        w.write(HEADER);
        w.write('\n');
        for (Map.Entry<String, Rec> e : recs.entrySet()) {
            Rec r = e.getValue();
            w.write(r.handle + "\t" + r.done + "\t" + r.size + "\t" + sanitize(r.relPath) + "\n");
        }
        w.flush();
    }

    /** 坏行一律跳过，不抛异常——日志损坏不该让整个导片流程起不来。 */
    public void load(InputStream in) throws IOException {
        recs.clear();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = br.readLine()) != null) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            String[] f = s.split("\t");
            if (f.length < 4) continue;
            try {
                int handle = Integer.parseInt(f[0].trim());
                long done = Long.parseLong(f[1].trim());
                long size = Long.parseLong(f[2].trim());
                markDone(handle, done, size, f[3]);
            } catch (NumberFormatException bad) {
                // 坏行跳过
            }
        }
    }

    static String sanitize(String relPath) {
        if (relPath == null) return "";
        return relPath.replace('\t', '_').replace('\n', '_').replace('\r', '_');
    }
}
