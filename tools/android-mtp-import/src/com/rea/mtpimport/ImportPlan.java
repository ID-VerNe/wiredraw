package com.rea.mtpimport;

import java.util.ArrayList;
import java.util.List;

/**
 * 待下载清单：由勾选集合固化而来，之后不再受界面勾选变化影响。
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
     * 勾选 → 清单。
     *
     * 空勾选等价于全选（issue #80）：照片墙刚出现、用户一张都没勾时，主 CTA 的文案就是
     * `Import all N`（UI-SPEC `T-01` 的默认批量路径：已选 0，应用内点一次即开始）。所以这里必须
     * 兜底成「照片墙的全部 ARW」——否则 `Main.doStart()` 拿到空计划会直接 return，点击毫无反应。
     *
     * 只收 ARW：主 CTA 的分母是照片数（`C-08` / `U-53`），而 catalog 里还挂着同名 JPG
     * （真机扫描口径 511 ARW + 512 JPEG）；收 JPG 会让 `Import all 511` 实际传 1023 个文件。
     * 排序沿用 `Selection.fileHandles()` 的 relPath 口径，落盘顺序与续传日志保持一致。
     */
    public static ImportPlan of(Catalog catalog, Selection selection) {
        List<Catalog.Entry> picked = new ArrayList<>();
        List<Integer> handles = selection.fileHandles();
        if (handles.isEmpty()) {
            for (Catalog.Entry e : catalog.all()) {
                if (!e.isFolder() && e.isRaw()) picked.add(e);
            }
            picked.sort((a, b) -> a.relPath.compareTo(b.relPath));
        } else {
            for (int h : handles) {
                Catalog.Entry e = catalog.get(h);
                if (e == null || e.isFolder()) continue;
                picked.add(e);
            }
        }
        ImportPlan p = new ImportPlan();
        for (Catalog.Entry e : picked) {
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
}
