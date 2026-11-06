package com.rea.mtpimport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 待下载清单：由「当前 filter 可见集合 + 勾选集合 + 档位载荷」固化而来，之后不再受界面变化影响。
 * 传输进度、断点续传都以它为准（中途改勾选不改变正在跑的这轮）。
 */
public final class ImportPlan {

    public static final class Item {
        public final int handle;
        public final String relPath;
        public final long size;

        public Item(int handle, String relPath, long size) {
            this.handle = handle;
            this.relPath = relPath == null ? "" : relPath;
            this.size = size;
        }

        @Override
        public String toString() {
            return relPath + " (" + size + ")";
        }
    }

    private final List<Item> items = new ArrayList<>();
    private long totalBytes;

    /**
     * 可见集合 + 勾选 → 清单（UI-SPEC `C-08` / `C-10` / `V-32`，issue #83）。
     *
     * 口径：
     * 1. **只考虑当前 filter 可见的照片单元**（`visible`），隐藏的已选项保留在界面上，但不进本轮计划；
     * 2. 可见已选数为 0（含「总已选 ≥1 但全被 filter 隐藏」）时，等价于「导入全部可见单元」——
     *    这正是主 CTA `Import all N` 的含义（`T-01` 默认批量路径，issue #80 的口径修正）；
     * 3. 每个单元按 `export` 取**一个**文件：`All` / 日期档 JPG 优先、缺失回退 ARW；`RAW` 档 ARW；`JPG` 档 JPG。
     *
     * 因此每个单元最多进 1 个文件，不会把同名 ARW + JPG 一起吞进计划（真机 512 单元 = 512 个文件）；
     * 只有档位与该单元不配套（`export` 返回 null）时才会少一条，正常可见集合不会出现这种组合。
     * 排序沿用 relPath 口径，落盘顺序与续传日志保持一致。
     */
    public static ImportPlan of(Catalog catalog, Selection selection,
                                List<PhotoWall.Unit> visible, PhotoWall.Export export) {
        ImportPlan p = new ImportPlan();
        if (catalog == null || selection == null || visible == null || visible.isEmpty()) return p;

        List<PhotoWall.Unit> chosen = new ArrayList<>();
        for (PhotoWall.Unit u : visible) {
            if (u.isChecked(selection)) chosen.add(u);
        }
        if (chosen.isEmpty()) chosen.addAll(visible);   // 空勾选兜底 = 当前可见集合（issue #80 / #83）

        chosen.sort((a, b) -> {
            Catalog.Entry ea = a.export(export);
            Catalog.Entry eb = b.export(export);
            String pa = ea == null ? a.relPath : (ea.relPath == null ? ea.name : ea.relPath);
            String pb = eb == null ? b.relPath : (eb.relPath == null ? eb.name : eb.relPath);
            return pa.compareTo(pb);
        });
        for (PhotoWall.Unit u : chosen) {
            Catalog.Entry e = u.export(export);
            if (e == null) continue;   // 档位不适用（理论上不会出现在可见集合里）
            p.items.add(new Item(e.handle, e.relPath == null ? e.name : e.relPath, e.size));
            p.totalBytes += e.size;
        }
        return p;
    }

    public List<Item> items() {
        return new ArrayList<>(items);
    }

    public int count() {
        return items.size();
    }

    public long totalBytes() {
        return totalBytes;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /**
     * 两份清单是否指向同一批文件（issue #57：恢复暂停轮次前先比较）。
     *
     * 跨会话的稳定主键是 `relPath + size`，与 Journal 的跳过口径一致；句柄只在当前会话有效。
     * 顺序不参与比较（{@link #of} 已按 relPath 排序），同一集合不同顺序仍算同一批。
     * 任一参数为 null 返回 false：没有「暂停轮次的清单」就谈不上恢复。
     */
    public static boolean sameFileSet(ImportPlan a, ImportPlan b) {
        if (a == null || b == null) return false;
        if (a.items.size() != b.items.size()) return false;
        Map<String, Integer> diff = new HashMap<>();
        for (Item it : a.items) diff.merge(key(it), 1, Integer::sum);
        for (Item it : b.items) diff.merge(key(it), -1, Integer::sum);
        // 每个键的计数都归零才是同一批；多出来的键会留下正数、缺的键留下负数。
        for (Integer n : diff.values()) if (n != 0) return false;
        return true;
    }

    private static String key(Item it) {
        return it.relPath + '\u0000' + it.size;
    }
}
