package com.rea.mtpimport;

import java.util.ArrayList;
import java.util.List;

import static com.rea.mtpimport.TestSupport.*;
import static com.rea.mtpimport.Fixtures.*;

/** 选择、组选、导入计划与空间校验的断言组（#318 从 SelfTest.java 拆出）。 */
final class SelectionCases {

    // ---------------- Selection ----------------

    static void selectionCases() {
        Catalog c = sample();
        Selection s = new Selection(c);

        s.setChecked(3, true);
        check("勾单个文件只产出它自己",
                s.fileHandles().equals(List.of(3)),
                "实际 " + s.fileHandles());

        s.clear();
        check("clear() 后为空", s.fileHandles().isEmpty(), "实际 " + s.fileHandles());

        s.setChecked(1, true);
        check("勾文件夹展开为全部子孙文件（不含文件夹自身）",
                s.fileHandles().equals(List.of(3, 4, 6)),
                "实际 " + s.fileHandles());

        s.setChecked(1, false);
        check("取消勾选文件夹连带清空子孙",
                s.fileHandles().isEmpty(), "实际 " + s.fileHandles());

        s.setChecked(1, true);
        s.setChecked(2, true);
        check("父与子同时勾不产生重复文件",
                s.fileHandles().equals(List.of(3, 4, 6)),
                "实际 " + s.fileHandles());

        s.selectAll();
        check("selectAll() 覆盖全部 4 个文件",
                s.fileHandles().size() == 4, "实际 " + s.fileHandles());
    }

    // ---------------- 组选（#62） ----------------

    static void groupSelectionCases() {
        Catalog c = groupSample();
        Selection s = new Selection(c);
        List<PhotoWall.Group> groups = PhotoWall.of(c).groups();

        PhotoWall.Group many = null;
        PhotoWall.Group one = null;
        for (PhotoWall.Group g : groups) {
            if (g.count() == 2) many = g;
            else if (g.count() == 1) one = g;
        }
        check("组选：用例造出 2 个单元组与 1 个单元组", many != null && one != null,
                "groups=" + groups.size());

        check("组选：空组不算已全选", !s.allCheckedHandles(List.of()),
                "空组返回 true");

        List<Integer> manyHandles = new ArrayList<>();
        for (PhotoWall.Unit u : many.photos) manyHandles.addAll(u.handles());
        check("组选：2 个单元含 3 个文件句柄（同名 JPG 也参与）", manyHandles.size() == 3,
                "实际 " + manyHandles);

        s.setChecked(manyHandles.get(0), true);
        check("组选：部分选中时 allCheckedHandles 为 false", !s.allCheckedHandles(manyHandles),
                "实际 " + s.fileHandles());

        s.setCheckedAll(manyHandles, true);
        check("组选：补齐后组内全部已勾", s.allCheckedHandles(manyHandles),
                "实际 " + s.fileHandles());
        check("组选：只作用于本组单元，不跨组",
                !one.photos.get(0).isChecked(s), "1 个单元组被顺带勾上");
        check("组选：同名 JPG 与 ARW 一起勾选（#83）", s.isChecked(5),
                "实际 " + s.fileHandles());
        check("组选：重复全选不产生重复文件", s.fileHandles().size() == 3,
                "实际 " + s.fileHandles());

        s.setCheckedAll(manyHandles, false);
        check("组选：已全选后取消清空本组",
                !s.allCheckedHandles(manyHandles) && s.fileHandles().isEmpty(),
                "实际 " + s.fileHandles());
    }

    // ---------------- ImportPlan ----------------

