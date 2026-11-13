package com.rea.mtpimport;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static com.rea.mtpimport.TestSupport.*;
import static com.rea.mtpimport.Fixtures.*;
import static com.rea.mtpimport.SourceGuard.*;

/** 照片墙分组、派生量与设计基线的断言组（#318 从 SelfTest.java 拆出）。 */
final class WallCases {

    // ---------------- 照片墙分组（PhotoWall） ----------------

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
        // #374 / IA-6：失效源从全局版本号换成按 key 的镜像表，守卫的口径随之改成「页面不得自己碰镜像」——
        // 页面只能走 `isUnitChecked` / `isGroupChecked`，否则等于绕过唯一读取入口、把细粒度又摊平。
        scanned = scanSourceText(srcRoot, "checkedState", "src/com/rea/mtpimport/ui/AppModel.kt", hits);
        check("勾选失效源只在 AppModel：页面不得裸读 checkedState", scanned > 0 && hits.isEmpty(),
                "扫描 " + scanned + " 个文件，命中：\n" + String.join("\n", hits));
    }

    /**
     * #374 / IA-6：组头聚合从「每次遍历求和」改成「建组时按三档预聚合」。
     *
     * 预聚合最容易犯的错是三个累加器写串（比如 `bytesRaw` 累到 `bytesJpg` 上），而组头文案
     * 只会在真机上「数字不对」，单测不盯就没人发现。这里对每档逐项对账，并确认 `select()` 出来的
     * 子组同样按新的单元集合重算（不是沿用父组的数）。
     */
    static void groupBytesCases() {
        PhotoWall wall = PhotoWall.of(sample());
        boolean allMatch = true;
        boolean summaryMatch = true;
        String detail = "";
        for (PhotoWall.Group g : wall.groups()) {
            for (PhotoWall.Export mode : PhotoWall.Export.values()) {
                long manual = 0;
                for (PhotoWall.Unit u : g.photos) manual += u.exportBytes(mode);
                if (g.bytes(mode) != manual) {
                    allMatch = false;
                    detail += g.key + "/" + mode + " 预聚合 " + g.bytes(mode) + " ≠ 逐项 " + manual + "\n";
                }
                if (g.summary(mode).bytes != manual) summaryMatch = false;
            }
        }
        check("组内字节按三档预聚合 = 逐项累加（#374 IA-6）", allMatch, detail);
        check("组头聚合快照与预聚合同源", summaryMatch, "summary.bytes 与 bytes(mode) 不一致");

        // select() 出来的子组必须按**新集合**重算：`groupSample()` 的 20260607 组有 2 个单元
        // （ARW+JPG 一个、纯 ARW 一个），只留 JPG 后聚合必然小于父组。
        PhotoWall multi = PhotoWall.of(groupSample());
        PhotoWall.Group big = null;
        for (PhotoWall.Group g : multi.groups()) {
            if (g.count() >= 2) big = g;
        }
        check("组选样本里有 ≥2 个单元的组", big != null, "groupSample() 分组异常");
        if (big != null) {
            PhotoWall.Group subset = big.select(PhotoWall.Unit::hasJpg);
            long manual = 0;
            if (subset != null) {
                for (PhotoWall.Unit u : subset.photos) manual += u.exportBytes(PhotoWall.Export.JPG_FIRST);
            }
            check("select() 出的子组按新集合重算，不沿用父组聚合（#374 IA-6）",
                    subset != null && subset.count() < big.count()
                            && subset.bytes(PhotoWall.Export.JPG_FIRST) == manual
                            && manual != big.bytes(PhotoWall.Export.JPG_FIRST),
                    "子组 " + (subset == null ? "null" : subset.bytes(PhotoWall.Export.JPG_FIRST))
                            + " / 逐项 " + manual + " / 父组 " + big.bytes(PhotoWall.Export.JPG_FIRST));
        }
    }

    /**
     * #374 / IA-6：勾选状态的失效源改成「每单元一个 `MutableState`」的镜像。
     *
     * 这是**写坏就静默**的一类改动：漏同步不会崩，只会「点得动、计数不动」或「换 filter 后
     * 勾选态回退」。`AppModel` 依赖 Compose 运行时，JVM 用例起不来，只能把结构钉在源码上。
     *
     * **为什么不是 `contains`**（评审第 1 轮拦下）：子串匹配挡不住「行还在、语义没了」——
     * `if (false) checkedState(unit.key).value = on` 照样命中，`syncCheckedFromSelection` 顶部加一句
     * 早返回也照样命中。这里改成**整行 + 缩进**匹配：行内容必须与期望逐字相等，缩进必须正好是
     * 函数体内那一级（死代码包裹会多缩进 4 格），回读函数另外禁止出现 `return`（挡早返回）。
     */
    static void checkedMirrorCases() {
        File appModel = source("com/rea/mtpimport/ui/AppModel.kt");
        String src = readSourceOrNull(appModel);
        if (src == null) {
            check("勾选镜像：能读到 AppModel.kt 源码（#374 IA-6）", false, "读不到 " + appModel);
            return;
        }

        boolean field = hasStatement(src, 4,
                "private val checkedStates = HashMap<String, MutableState<Boolean>>()")
                && hasStatement(src, 4,
                "private fun checkedState(key: String): MutableState<Boolean> =")
                && hasStatement(src, 8, "checkedStates.getOrPut(key) { mutableStateOf(false) }");
        boolean readsCell = hasStatement(src, 4,
                "fun isUnitChecked(unit: PhotoWall.Unit): Boolean = checkedState(unit.key).value");
        boolean readsGroup = hasStatement(src, 12, "if (!checkedState(u.key).value) return false");
        check("勾选读取只走镜像表：单格读本格 key、组头逐单元短路（#374 IA-6）",
                field && readsCell && readsGroup,
                "字段 " + field + " / 单格 " + readsCell + " / 组头 " + readsGroup);

        boolean writesUnit = hasStatement(src, 8, "checkedState(unit.key).value = on");
        boolean writesGroup = hasStatement(src, 8,
                "for (u in group.photos) checkedState(u.key).value = select");
        check("两条写路径同处同步镜像：单格写 key、组选整组写（#374 IA-6）",
                writesUnit && writesGroup,
                "单格 " + writesUnit + " / 组选 " + writesGroup);

        // 真相源一侧也必须钉住（评审第 2 轮拦下）：只钉镜像会漏掉反方向的洞 ——
        // 把 `sel.setChecked(...)` 删掉（只写镜像、不写 `Selection`），六条断言全绿，
        // 而导出计划读的是 `Selection`，症状正是「点得动、导出不带」。
        boolean writesTruth = hasStatement(src, 8, "val on = !unit.isChecked(sel)")
                && hasStatement(src, 8, "for (handle in unit.handles()) sel.setChecked(handle, on)")
                && hasStatement(src, 8, "val select = !sel.allCheckedHandles(handles)")
                && hasStatement(src, 8, "sel.setCheckedAll(handles, select)");
        check("真相源写入仍在原处：Selection 的勾选/组选判据没被镜像替代（#374 IA-6）",
                writesTruth, "单格判据/写入、组选判据/写入四处任一缺失即失败");

        // 回读函数：必须是「遍历 allUnits → 写值」，且函数体内不得有 `return`（挡早返回的假实现）。
        String syncBody = slice(src, "private fun syncCheckedFromSelection() {", "\n    }\n");
        boolean loops = hasStatement(src, 8, "for (u in allUnits) {");
        boolean writesFromSelection = hasStatement(src, 12,
                "checkedState(u.key).value = sel != null && u.isChecked(sel)");
        boolean noEarlyReturn = syncBody != null && countLines(syncBody, "return") == 0;
        check("回读实现是真遍历：遍历 allUnits、按 Selection 写值、无早返回（#374 IA-6）",
                loops && writesFromSelection && noEarlyReturn,
                "遍历 " + loops + " / 写值 " + writesFromSelection + " / 无 return " + noEarlyReturn
                        + "\nsync 切片：\n" + syncBody);

        int syncAll = countLines(src, "syncCheckedFromSelection()");
        boolean defined = hasStatement(src, 4, "private fun syncCheckedFromSelection() {");
        check("树装载/丢弃四处回读同一实现：定义 1 + 调用 4（#374 IA-6）",
                defined && syncAll == 5, "命中 " + syncAll + " 行（定义 1 + 调用应为 5）");

        check("旧的全墙失效源已移除：不再有 `var selectionVersion`（#374 IA-6）",
                countLines(src, "var selectionVersion") == 0,
                "仍存在 var selectionVersion 字段");

        // 这一条是实测踩出来的：`SnapshotStateMap` 是**整表订阅**，任何写入都会让所有读过它的
        // 作用域失效 —— 用它做细粒度等于没做（E2 上 p50 只从 36 ms 降到 30 ms、janky 仍 13/18）。
        check("细粒度状态不得退回 SnapshotStateMap（整表订阅，实测无效）",
                countLines(src, "mutableStateMapOf") == 0,
                "AppModel 里仍有 mutableStateMapOf：会退化成整墙失效");
    }

    /**
     * 源码里是否存在**整行等于** `text`、且缩进恰好 `indent` 个空格的语句。
     *
     * 比 `contains` 严格两处：① 行必须逐字相等（`x = on ` 之外的包装、注释掉、拼接都挡得住）；
     * ② 缩进必须精确（被 `if (false) { … }` 包一层会多 4 格，判不出来就等于没守卫）。
     */
    private static boolean hasStatement(String src, int indent, String text) {
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < indent; i++) pad.append(' ');
        String want = pad + text;
        for (String line : src.split("\n", -1)) {
            if (line.equals(want)) return true;
        }
        return false;
    }

    /** 源码切片：从 `from` 到 `to`（不含 `to`）；任一端缺失返回 null。 */
    private static String slice(String src, String from, String to) {
        int a = src.indexOf(from);
        if (a < 0) return null;
        int b = src.indexOf(to, a + from.length());
        if (b < 0) return null;
        return src.substring(a, b);
    }

    /** 含 `needle` 的行数（同一行多次只算一次）。 */
    private static int countLines(String src, String needle) {
        int n = 0;
        for (String line : src.split("\n", -1)) {
            if (line.contains(needle)) n++;
        }
        return n;
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
