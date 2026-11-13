package com.rea.mtpimport;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static com.rea.mtpimport.TestSupport.*;
import static com.rea.mtpimport.Fixtures.*;
import static com.rea.mtpimport.SourceGuard.*;

/** 照片墙分组、派生量与设计基线的断言组（#318 从 SelfTest.java 拆出）。 */
final class WallCases {

    // ---------------- 照片墙分组（PhotoWall） ----------------

    /**
     * issue #365 / #366：网格项绝对索引与日期组映射算法。
     */
    static void photoWallGridIndexCases() {
        PhotoWall wall = PhotoWall.of(sample());
        List<PhotoWall.Group> groups = wall.groups();
        check("网格总项数：未折叠时 3 个组头 + 3 张照片 = 6 项（#365）",
                PhotoWall.gridTotalItems(groups, Collections.emptySet()) == 6,
                "实际 " + PhotoWall.gridTotalItems(groups, Collections.emptySet()));

        String secondGroupKey = groups.get(1).key;
        Set<String> collapsedSecond = Set.of(secondGroupKey);
        check("网格总项数：折叠第 2 组后项数减为 5 项（#365）",
                PhotoWall.gridTotalItems(groups, collapsedSecond) == 5,
                "实际 " + PhotoWall.gridTotalItems(groups, collapsedSecond));

        check("网格项索引映射：index 0 归属组 0 组头（#365）",
                PhotoWall.groupAtItemIndex(groups, Collections.emptySet(), 0) == groups.get(0),
                "实际 " + PhotoWall.groupAtItemIndex(groups, Collections.emptySet(), 0));

        check("网格项索引映射：index 1 归属组 0 照片（#365）",
                PhotoWall.groupAtItemIndex(groups, Collections.emptySet(), 1) == groups.get(0),
                "实际 " + PhotoWall.groupAtItemIndex(groups, Collections.emptySet(), 1));

        check("网格项索引映射：index 2 归属组 1（#365）",
                PhotoWall.groupAtItemIndex(groups, Collections.emptySet(), 2) == groups.get(1),
                "实际 " + PhotoWall.groupAtItemIndex(groups, Collections.emptySet(), 2));

        check("网格项索引映射：折叠组 1 后 index 3 归属组 2（#365）",
                PhotoWall.groupAtItemIndex(groups, collapsedSecond, 3) == groups.get(2),
                "实际 " + PhotoWall.groupAtItemIndex(groups, collapsedSecond, 3));

        check("网格项索引映射：负索引安全回退第一组（#365）",
                PhotoWall.groupAtItemIndex(groups, Collections.emptySet(), -5) == groups.get(0),
                "实际 " + PhotoWall.groupAtItemIndex(groups, Collections.emptySet(), -5));

        check("网格项索引映射：越界大索引安全回退最后一组（#365）",
                PhotoWall.groupAtItemIndex(groups, Collections.emptySet(), 999) == groups.get(2),
                "实际 " + PhotoWall.groupAtItemIndex(groups, Collections.emptySet(), 999));

        check("组起始索引：组 0 起始于 0（#365）",
                PhotoWall.groupStartIndex(groups, Collections.emptySet(), groups.get(0).key) == 0,
                "实际 " + PhotoWall.groupStartIndex(groups, Collections.emptySet(), groups.get(0).key));

        check("组起始索引：未折叠时组 1 起始于 2（#365）",
                PhotoWall.groupStartIndex(groups, Collections.emptySet(), groups.get(1).key) == 2,
                "实际 " + PhotoWall.groupStartIndex(groups, Collections.emptySet(), groups.get(1).key));

        check("组起始索引：未折叠时组 2 起始于 4（#365）",
                PhotoWall.groupStartIndex(groups, Collections.emptySet(), groups.get(2).key) == 4,
                "实际 " + PhotoWall.groupStartIndex(groups, Collections.emptySet(), groups.get(2).key));

        check("组起始索引：折叠组 1 后组 2 起始于 3（#365）",
                PhotoWall.groupStartIndex(groups, collapsedSecond, groups.get(2).key) == 3,
                "实际 " + PhotoWall.groupStartIndex(groups, collapsedSecond, groups.get(2).key));
    }

