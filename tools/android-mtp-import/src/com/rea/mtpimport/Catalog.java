package com.rea.mtpimport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 只读目录树：一次扫描的产物，之后所有勾选/计划都在内存里做，不再碰相机。
 *
 * 为什么要有这一层：卡里 1526 个对象逐个 getObjectInfo 约 39 ms，
 * 全量约 59 秒（见 tools/android-mtp-probe/README.md 坑 2）。若每次勾选都重扫，
 * 「选目录」这个交互根本没法用。
 *
 * 本类只用 JDK 类型，不 import android.*，便于在 JVM 上直接测。
 */
public final class Catalog {

    public static final int FMT_FOLDER = 0x3001;
    public static final int FMT_ARW = 0xB101;
    public static final int FMT_JPEG = 0x3801;

    private static final Comparator<Entry> ORDER =
            Comparator.comparing((Entry e) -> !e.isFolder()).thenComparing(e -> e.name);

    /** 一个 MTP 对象（文件夹或文件）。relPath 由 {@link #build()} 依 parent 链算出。 */
    public static final class Entry {
        public final int handle;
        public final int parent;
        public final int format;
        public final long size;
        public final String name;
        public String relPath;

        public Entry(int handle, int parent, int format, long size, String name) {
            this.handle = handle;
            this.parent = parent;
            this.format = format;
            this.size = size;
            this.name = name == null ? "" : name;
        }

        public boolean isFolder() {
            return format == FMT_FOLDER;
        }

        /** ARW 按格式码判，同时兼容后缀——探针实测两者都有用（见 Main.java:258 的同款判断）。 */
        public boolean isRaw() {
            return format == FMT_ARW || name.toUpperCase(Locale.US).endsWith(".ARW");
        }

        @Override
        public String toString() {
            return (relPath == null ? name : relPath) + (isFolder() ? "/" : " (" + size + ")");
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Map<Integer, Entry> byHandle = new LinkedHashMap<>();
    private final Map<Integer, List<Entry>> byParent = new LinkedHashMap<>();
    private boolean built;

    public void add(Entry e) {
        if (built) throw new IllegalStateException("build() 之后不能再 add");
        if (byHandle.containsKey(e.handle)) return;   // 同一句柄重复枚举时去重（探针遇到过重复句柄）
        entries.add(e);
        byHandle.put(e.handle, e);
        byParent.computeIfAbsent(e.parent, k -> new ArrayList<>()).add(e);
    }

    /** 解析 relPath、把子节点按「文件夹在前、再按名字」排序；返回 this 便于链式调用。 */
    public Catalog build() {
        for (Entry e : entries) {
            e.relPath = resolvePath(e);
        }
        for (List<Entry> kids : byParent.values()) {
            kids.sort(ORDER);
        }
        built = true;
        return this;
    }

    private String resolvePath(Entry e) {
        StringBuilder sb = new StringBuilder(e.name);
        Set<Integer> seen = new HashSet<>();
        seen.add(e.handle);
        Entry cur = e;
        while (true) {
            Entry up = byHandle.get(cur.parent);
            // parent 为 0 或不在本次枚举里（跨存储的顶层对象）就停在这个名字上
            if (up == null || !seen.add(up.handle)) break;
            sb.insert(0, up.name + "/");
            cur = up;
        }
        return sb.toString();
    }

    public List<Entry> all() {
        return new ArrayList<>(entries);
    }

    public Entry get(int handle) {
        return byHandle.get(handle);
    }

    /** 根节点用 parentHandle = 0。 */
    public List<Entry> childrenOf(int parentHandle) {
        List<Entry> kids = byParent.get(parentHandle);
        return kids == null ? new ArrayList<>() : new ArrayList<>(kids);
    }

    /** 直接挂在 parentHandle 下的全部后代（不含自身、含文件夹）。 */
    public List<Entry> descendantsOf(int folderHandle) {
        List<Entry> out = new ArrayList<>();
        List<Entry> stack = new ArrayList<>(childrenOf(folderHandle));
        while (!stack.isEmpty()) {
            Entry e = stack.remove(stack.size() - 1);
            out.add(e);
            if (e.isFolder()) stack.addAll(childrenOf(e.handle));
        }
        return out;
    }

    public List<Entry> roots() {
        return childrenOf(0);
    }

    public int folderCount() {
        int n = 0;
        for (Entry e : entries) if (e.isFolder()) n++;
        return n;
    }

    public int fileCount() {
        return entries.size() - folderCount();
    }

    /** 所有非文件夹对象的字节数之和。 */
    public long fileBytes() {
        long n = 0;
        for (Entry e : entries) if (!e.isFolder()) n += e.size;
        return n;
    }

    public long rawBytes() {
        long n = 0;
        for (Entry e : entries) if (!e.isFolder() && e.isRaw()) n += e.size;
        return n;
    }
}
