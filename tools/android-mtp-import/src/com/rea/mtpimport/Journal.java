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
 * 断点续传日志：记住「哪个文件已经落了多少字节」，让下次开工能跳过或从中间接着传。
 *
 * 格式故意用行式文本而不是 JSON：不引依赖、出了问题能直接 cat 出来看。
 *
 * <pre>
 * # wiredraw-import journal v1
 * 12345   7340032   24389632   DCIM/100MSDCF/DSC02183.ARW
 * </pre>
 *
 * 注意：句柄是**单次会话内**有效的（MTP 的 object handle 不跨会话稳定），而且下一次会话
 * 里同一个 handle 可能指向另一个文件。所以索引主键是 `relPath`，`size` 留在 {@link Rec}
 * 里做同路径不同文件的判别；handle 只作为文件格式的一列保留，不再作索引。
 * `doneBytes(int)` / `isComplete(int,long)` 仍保留给同会话内的快路径，内部改成扫描。
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

    /** 已落盘字节数；未知返回 0。同会话快路径：扫描记录里的 handle。 */
    public long doneBytes(int handle) {
        for (Rec r : recs.values()) {
            if (r.handle == handle) return r.done;
        }
        return 0;
    }

    /**
     * 按「大小 + 相对路径」查已落盘字节数。
     *
     * MTP handle 不跨会话稳定，重连后同一个文件会换句柄；续传的 resumeFrom 必须走这条，
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

    /** 已传字节数已达预期大小，整个文件可跳过。同会话快路径：扫描 handle。 */
    public boolean isComplete(int handle, long size) {
        for (Rec r : recs.values()) {
            if (r.handle != handle) continue;
            if (size > 0 && r.size != size) continue;   // 大小变了（同名不同文件）就不算完成
            if (r.done >= r.size && r.size > 0) return true;
        }
        return false;
    }

    /**
     * 跨会话完成判定：按「大小 + 相对路径」找记录，不依赖 handle。
     * 导入编排层应优先用这条；handle 版本只作同会话内的快路径。
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
     * 半成品文件路径：同一目录下按 `relPath` 稳定命名，避免与最终文件名抢位。
     *
     * 为什么不再用句柄命名（PC / issue #50）：MTP handle 不跨会话稳定，重连后同一个句柄
     * 可能指向另一个文件；按句柄命名的 `.part` 在续传时会接错文件。`relPath` 才是跨会话
     * 稳定的身份（与完成判定同源）。
     */
    public String partPath(String outDir, String relPath) {
        String dir = outDir == null ? "" : outDir.replace('\\', '/');
        while (dir.endsWith("/")) dir = dir.substring(0, dir.length() - 1);
        return dir + "/" + partName(relPath) + ".part";
    }

    /** 半成品文件名：路径分隔符换成下划线，跨会话稳定且不会把文件写到子目录外。 */
    static String partName(String relPath) {
        String s = relPath == null ? "" : relPath;
        s = sanitize(s.replace('\\', '_').replace('/', '_'));
        return s.isEmpty() ? "unnamed" : s;
    }

    /**
     * 由已落盘的 `.part` 长度推导续传起点（纯判定，JVM 自检覆盖）。
     *
     * - `partLength > size`：半成品比目标还大，来源不可信（换了文件 / 写坏了），按 0 从头传；
     * - `partLength == size`：半成品已满，传输层直接 rename 落盘；
     * - 其余原样（0 表示从头）。
     */
    public static long resumeFrom(long partLength, long size) {
        if (partLength <= 0 || size <= 0) return 0;
        if (partLength > size) return 0;
        return partLength;
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