    static void photoWallCases() {
        PhotoWall wall = PhotoWall.of(sample());
        // 样本 = 3 个照片单元：DSC02183（ARW 300 + JPG 100）、DSC02999（ARW 50）、C0001（JPG 60）。
        check("照片墙按照片单元计：3 格", wall.photoCount() == 3, "实际 " + wall.photoCount());
        check("照片墙容量按 All 档 JPG 优先：100+50+60",
                wall.photoBytes() == 210, "实际 " + wall.photoBytes());
        check("按目录名分出 3 组（100MSDCF / DCIM / 顶层 Folder）",
                wall.groups().size() == 3, "实际 " + wall.groups().size());
        boolean onlyPhotos = true;
        for (PhotoWall.Group g : wall.groups()) {
            for (PhotoWall.Unit u : g.photos) {
                if (!u.hasArw() && !u.hasJpg()) onlyPhotos = false;
            }
        }
        check("组内只含照片单元（MP4 等不进照片墙）", onlyPhotos, "组内混入了非照片对象");

        PhotoWall.Unit both = null;
        for (PhotoWall.Unit u : wall.units()) {
            if (u.hasArw() && u.hasJpg() && u.arw.handle == 3) both = u;
        }
        check("同名 ARW+JPG 合成一个单元（#83）", both != null
                        && both.jpg != null && both.jpg.handle == 4,
                "实际 " + both);
        check("单元载荷随档位切换：All 取 JPG、RAW 取 ARW（#83）",
                both.export(PhotoWall.Export.JPG_FIRST).handle == 4
                        && both.export(PhotoWall.Export.RAW).handle == 3
                        && both.export(PhotoWall.Export.JPG).handle == 4,
                "JPG_FIRST=" + both.export(PhotoWall.Export.JPG_FIRST)
                        + " RAW=" + both.export(PhotoWall.Export.RAW));

        long groupBytes = 0;
        int groupCount = 0;
        for (PhotoWall.Group g : wall.groups()) {
            groupBytes += g.bytes(PhotoWall.Export.JPG_FIRST);
            groupCount += g.count();
        }
        check("组内聚合之和等于总量", groupBytes == wall.photoBytes() && groupCount == wall.photoCount(),
                "组和 " + groupBytes + "/" + groupCount);
        check("组头是组内聚合格式",
                wall.groups().get(0).header(PhotoWall.Export.JPG_FIRST)
                        .matches(".+ \u00B7 \\d+ \u00B7 \\d+(.\\d)? MB"),
                "实际 " + wall.groups().get(0).header(PhotoWall.Export.JPG_FIRST));
        check("目录名日期渲染成 Jun 7", "Jun 7".equals(PhotoWall.labelOf("20260607")),
                "实际 " + PhotoWall.labelOf("20260607"));
        check("非日期目录名原样保留", "100MSDCF".equals(PhotoWall.labelOf("100MSDCF")),
                "实际 " + PhotoWall.labelOf("100MSDCF"));
    }

    // ---------------- 派生量与聚合口径（G6 / issue #214） ----------------

