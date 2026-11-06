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
        /** 该对象所属的 MTP storageId；0 表示调用方未提供（旧构造器与既有用例）。 */
        public final int storageId;
        public String relPath;

        public Entry(int handle, int parent, int format, long size, String name) {
            this(handle, parent, format, size, name, 0);
        }

        public Entry(int handle, int parent, int format, long size, String name, int storageId) {
            this.handle = handle;
            this.parent = parent;
            this.format = format;
            this.size = size;
            this.name = name == null ? "" : name;
            this.storageId = storageId;
        }

        public boolean isFolder() {
            return format == FMT_FOLDER;
        }

        /** ARW 按格式码判，同时兼容后缀——探针实测两者都有用（见探针 `com.rea.mtpprobe.Main#probe()` 的同款判断）。 */
        public boolean isRaw() {
            return format == FMT_ARW || name.toUpperCase(Locale.US).endsWith(".ARW");
        }

        /** JPEG 同样格式码与后缀双判（`FMT_JPEG` 实测出现在索尼机身写出的同名 JPG 上）。 */
        public boolean isJpeg() {
            if (isFolder() || isRaw()) return false;
            String upper = name.toUpperCase(Locale.US);
            return format == FMT_JPEG || upper.endsWith(".JPG") || upper.endsWith(".JPEG");
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

    /**
     * 渐进发布用快照：复制当前已发现的对象并独立 {@link #build()}，**不改动本源对象**。
     *
     * 必须是深拷贝：Entry 的 relPath 在 build 时会被改写，若快照共用 Entry，
     * 后续扫描长出的祖先会把已经交给界面的旧快照 relPath 一起改掉（issue #60）。
     * 调用点只在扫描线程上、每 200 个对象一次，1526 个对象的复制代价可以忽略。
     */
    public Catalog snapshot() {
        Catalog copy = new Catalog();
        for (Entry e : entries) {
            copy.add(new Entry(e.handle, e.parent, e.format, e.size, e.name, e.storageId));
        }
        return copy.build();
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

    /**
     * 给定集合里，同一 relPath 跨 storage 重复的文件（#54）。
     *
     * 只在该集合内判定：两张卡同时插着但只选中其中一个时没有争用，不应算冲突。
     */
    public List<Entry> storageConflicts(List<Entry> selected) {
        Map<String, List<Entry>> byPath = new LinkedHashMap<>();
        if (selected == null) return new ArrayList<>();
        for (Entry e : selected) {
            if (e == null || e.isFolder() || e.relPath == null) continue;
            List<Entry> group = byPath.get(e.relPath);
            if (group == null) {
                group = new ArrayList<>();
                byPath.put(e.relPath, group);
            }
            group.add(e);
        }
        List<Entry> out = new ArrayList<>();
        for (List<Entry> group : byPath.values()) {
            Set<Integer> storages = new HashSet<>();
            for (Entry e : group) storages.add(e.storageId);
            if (storages.size() > 1) out.addAll(group);
        }
        return out;
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

    /**
     * 树视图渲染窗口：只收集前 {@code limit} 行，同时给出**全树节点总数**。
     *
     * 渲染上限与计数必须分开：底部「还有 N 项未显示」的 N = {@link #total} − {@link #rows}.size()，
     * 不能拿截断集合的差值（UI-SPEC 的 C-09 / U-13）。issue #15 曾因两者混在一起，
     * 提示恒显示「还有 200 项未显示」，而真实缺口是 726。
     */
    public static final class Flat {
        /** 实际要渲染的行，最多 limit 条，深度优先、父项先于子项。 */
        public final List<Entry> rows;
        /** 全树节点数，不受 limit 截断影响。 */
        public final int total;

        Flat(List<Entry> rows, int total) {
            this.rows = rows;
            this.total = total;
        }

        /** 未渲染的节点数；底部「还有 N 项未显示」的 N 必须取它（#15）。 */
        public int remaining() {
            return total - rows.size();
        }
    }

    /**
     * 深度优先摊平目录树。遍历不会在 limit 处提前停止——只有「收集」受 limit 约束，
     * 计数必须走完全树，否则又回到 #15 的截断差值。
     */
    public Flat flatten(int limit) {
        List<Entry> rows = new ArrayList<>();
        int total = collect(roots(), limit, rows);
        return new Flat(rows, total);
    }

    private int collect(List<Entry> list, int limit, List<Entry> rows) {
        int n = 0;
        for (Entry e : list) {
            n++;
            if (rows.size() < limit) rows.add(e);
            if (e.isFolder()) n += collect(childrenOf(e.handle), limit, rows);
        }
        return n;
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

    /** ARW 张数（`RAW` 档的单元数口径；照片墙一格按照片单元计，见 {@code PhotoWall}）。 */
    public int rawCount() {
        int n = 0;
        for (Entry e : entries) if (!e.isFolder() && e.isRaw()) n++;
        return n;
    }

}
