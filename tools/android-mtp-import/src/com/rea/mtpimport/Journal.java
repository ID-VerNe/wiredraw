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
 * 断点续传日志：记住「哪个句柄已经落了多少字节」，让下次开工能跳过或从中间接着传。
 *
 * 格式故意用行式文本而不是 JSON：不引依赖、出了问题能直接 cat 出来看。
 *
 * <pre>
 * # wiredraw-import journal v1
 * 12345   7340032   24389632   DCIM/100MSDCF/DSC02183.ARW
 * </pre>
 *
 * 注意：句柄是**单次会话内**有效的（MTP 的 object handle 不跨会话稳定），
 * 所以 size + relPath 才是跨会话重识别的主键，handle 只作同会话内的快路径。
 * 跨会话续传要先按 relPath 重新拿到新 handle —— 这一步在 {@link ResumableTransfer}
 * 的真实现里做，本轮框架不做。
 */
public final class Journal {

    public static final String HEADER = "# wiredraw-import journal v1";

    private static final class Rec {
        long done;
        long size;
        String relPath;
    }

    private final Map<Integer, Rec> recs = new LinkedHashMap<>();

    /** 已落盘字节数；未知返回 0。 */
    public long doneBytes(int handle) {
        Rec r = recs.get(handle);
        return r == null ? 0 : r.done;
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
        for (Rec r : recs.values()) {
            if (r == null || r.size != size || !relPath.equals(r.relPath)) continue;
            return r.done;
        }
        return 0;
    }

    /** 记录进度。doneBytes 被钳在 [0, size]，避免写进不可能的值。 */
    public void markDone(int handle, long doneBytes, long size, String relPath) {
        Rec r = recs.computeIfAbsent(handle, k -> new Rec());
        r.size = Math.max(0, size);
        r.done = Math.min(r.size, Math.max(0, doneBytes));
        r.relPath = relPath == null ? "" : relPath;
    }

    /** 已传字节数已达预期大小，整个文件可跳过。 */
    public boolean isComplete(int handle, long size) {
        Rec r = recs.get(handle);
        if (r == null) return false;
        if (size > 0 && r.size != size) return false;   // 大小变了（同名不同文件）就不算完成
        return r.done >= r.size && r.size > 0;
    }

    /**
     * 跨会话完成判定：按「大小 + 相对路径」找记录，不依赖 handle。
     * 导入编排层应优先用这条；handle 版本只作同会话内的快路径。
     */
    public boolean isComplete(String relPath, long size) {
        if (relPath == null || relPath.isEmpty() || size <= 0) return false;
        for (Rec r : recs.values()) {
            if (r == null || r.size != size || !relPath.equals(r.relPath)) continue;
            if (r.done >= r.size) return true;
        }
        return false;
    }

    public int size() {
        return recs.size();
    }

    /** 半成品文件路径：同一目录下以句柄命名，避免与最终文件名抢位。 */
    public String partPath(String outDir, int handle) {
        String dir = outDir == null ? "" : outDir.replace('\\', '/');
        while (dir.endsWith("/")) dir = dir.substring(0, dir.length() - 1);
        return dir + "/" + handle + ".part";
    }

    public void save(OutputStream out) throws IOException {
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        w.write(HEADER);
        w.write('\n');
        for (Map.Entry<Integer, Rec> e : recs.entrySet()) {
            Rec r = e.getValue();
            w.write(e.getKey() + "\t" + r.done + "\t" + r.size + "\t" + sanitize(r.relPath) + "\n");
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