    /**
     * F-07：进度分数只有一个定义——分母 ≤0 记 0，越界夹紧。
     * F-09：组头视觉/读屏两种呈现共用一次聚合。
     * F-02：可见组由可见单元的 key 集合从全部组重建（组头计数恒等于条目数）。
     */
    static void derivedValueCases() {
        check("进度分数：分母为 0 记 0", UiText.progressFraction(3, 0) == 0f,
                "实际 " + UiText.progressFraction(3, 0));
        check("进度分数：分母为负记 0", UiText.progressFraction(3, -1) == 0f,
                "实际 " + UiText.progressFraction(3, -1));
        check("进度分数：完成数超过总数夹到 1", UiText.progressFraction(40, 36) == 1f,
                "实际 " + UiText.progressFraction(40, 36));
        check("进度分数：负数进度夹到 0", UiText.progressFraction(-2, 36) == 0f,
                "实际 " + UiText.progressFraction(-2, 36));
        check("进度分数：12/36 = 1/3", Math.abs(UiText.progressFraction(12, 36) - 1f / 3f) < 1e-6f,
                "实际 " + UiText.progressFraction(12, 36));
        // #282（产品裁决「钳」）：环弧与 percentText 同口径，未传完不得画满。
        check("进度分数：511/512 钳到 0.99（不是 0.998）", UiText.progressFraction(511, 512) == 0.99f,
                "实际 " + UiText.progressFraction(511, 512));
        check("进度分数：199/200 也钳到 0.99", UiText.progressFraction(199, 200) == 0.99f,
                "实际 " + UiText.progressFraction(199, 200));
        check("进度分数：512/512 才给满环", UiText.progressFraction(512, 512) == 1f,
                "实际 " + UiText.progressFraction(512, 512));
        check("环弧：未完成时不超过 99% 对应角度",
                360f * UiText.progressFraction(511, 512) <= 360f * 0.99f,
                "实际 " + (360f * UiText.progressFraction(511, 512)));

        PhotoWall wall = PhotoWall.of(sample());
        PhotoWall.Group jun = null;
        for (PhotoWall.Group g : wall.groups()) {
            if ("100MSDCF".equals(g.label)) jun = g;
        }
        check("组头摘要的计数等于组内条目数",
                jun != null && jun.summary(PhotoWall.Export.JPG_FIRST).count == jun.photos.size(),
                "实际 " + (jun == null ? "null" : jun.summary(PhotoWall.Export.JPG_FIRST).count));
        check("视觉组头与读屏组头同源（视觉）",
                jun != null && jun.summary(PhotoWall.Export.JPG_FIRST).header()
                        .equals(UiText.groupHeader("100MSDCF", jun.photos.size(),
                                jun.bytes(PhotoWall.Export.JPG_FIRST))),
                "实际 " + (jun == null ? "null" : jun.summary(PhotoWall.Export.JPG_FIRST).header()));
        check("视觉组头与读屏组头同源（读屏）",
                jun != null && jun.summary(PhotoWall.Export.JPG_FIRST).spoken()
                        .equals("Date group 100MSDCF, " + jun.photos.size() + " photos, "
                                + UiText.capacity(jun.bytes(PhotoWall.Export.JPG_FIRST))),
                "实际 " + (jun == null ? "null" : jun.summary(PhotoWall.Export.JPG_FIRST).spoken()));

        // F-02：restrict 用可见单元 key 剪枝；组头计数 == 条目数，且不漏不重。
        List<String> rawKeys = new ArrayList<>();
        for (PhotoWall.Unit u : wall.units()) {
            if (u.hasArw()) rawKeys.add(u.key);
        }
        List<PhotoWall.Group> restricted = PhotoWall.restrict(wall.groups(), rawKeys);
        int unitTotal = 0;
        boolean allKeysVisible = true;
        for (PhotoWall.Group g : restricted) {
            unitTotal += g.count();
            for (PhotoWall.Unit u : g.photos) {
                if (!rawKeys.contains(u.key)) allKeysVisible = false;
            }
        }
        check("restrict：RAW key 剪枝后组内单元总数一致", unitTotal == rawKeys.size(),
                "实际 " + unitTotal + " / " + rawKeys.size());
        check("restrict：不可见组不出现（纯 JPG 组被剪掉）", restricted.size() == 2,
                "实际 " + restricted.size());
        check("restrict：只含可见单元", allKeysVisible, "混入了不可见单元");
        check("restrict：空 key 返回空",
                PhotoWall.restrict(wall.groups(), new ArrayList<String>()).isEmpty(),
                "实际 " + PhotoWall.restrict(wall.groups(), new ArrayList<String>()).size());
    }