    static void planCases() {
        Catalog c = sample();
        PhotoWall wall = PhotoWall.of(c);

        // 勾 DCIM 整个目录：选中 2 个单元（DSC02183 双格式、DSC02999 仅 ARW），
        // 顶层 C0001.JPG 不在 DCIM 下，不参与。
        Selection s = new Selection(c);
        s.setChecked(1, true);
        ImportPlan p = ImportPlan.of(c, s, wall.units(), PhotoWall.Export.JPG_FIRST);

        check("计划条数 = 可见已选单元数", p.count() == 2, "实际 " + p.count());
        check("All 档载荷 JPG 优先、缺 JPG 回退 ARW",
                p.totalBytes() == 100 + 50, "实际 " + p.totalBytes());
        List<String> paths = new ArrayList<>();
        for (ImportPlan.Item item : p.items()) paths.add(item.relPath);
        check("计划按单元取文件名，DSC02183 取 JPG 而不是 ARW（#83）",
                paths.equals(List.of("DCIM/100MSDCF/DSC02183.JPG", "DCIM/DSC02999.ARW")),
                "实际 " + paths);

        // issue #83：空勾选兜底 = 当前可见集合，不是写死的「全部 ARW」；
        // 旧断言「空勾选只收 ARW」是本次语义变更的墓碑，改回 ARW-only 必须红。
        ImportPlan all = ImportPlan.of(c, new Selection(c), wall.units(), PhotoWall.Export.JPG_FIRST);
        check("空勾选 = 当前可见集合（All 档 3 个单元，#83）",
                all.count() == 3 && all.totalBytes() == 100 + 50 + 60,
                "count=" + all.count() + " total=" + all.totalBytes());
        List<String> allPaths = new ArrayList<>();
        for (ImportPlan.Item item : all.items()) allPaths.add(item.relPath);
        check("All 档空勾选三张全收、同名 JPG 优先（#83）",
                allPaths.equals(List.of("C0001.JPG",
                        "DCIM/100MSDCF/DSC02183.JPG", "DCIM/DSC02999.ARW")),
                "实际 " + allPaths);

        // RAW 档：可见集合 = 有 ARW 的单元，载荷只取 ARW。
        List<PhotoWall.Unit> rawUnits = new ArrayList<>();
        for (PhotoWall.Unit u : wall.units()) if (u.hasArw()) rawUnits.add(u);
        ImportPlan raw = ImportPlan.of(c, new Selection(c), rawUnits, PhotoWall.Export.RAW);
        check("RAW 档只导 ARW（#83）",
                raw.count() == 2 && raw.totalBytes() == 300 + 50,
                "count=" + raw.count() + " total=" + raw.totalBytes());

        // JPG 档：可见集合 = 有 JPG 的单元（样本 3 个单元里 2 个有 JPG）。
        List<PhotoWall.Unit> jpgUnits = new ArrayList<>();
        for (PhotoWall.Unit u : wall.units()) if (u.hasJpg()) jpgUnits.add(u);
        ImportPlan jpg = ImportPlan.of(c, new Selection(c), jpgUnits, PhotoWall.Export.JPG);
        check("JPG 档只导 JPG（样本 2 个有 JPG 的单元，#83）",
                jpg.count() == 2 && jpg.totalBytes() == 100 + 60,
                "count=" + jpg.count() + " total=" + jpg.totalBytes());

        // 可见已选 0、但总已选 ≥1（勾的是被 filter 隐藏的单元）：CTA 语义回到「导入全部可见」。
        Selection hidden = new Selection(c);
        hidden.setChecked(5, true);   // 顶层 C0001.JPG，不在 RAW 档可见集合里
        ImportPlan hiddenPlan = ImportPlan.of(c, hidden, rawUnits, PhotoWall.Export.RAW);
        check("总已选 ≥1 但全被 filter 隐藏时，计划 = 全部可见单元（#83）",
                hiddenPlan.count() == 2 && hiddenPlan.totalBytes() == 300 + 50,
                "count=" + hiddenPlan.count() + " total=" + hiddenPlan.totalBytes());

        ImportPlan none = ImportPlan.of(c, s, List.of(), PhotoWall.Export.RAW);
        check("可见集合为空时计划为空（不产生 Import 0）", none.isEmpty(), "实际 " + none.count());

        // issue #57：恢复暂停轮次前必须判断两份计划是不是同一批文件。
        ImportPlan p2 = ImportPlan.of(c, s, wall.units(), PhotoWall.Export.JPG_FIRST);
        check("同一清单按值比较为真（不靠对象身份，#57）",
                ImportPlan.sameFileSet(p, p), "实际 false");
        check("同勾选重算出的清单仍视为同一批（#57）",
                ImportPlan.sameFileSet(p, p2), "实际 false");
        check("条数不同不是同一批（#57）",
                !ImportPlan.sameFileSet(p, all), "实际 true");
        Selection other = new Selection(c);
        other.setChecked(5, true);   // C0001.JPG（60）
        other.setChecked(6, true);   // DSC02999.ARW（50）：条数与 p 相同，但 C0001 不在 p 里
        ImportPlan otherPlan = ImportPlan.of(c, other, wall.units(), PhotoWall.Export.JPG_FIRST);
        check("条数相同但文件不同也不是同一批（#57）",
                p.count() == otherPlan.count() && !ImportPlan.sameFileSet(p, otherPlan),
                "p=" + p.count() + " other=" + otherPlan.count());
        check("档位变化导致载荷文件不同，也不是同一批（#57）",
                !ImportPlan.sameFileSet(p, ImportPlan.of(c, s, wall.units(), PhotoWall.Export.RAW)),
                "实际 true");
        // 主键是 relPath + size：同名同路径但字节数变了（换卡 / 重新编码）也不能算同一批。
        Catalog variant = new Catalog();
        variant.add(new Catalog.Entry(1, 0, Catalog.FMT_FOLDER, 0, "DCIM"));
        variant.add(new Catalog.Entry(2, 1, Catalog.FMT_FOLDER, 0, "100MSDCF"));
        variant.add(new Catalog.Entry(3, 2, Catalog.FMT_ARW, 300, "DSC02183.ARW"));
        variant.add(new Catalog.Entry(4, 2, Catalog.FMT_JPEG, 101, "DSC02183.JPG"));
        variant.add(new Catalog.Entry(6, 1, Catalog.FMT_ARW, 50, "DSC02999.ARW"));
        variant.add(new Catalog.Entry(5, 0, Catalog.FMT_JPEG, 60, "C0001.JPG"));
        Catalog cv = variant.build();
        ImportPlan otherSize = ImportPlan.of(cv, s, PhotoWall.of(cv).units(),
                PhotoWall.Export.JPG_FIRST);
        check("relPath 相同但字节数变了也不是同一批（#57）",
                !ImportPlan.sameFileSet(p, otherSize), "实际 true");
        check("任一侧为 null 时不是同一批（#57）",
                !ImportPlan.sameFileSet(p, null) && !ImportPlan.sameFileSet(null, p),
                "实际 true");
    }

