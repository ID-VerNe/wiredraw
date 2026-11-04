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

    public static ImportPlan of(Catalog catalog, Selection selection) {
        ImportPlan p = new ImportPlan();
        for (int h : selection.fileHandles()) {
            Catalog.Entry e = catalog.get(h);
            if (e == null || e.isFolder()) continue;
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