    /** F-09/F-20 的结构守卫：聚合与勾选订阅不得在页面里各写一份。 */
    static void derivedSourceCases() {
        File srcRoot = SRC_ROOT;
        List<String> hits = new ArrayList<>();
        int scanned = scanSourceText(srcRoot, "group.count()", null, hits);
        check("组头聚合只在 PhotoWall：src 里 group.count() 0 命中", scanned > 0 && hits.isEmpty(),
                "扫描 " + scanned + " 个文件，命中：\n" + String.join("\n", hits));
        hits.clear();
        scanned = scanSourceText(srcRoot, "selectionVersion", "src/com/rea/mtpimport/ui/AppModel.kt", hits);
        check("勾选订阅只在 AppModel：页面不得裸读 selectionVersion", scanned > 0 && hits.isEmpty(),
                "扫描 " + scanned + " 个文件，命中：\n" + String.join("\n", hits));
    }

    // ---------------- 设计基线常量（G7a / issue #215） ----------------

    /**
     * F-11：五档排版的字号 / 行高 / 字重 / 字距必须与 UI-SPEC §3.1 表逐项一致
     * （`UI-SPEC.md` 的 §3.1）；V-01 给字号下界。
     */
    static void typeTokenCases() {
        check("body-md = 14/20/400/-0.005em",
                TypeTokens.BODY_MD_SIZE_SP == 14f
                        && TypeTokens.BODY_MD_LINE_HEIGHT_SP == 20f
                        && TypeTokens.BODY_MD_WEIGHT == 400
                        && Math.abs(TypeTokens.BODY_MD_TRACKING_EM + 0.005f) < 1e-6f,
                "实际 " + TypeTokens.BODY_MD_SIZE_SP + "/" + TypeTokens.BODY_MD_LINE_HEIGHT_SP
                        + "/" + TypeTokens.BODY_MD_WEIGHT + "/" + TypeTokens.BODY_MD_TRACKING_EM);
        check("body-sm = 12/16/400/0",
                TypeTokens.BODY_SM_SIZE_SP == 12f
                        && TypeTokens.BODY_SM_LINE_HEIGHT_SP == 16f
                        && TypeTokens.BODY_SM_WEIGHT == 400
                        && TypeTokens.BODY_SM_TRACKING_EM == 0f,
                "实际 " + TypeTokens.BODY_SM_SIZE_SP + "/" + TypeTokens.BODY_SM_LINE_HEIGHT_SP
                        + "/" + TypeTokens.BODY_SM_WEIGHT + "/" + TypeTokens.BODY_SM_TRACKING_EM);
        check("label-md = 13/16/500/0.01em",
                TypeTokens.LABEL_MD_SIZE_SP == 13f
                        && TypeTokens.LABEL_MD_LINE_HEIGHT_SP == 16f
                        && TypeTokens.LABEL_MD_WEIGHT == 500
                        && Math.abs(TypeTokens.LABEL_MD_TRACKING_EM - 0.01f) < 1e-6f,
                "实际 " + TypeTokens.LABEL_MD_SIZE_SP + "/" + TypeTokens.LABEL_MD_LINE_HEIGHT_SP
                        + "/" + TypeTokens.LABEL_MD_WEIGHT + "/" + TypeTokens.LABEL_MD_TRACKING_EM);
        check("display-sm = 24/28/600/-0.02em",
                TypeTokens.DISPLAY_SM_SIZE_SP == 24f
                        && TypeTokens.DISPLAY_SM_LINE_HEIGHT_SP == 28f
                        && TypeTokens.DISPLAY_SM_WEIGHT == 600
                        && Math.abs(TypeTokens.DISPLAY_SM_TRACKING_EM + 0.02f) < 1e-6f,
                "实际 " + TypeTokens.DISPLAY_SM_SIZE_SP + "/" + TypeTokens.DISPLAY_SM_LINE_HEIGHT_SP
                        + "/" + TypeTokens.DISPLAY_SM_WEIGHT + "/" + TypeTokens.DISPLAY_SM_TRACKING_EM);
        check("mono-data = 12/16/400/0",
                TypeTokens.MONO_DATA_SIZE_SP == 12f
                        && TypeTokens.MONO_DATA_LINE_HEIGHT_SP == 16f
                        && TypeTokens.MONO_DATA_WEIGHT == 400
                        && TypeTokens.MONO_DATA_TRACKING_EM == 0f,
                "实际 " + TypeTokens.MONO_DATA_SIZE_SP + "/" + TypeTokens.MONO_DATA_LINE_HEIGHT_SP
                        + "/" + TypeTokens.MONO_DATA_WEIGHT + "/" + TypeTokens.MONO_DATA_TRACKING_EM);

        // V-01：正文 ≥14 sp、辅助/数字 ≥12 sp；V-02 口径下一档行高不得小于字号。
        float[] sizes = {
            TypeTokens.BODY_MD_SIZE_SP, TypeTokens.BODY_SM_SIZE_SP, TypeTokens.LABEL_MD_SIZE_SP,
            TypeTokens.DISPLAY_SM_SIZE_SP, TypeTokens.MONO_DATA_SIZE_SP
        };
        float[] lineHeights = {
            TypeTokens.BODY_MD_LINE_HEIGHT_SP, TypeTokens.BODY_SM_LINE_HEIGHT_SP,
            TypeTokens.LABEL_MD_LINE_HEIGHT_SP, TypeTokens.DISPLAY_SM_LINE_HEIGHT_SP,
            TypeTokens.MONO_DATA_LINE_HEIGHT_SP
        };
        boolean floorOk = sizes[0] >= 14f;
        boolean linesOk = true;
        for (int i = 0; i < sizes.length; i++) {
            if (sizes[i] < 12f) floorOk = false;
            if (lineHeights[i] < sizes[i]) linesOk = false;
        }
        check("V-01：正文 ≥14 sp、其余 ≥12 sp", floorOk, "body-md=" + sizes[0]);
        check("行高不小于字号（五档）", linesOk, "存在行高 < 字号");
    }

