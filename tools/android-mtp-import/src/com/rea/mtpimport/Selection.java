package com.rea.mtpimport;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 勾选状态。勾一个文件夹等于把它的全部子孙文件都勾上（一次性写进集合）。
 *
 * 目录展开在 {@link #setChecked} 里做完，{@link #fileHandles()} 只做过滤 + 排序。
 * 这样「取消勾目录」是一批删除，语义干净；勾过子文件再勾父目录也不会互相打架。
 * 卡里满打满算 1526 个句柄，一个 int 集合的代价可以忽略。
 */
public final class Selection {

    private final Catalog catalog;
    private final Set<Integer> checked = new LinkedHashSet<>();

    public Selection(Catalog catalog) {
        this.catalog = catalog;
    }

    public boolean isChecked(int handle) {
        return checked.contains(handle);
    }

    /** 勾选/取消；文件夹会连带其所有后代的勾选状态。 */
    public void setChecked(int folderOrFileHandle, boolean on) {
        apply(folderOrFileHandle, on);
        Catalog.Entry e = catalog.get(folderOrFileHandle);
        if (e != null && e.isFolder()) {
            for (Catalog.Entry d : catalog.descendantsOf(folderOrFileHandle)) {
                apply(d.handle, on);
            }
        }
    }

    private void apply(int handle, boolean on) {
        if (on) checked.add(handle);
        else checked.remove(handle);
    }

    public void toggle(int handle) {
        setChecked(handle, !checked.contains(handle));
    }

    public void selectAll() {
        checked.clear();
        for (Catalog.Entry e : catalog.all()) checked.add(e.handle);
    }

    public void clear() {
        checked.clear();
    }

    /** 真正要下载的文件句柄：排除文件夹自身、去重、按 relPath 排序。 */
    public List<Integer> fileHandles() {
        List<Catalog.Entry> hit = new ArrayList<>();
        for (Catalog.Entry e : catalog.all()) {
            if (!e.isFolder() && checked.contains(e.handle)) hit.add(e);
        }
        hit.sort((a, b) -> a.relPath.compareTo(b.relPath));
        List<Integer> out = new ArrayList<>(hit.size());
        for (Catalog.Entry e : hit) out.add(e.handle);
        return out;
    }
}
