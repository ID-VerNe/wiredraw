package com.rea.mtpimport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 照片墙：把 {@link Catalog} 里的 ARW 按「日期组」聚合。
 *
 * UI-SPEC §0.6 的分组依据优先 EXIF / 文件修改时间，两者都未验证；两者都取不到时
 * 按 MTP 目录分组（目录名即日期）。{@link Catalog.Entry} 只有 handle/parent/format/size/name，
 * 没有日期字段，所以这里**只实现目录名这一条**，并在组头上按目录名给标签。
 *
 * 组内数量与容量是**组内聚合**（C-05：GB 2 位、MB ≤1 位）。
 * 本类不 import android.*，可在 JVM 上直接测。
 */
public final class PhotoWall {

    /** 一个日期组；photos 只含 ARW，按 relPath 升序。 */
    public static final class Group {
        public final String key;
        public final String label;
        public final List<Catalog.Entry> photos;
        public final long bytes;

        Group(String key, String label, List<Catalog.Entry> photos, long bytes) {
            this.key = key;
            this.label = label;
            this.photos = Collections.unmodifiableList(photos);
            this.bytes = bytes;
        }

        public int count() {
            return photos.size();
        }

        /** 组头：`Jun 7 · 12 · 284 MB`（U-44 的正则口径）。 */
        public String header() {
            return UiText.groupHeader(label, count(), bytes);
        }
    }

    private final List<Group> groups;
    private final int photoCount;
    private final long photoBytes;

    private PhotoWall(List<Group> groups, int photoCount, long photoBytes) {
        this.groups = Collections.unmodifiableList(groups);
        this.photoCount = photoCount;
        this.photoBytes = photoBytes;
    }

    public List<Group> groups() {
        return groups;
    }

    /** ARW 张数 —— 顶栏计数胶囊与 `Import all N` 的唯一分母（C-08）。 */
    public int photoCount() {
        return photoCount;
    }

    public long photoBytes() {
        return photoBytes;
    }

    public static PhotoWall of(Catalog catalog) {
        Map<String, List<Catalog.Entry>> byFolder = new LinkedHashMap<>();
        for (Catalog.Entry e : catalog.all()) {
            if (e.isFolder() || !e.isRaw()) continue;
            String folder = parentFolderOf(e);
            byFolder.computeIfAbsent(folder, k -> new ArrayList<>()).add(e);
        }
        List<Group> out = new ArrayList<>();
        int count = 0;
        long bytes = 0;
        for (Map.Entry<String, List<Catalog.Entry>> item : byFolder.entrySet()) {
            String folder = item.getKey();
            List<Catalog.Entry> photos = item.getValue();
            photos.sort((a, b) -> a.relPath.compareTo(b.relPath));
            long sum = 0;
            for (Catalog.Entry e : photos) sum += e.size;
            out.add(new Group(folder, labelOf(folder), photos, sum));
            count += photos.size();
            bytes += sum;
        }
        // 新的日期在前；非日期目录名按字典序倒排（同一次扫描内保持稳定）。
        out.sort((a, b) -> b.key.compareTo(a.key));
        return new PhotoWall(out, count, bytes);
    }

    /** relPath 的倒数第二段；没有父目录时返回空串。 */
    static String parentFolderOf(Catalog.Entry e) {
        String path = e.relPath != null ? e.relPath : e.name;
        int slash = path.lastIndexOf('/');
        if (slash < 0) return "";
        String parent = path.substring(0, slash);
        int prev = parent.lastIndexOf('/');
        return prev < 0 ? parent : parent.substring(prev + 1);
    }

    /**
     * 目录名 → 组头标签。`20260607` / `20260607-1` 这类以 8 位日期开头的目录
     * 渲染成 `Jun 7`；其余按目录名原样（§0.6 的「按目录名」降级）。
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

    /** 供测试与详情用：把日期目录名规范成 `yyyy-mm-dd`；解析不了返回空串。 */
    static String isoDateOf(String folder) {
        if (folder == null || folder.length() < 8) return "";
        String head = folder.substring(0, 8);
        for (int i = 0; i < head.length(); i++) {
            if (!Character.isDigit(head.charAt(i))) return "";
        }
        return head.substring(0, 4) + "-" + head.substring(4, 6) + "-" + head.substring(6, 8);
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "PhotoWall(%d groups, %d photos)", groups.size(), photoCount);
    }
}