    /** F-12 / F-21：环与抽屉尺寸的唯一定义（V-22 有据；扫描环与 480 dp 为现状口径）。 */
    static void dimsCases() {
        check("V-22：传输环外径 180 dp", Dims.TRANSFER_RING_DIAMETER_DP == 180f,
                "实际 " + Dims.TRANSFER_RING_DIAMETER_DP);
        check("V-22：传输环描边 3.5 dp", Dims.TRANSFER_RING_STROKE_DP == 3.5f,
                "实际 " + Dims.TRANSFER_RING_STROKE_DP);
        check("扫描环沿用 180 / 2 dp", Dims.SCAN_RING_DIAMETER_DP == 180f && Dims.SCAN_RING_STROKE_DP == 2f,
                "实际 " + Dims.SCAN_RING_DIAMETER_DP + " / " + Dims.SCAN_RING_STROKE_DP);
        check("两个环同径（180）", Dims.SCAN_RING_DIAMETER_DP == Dims.TRANSFER_RING_DIAMETER_DP,
                "扫描 " + Dims.SCAN_RING_DIAMETER_DP + " vs 传输 " + Dims.TRANSFER_RING_DIAMETER_DP);
        check("抽屉最大高度 480 dp", Dims.DRAWER_MAX_HEIGHT_DP == 480f,
                "实际 " + Dims.DRAWER_MAX_HEIGHT_DP);
    }

