package com.rea.mtpimport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 照片墙：把 {@link Catalog} 里的 ARW / JPG 合成「照片单元」，再按「日期组」聚合。
 *
 * 照片单元（UI-SPEC §0.6 / issue #83）：同一 MTP 父目录下、文件名去扩展名相同的最多一个
 * ARW + 一个 JPG。照片墙一格 = 一个单元；组头张数按单元计（`V-12`）。
 *
 * 单元携带两个句柄，像素来源与导出载荷由 {@link Export} 决定（`V-32`）：
 * {@link Export#JPG_FIRST} 优先 JPG、缺失回退 ARW；{@link Export#RAW} 只取 ARW；
 * {@link Export#JPG} 只取 JPG。
 * filter 只决定显示哪些单元，不改变像素来源——这条口径在 UI 层落地，本类只提供数据。
 *
 * UI-SPEC §0.6 的分组依据优先 EXIF / 文件修改时间，两者都未验证；两者都取不到时
 * 按 MTP 目录分组（目录名即日期）。{@link Catalog.Entry} 只有 handle/parent/format/size/name，
 * 没有日期字段，所以这里**只实现目录名这一条**，并在组头上按目录名给标签。
 *
 * 组内数量与容量是**组内聚合**（C-05：GB 2 位、MB ≤1 位）。
 * 本类不 import android.*，可在 JVM 上直接测。
 */
public final class PhotoWall {

    /** 导出载荷口径（`V-32`）。 */
    public enum Export {
        /** `All` / 日期档：JPG 优先、缺失回退 ARW。 */
        JPG_FIRST,
        /** `RAW` 档：只要 ARW。 */
        RAW,
        /** `JPG` 档：只要 JPG。 */
        JPG
    }

    /** filter 视图的单元筛选；返回 null 的组表示没有可见单元。 */
    public interface UnitFilter {
        boolean test(Unit unit);
    }

    /**
     * 一个照片单元：同父目录、同 stem 的 ARW 与 JPG（各可空，至少一个非空）。
     * `relPath` 取实际存在的那一个，用于排序与落盘。
     */
    public static final class Unit {
        public final String key;
        public final Catalog.Entry arw;
        public final Catalog.Entry jpg;
        public final String relPath;

        Unit(String key, Catalog.Entry arw, Catalog.Entry jpg) {
            this.key = key;
            this.arw = arw;
            this.jpg = jpg;
            Catalog.Entry base = arw != null ? arw : jpg;
            this.relPath = base.path();
        }

        public boolean hasArw() {
            return arw != null;
        }

        public boolean hasJpg() {
            return jpg != null;
        }

        /** 网格 / 看图页首帧的像素来源：同名 JPG 优先，缺失回退 ARW（`V-32`）。 */
        public Catalog.Entry preview() {
            return jpg != null ? jpg : arw;
        }

        /** 当前档位下真正要导出的文件；该档位不适用时返回 null。 */
        public Catalog.Entry export(Export mode) {
            switch (mode) {
                case RAW:
                    return arw;
                case JPG:
                    return jpg;
                default:
                    return jpg != null ? jpg : arw;
            }
        }

        public long exportBytes(Export mode) {
            Catalog.Entry e = export(mode);
            return e == null ? 0 : e.size;
        }

        /** 单元的全部句柄（ARW、JPG 各自独立参与勾选）。 */
        public List<Integer> handles() {
            List<Integer> out = new ArrayList<>(2);
            if (arw != null) out.add(arw.handle);
            if (jpg != null) out.add(jpg.handle);
            return out;
        }

        /** 单元已选 = 任一文件句柄已勾选（勾选时两个句柄一起写，见 `AppModel.toggleUnitSelection`）。 */
        public boolean isChecked(Selection selection) {
            if (selection == null) return false;
            for (int h : handles()) {
                if (selection.isChecked(h)) return true;
            }
            return false;
        }

        /** 网格缓存键：优先 JPG 句柄，缺失回退 ARW。 */
        public int previewHandle() {
            return preview().handle;
        }

        @Override
        public String toString() {
            return relPath;
        }
    }

    /** 一个日期组；photos 是照片单元，按 relPath 升序。 */
    public static final class Group {
        public final String key;
        public final String label;
        public final List<Unit> photos;

        Group(String key, String label, List<Unit> photos) {
            this.key = key;
            this.label = label;
            this.photos = Collections.unmodifiableList(photos);
        }

        public int count() {
            return photos.size();
        }

        /** 组内导出载荷字节：随当前档位变化（`V-16` / `V-32`）。 */
        public long bytes(Export mode) {
            long sum = 0;
            for (Unit u : photos) sum += u.exportBytes(mode);
            return sum;
        }

        /**
         * 组头聚合快照（#214 / F-09）：视觉组头与读屏文案共用这一份，
         * 页面不再各算一遍 `count()` / `bytes(mode)`。
         */
        public Summary summary(Export mode) {
            return new Summary(label, photos.size(), bytes(mode));
        }

        /** 组头（U-44）；内部走 {@link #summary}，与读屏文案同源。 */
        public String header(Export mode) {
            return summary(mode).header();
        }

        /** 只保留满足条件的单元；没有可见单元时返回 null（组头随之重算）。 */
        public Group select(UnitFilter filter) {
            List<Unit> keep = new ArrayList<>();
            for (Unit u : photos) {
                if (filter.test(u)) keep.add(u);
            }
            if (keep.isEmpty()) return null;
            return new Group(key, label, keep);
        }
    }

    /**
     * 日期组头的两种呈现（#214 / F-09）：视觉 `Jun 7 · 43 · 74.0 MB`（U-44）与
     * 读屏 `Date group Jun 7, 43 photos, 74.0 MB`（A-05）由同一份聚合快照产出。
     */
    public static final class Summary {
        public final String label;
        public final int count;
        public final long bytes;

        Summary(String label, int count, long bytes) {
            this.label = label;
            this.count = count;
            this.bytes = bytes;
        }

        /** 视觉组头（U-44）。 */
        public String header() {
            return UiText.groupHeader(label, count, bytes);
        }

        /** 读屏组头（A-05）。 */
        public String spoken() {
            return "Date group " + label + ", " + count + " photos, " + UiText.capacity(bytes);
        }
    }

    private final List<Group> groups;
    private final List<Unit> units;
    private final int photoCount;
    private final long photoBytes;

    private PhotoWall(List<Group> groups, List<Unit> units) {
        this.groups = Collections.unmodifiableList(groups);
        this.units = Collections.unmodifiableList(units);
        this.photoCount = units.size();
        long jpgFirst = 0;
        for (Unit u : units) jpgFirst += u.exportBytes(Export.JPG_FIRST);
        this.photoBytes = jpgFirst;
    }

    public List<Group> groups() {
        return groups;
    }

    /** 全部照片单元，按 relPath 升序（filter 与导出计划都以它为底）。 */
    public List<Unit> units() {
        return units;
    }

    /** 照片单元数（空态判定与组内张数口径）。 */
    public int photoCount() {
        return photoCount;
    }

    /** 全部单元按 `JPG_FIRST` 口径的载荷字节（`All` 档的默认导出量）。 */
    public long photoBytes() {
        return photoBytes;
    }

    /**
     * 可见组（#214 / F-02）：按可见单元的 {@link Unit#key} 集合从全部组重建，
     * 组头计数因此恒等于条目数。页面不再另起一条过滤链。
     *
     * 返回的组是新的 {@link Group}（只含可见单元）；不可见组与剪空的组都不出现。
     */
    public static List<Group> restrict(List<Group> all, Collection<String> unitKeys) {
        List<Group> out = new ArrayList<>();
        for (Group g : all) {
            Group kept = g.select(u -> unitKeys.contains(u.key));
            if (kept != null) out.add(kept);
        }
        return out;
    }

    public static PhotoWall of(Catalog catalog) {
        Map<String, UnitBuilder> byKey = new LinkedHashMap<>();
        for (Catalog.Entry e : catalog.all()) {
            if (e.isFolder()) continue;
            if (!e.isRaw() && !e.isJpeg()) continue;   // MP4 等非照片对象不进照片墙
            String key = e.parent + ":" + stemOf(e.name);
            UnitBuilder b = byKey.computeIfAbsent(key, k -> new UnitBuilder());
            if (e.isRaw()) {
                if (b.arw == null) b.arw = e;
            } else if (b.jpg == null) {
                b.jpg = e;
            }
        }

        List<Unit> units = new ArrayList<>(byKey.size());
        for (Map.Entry<String, UnitBuilder> item : byKey.entrySet()) {
            UnitBuilder b = item.getValue();
            if (b.arw == null && b.jpg == null) continue;
            units.add(new Unit(item.getKey(), b.arw, b.jpg));
        }
        units.sort((a, b) -> a.relPath.compareTo(b.relPath));

        // 按单元代表文件的父目录分组（ARW 优先，纯 JPG 单元用 JPG）。
        Map<String, List<Unit>> byFolder = new LinkedHashMap<>();
        for (Unit u : units) {
            Catalog.Entry base = u.arw != null ? u.arw : u.jpg;
            String folder = parentFolderOf(base);
            byFolder.computeIfAbsent(folder, k -> new ArrayList<>()).add(u);
        }
        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, List<Unit>> item : byFolder.entrySet()) {
            String folder = item.getKey();
            List<Unit> photos = item.getValue();
            photos.sort((a, b) -> a.relPath.compareTo(b.relPath));
            groups.add(new Group(folder, labelOf(folder), photos));
        }
        // 新的日期在前；非日期目录名按字典序倒排（同一次扫描内保持稳定）。
        groups.sort((a, b) -> b.key.compareTo(a.key));
        return new PhotoWall(groups, units);
    }

    private static final class UnitBuilder {
        Catalog.Entry arw;
        Catalog.Entry jpg;
    }

    static String stemOf(String name) {
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(0, dot)).toUpperCase(Locale.US);
    }

    /** relPath 的倒数第二段；没有父目录时返回空串。 */
    static String parentFolderOf(Catalog.Entry e) {
        String path = e.path();
        int slash = path.lastIndexOf('/');
        if (slash < 0) return "";
        String parent = path.substring(0, slash);
        int prev = parent.lastIndexOf('/');
        return prev < 0 ? parent : parent.substring(prev + 1);
    }

    /**
     * 目录名 → 组头标签。`20260607` / `20260607-1` 这类以 8 位日期开头的目录
     * 渲染成 `Jun 7`；其余按目录名原样（§0.6 的「按目录名」降级）。
     *
     * 这里是全仓唯一的「8 位日期头」解析（G10 / A16 / issue #220）：
     * 原先另有一份 `isoDateOf` 做同样的取头与判数字，但全仓 0 引用，已删除。
     */
    static String labelOf(String folder) {
        String digits = null;
        if (folder.length() >= 8) {
            String head = folder.substring(0, 8);
            boolean allDigits = true;
            for (int i = 0; i < head.length(); i++) {
                if (!Character.isDigit(head.charAt(i))) {
                    allDigits = false;
                    break;
                }
            }
            if (allDigits) digits = head;
        }
        if (digits == null) return folder.isEmpty() ? "Folder" : folder;
        int year = Integer.parseInt(digits.substring(0, 4));
        int month = Integer.parseInt(digits.substring(4, 6));
        int day = Integer.parseInt(digits.substring(6, 8));
        String label = UiText.monthDay(year, month, day);
        // 解析失败或月份越界时退回目录名，避免组头出现 `null`。
        return label == null ? folder : label;
    }

    /**
     * 计算可见网格中的总项数（组头 + 展开的单张照片项数，issue #365 / #366）。
     */
    public static int gridTotalItems(List<Group> groups, Set<String> collapsedKeys) {
        if (groups == null || groups.isEmpty()) return 0;
        int total = 0;
        for (Group g : groups) {
            total++;
            if (collapsedKeys == null || !collapsedKeys.contains(g.key)) {
                total += g.photos.size();
            }
        }
        return total;
    }

    /**
     * 根据网格项绝对索引查找所属的 Group（issue #365 / #366）。
     */
    public static Group groupAtItemIndex(List<Group> groups, Set<String> collapsedKeys, int itemIndex) {
        if (groups == null || groups.isEmpty()) return null;
        if (itemIndex <= 0) return groups.get(0);
        int current = 0;
        for (Group g : groups) {
            int groupSize = 1 + ((collapsedKeys == null || !collapsedKeys.contains(g.key)) ? g.photos.size() : 0);
            if (itemIndex >= current && itemIndex < current + groupSize) {
                return g;
            }
            current += groupSize;
        }
        return groups.get(groups.size() - 1);
    }

    /**
     * 查找指定 Group 在网格中的起始项索引（issue #365 / #366）。
     */
    public static int groupStartIndex(List<Group> groups, Set<String> collapsedKeys, String groupKey) {
        if (groups == null || groups.isEmpty() || groupKey == null) return 0;
        int current = 0;
        for (Group g : groups) {
            if (groupKey.equals(g.key)) return current;
            int groupSize = 1 + ((collapsedKeys == null || !collapsedKeys.contains(g.key)) ? g.photos.size() : 0);
            current += groupSize;
        }
        return 0;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "PhotoWall(%d groups, %d units)", groups.size(), photoCount);
    }
}
