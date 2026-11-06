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

    private Catalog catalog;
    private final Set<Integer> checked = new LinkedHashSet<>();

    public Selection(Catalog catalog) {
        this.catalog = catalog;
    }

    /**
     * 渐进发布（issue #60）：扫描线程不断把更大的快照推上来，已勾选句柄必须保留。
     *
     * 只换 Catalog 引用，不动 checked——快照只增不减，旧句柄始终有效；
     * `fileHandles()` 会按新 Catalog 重新过滤，新增对象随下一次勾选进入集合。
     */
    public void retarget(Catalog next) {
        if (next == null) throw new IllegalArgumentException("retarget(null)");
        this.catalog = next;
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

    /**
     * 日期组选（issue #62）：组内是否全部已勾选。
     *
     * 空组返回 false——没有可选对象时不算「已全选」，避免长按空组后语义变成「取消」。
     */
    public boolean allCheckedOf(List<Catalog.Entry> entries) {
        if (entries == null || entries.isEmpty()) return false;
        for (Catalog.Entry e : entries) {
            if (!checked.contains(e.handle)) return false;
        }
        return true;
    }

    /** 日期组选（issue #62）：批量勾选/取消，逐项复用 {@link #setChecked} 的文件/文件夹语义。 */
    public void setCheckedAllOf(List<Catalog.Entry> entries, boolean on) {
        if (entries == null) return;
        for (Catalog.Entry e : entries) setChecked(e.handle, on);
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