    /** F-11 / F-16 / F-21 的结构守卫：排版与尺寸不得在页面里各写一份。 */
    static void typeSourceCases() {
        File srcRoot = SRC_ROOT;
        List<String> hits = new ArrayList<>();
        int scanned = scanSourceText(srcRoot, "480.dp", null, hits);
        check("抽屉高度只在 Dims：src 里 480.dp 0 命中", scanned > 0 && hits.isEmpty(),
                "扫描 " + scanned + " 个文件，命中：\n" + String.join("\n", hits));
        hits.clear();
        scanned = scanSourceText(srcRoot, "letterSpacing", "src/com/rea/mtpimport/ui/Theme.kt", hits);
        check("字距只在 Theme.kt 落一次", scanned > 0 && hits.isEmpty(),
                "扫描 " + scanned + " 个文件，命中：\n" + String.join("\n", hits));
        hits.clear();
        scanned = scanSourceText(srcRoot, "onDark", null, hits);
        check("Pill 参数统一叫 onCanvas：src 里 onDark 0 命中", scanned > 0 && hits.isEmpty(),
                "扫描 " + scanned + " 个文件，命中：\n" + String.join("\n", hits));
    }

    // ---------------- 布局形状的唯一实现（G8 / issue #217） ----------------

    /**
     * F-04/F-08/F-15/F-17/F-18 的结构守卫：抽屉骨架、次按钮、图标画布、动作行、筛选选项
     * 各只允许有一份实现。页面里再抄一遍即红。
     */
    static void layoutShapeCases() {
        File srcRoot = SRC_ROOT;
        List<String> hits = new ArrayList<>();
        int scanned = scanSourceText(srcRoot, "BottomCenter", "src/com/rea/mtpimport/ui/Widgets.kt", hits);
        check("抽屉骨架只在 Widgets：页面不得各写一份 BottomCenter", scanned > 0 && hits.isEmpty(),
                "扫描 " + scanned + " 个文件，命中：\n" + String.join("\n", hits));
        hits.clear();
        scanned = scanSourceText(srcRoot, "DrawerSkeleton(title = ", null, hits);
        check("两个抽屉都走 DrawerSkeleton：调用点正好 2 处", hits.size() == 2,
                "实际 " + hits.size() + " 处：\n" + String.join("\n", hits));
        hits.clear();
        scanned = scanSourceText(srcRoot, "Canvas(modifier.size(size))", null, hits);
        check("图标画布只在 Icons 的 Glyph 工厂：全 src/ 正好 1 处", scanned > 0 && hits.size() == 1,
                "实际 " + hits.size() + " 处：\n" + String.join("\n", hits));
        hits.clear();
        scanned = scanSourceText(srcRoot, "Glyph(size, modifier)", null, hits);
        check("13 个 glyph 都走 Glyph 工厂", hits.size() == 13, "实际 " + hits.size() + " 处");
        hits.clear();
        scanned = scanSourceText(srcRoot, "RoundedCornerShape(24.dp)", "src/com/rea/mtpimport/ui/Widgets.kt", hits);
        check("24 dp 圆角按钮只在 Widgets：TransferScreen 不得手写", scanned > 0 && hits.isEmpty(),
                "扫描 " + scanned + " 个文件，命中：\n" + String.join("\n", hits));
        hits.clear();
        scanned = scanSourceText(srcRoot, "Modifier.fillMaxWidth().padding(8.dp)", null, hits);
        check("状态动作行只在 StatusScreen：页面不得手写 Row 内边距", scanned > 0 && hits.isEmpty(),
                "扫描 " + scanned + " 个文件，命中：\n" + String.join("\n", hits));
        hits.clear();
        scanned = scanSourceText(srcRoot, "setFilter(FilterKind.", null, hits);
        check("筛选选项数据驱动：ALL/RAW/JPG 不得各写一条", scanned > 0 && hits.size() == 1,
                "实际 " + hits.size() + " 处：\n" + String.join("\n", hits));
    }
}