    // ---------------- SpaceCheck（PD / P-09） ----------------

    static void spaceCases() {
        check("SpaceCheck：正好等于计划 + 5% 缓冲时放行",
                SpaceCheck.enough(1050, 1000), "free=1050 planned=1000");
        check("SpaceCheck：少 1 字节即判不足",
                !SpaceCheck.enough(1049, 1000), "free=1049 planned=1000");
        check("SpaceCheck：5% 向上取整（1 字节计划也留 1 字节缓冲）",
                SpaceCheck.requiredBytes(1) == 2, "实际 " + SpaceCheck.requiredBytes(1));
        check("SpaceCheck：0 字节计划不拦",
                SpaceCheck.requiredBytes(0) == 0 && SpaceCheck.enough(0, 0),
                "required=" + SpaceCheck.requiredBytes(0));
        check("SpaceCheck：负数计划按 0 处理",
                SpaceCheck.requiredBytes(-5) == 0, "实际 " + SpaceCheck.requiredBytes(-5));
        check("SpaceCheck：负数可用空间不放行",
                !SpaceCheck.enough(-1, 10), "free=-1 planned=10");

        // describe 的 Need 是**含 5% 缓冲后的需求**（5 GB → 5.3 GB），不是计划原值。
        String detail = SpaceCheck.describe(3L * 1073741824L, 5L * 1073741824L);
        check("SpaceCheck.describe 同时给出含缓冲的需求与剩余（GB，1 位小数）",
                detail.contains("5.3 GB") && detail.contains("3.0 GB"), "实际 " + detail);
    }
}
