package com.rea.mtpimport;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static com.rea.mtpimport.TestSupport.*;
import static com.rea.mtpimport.Fixtures.*;
import static com.rea.mtpimport.SourceGuard.*;

/** 目录、渐进发布、聚合口径与分块读取的断言组（#318 从 SelfTest.java 拆出）。 */
final class CatalogCases {

    // ---------------- Catalog ----------------

    static void catalogCases() {
        Catalog c = sample();

        check("Catalog.all() 收录全部对象", c.all().size() == 6, "实际 " + c.all().size());

        check("build() 依 parent 链算出嵌套 relPath",
                "DCIM/100MSDCF/DSC02183.ARW".equals(c.get(3).relPath),
                "实际 " + c.get(3).relPath);

        check("build() 的根节点是 parent=0 的顶层项",
                c.roots().size() == 2 && c.roots().get(0).isFolder(),
                "roots=" + c.roots().size());

        check("childrenOf() 只回直接子节点",
                c.childrenOf(1).size() == 2,
                "实际 " + c.childrenOf(1).size());

        // DCIM 下：100MSDCF(2) + DSC02999.ARW(6)，再加 100MSDCF 里的 3、4
        check("descendantsOf() 含嵌套子文件夹与文件",
                c.descendantsOf(1).size() == 4,
                "实际 " + c.descendantsOf(1).size());

        // 4 个文件：300 + 100 + 50 + 60（顶层 C0001.JPG 也要算进来）
        check("fileBytes() 不含文件夹、按文件累加",
                c.fileBytes() == 300 + 100 + 50 + 60,
                "实际 " + c.fileBytes());

        // 两张 ARW：DSC02183(300) + DSC02999(50)
        check("rawBytes() 只算 ARW", c.rawBytes() == 300 + 50, "实际 " + c.rawBytes());

        check("folderCount()/fileCount() 分类正确",
                c.folderCount() == 2 && c.fileCount() == 4,
                "folder=" + c.folderCount() + " file=" + c.fileCount());
    }

    // ---------------- 渐进发布（#60） ----------------

    /**
     * #60：扫描不再等全量才出照片墙。快照必须与本源解耦；快照长大后
     * 已勾选句柄不得丢。
     */
    static void progressiveCases() {
        Catalog partial = new Catalog();
        partial.add(new Catalog.Entry(1, 0, Catalog.FMT_FOLDER, 0, "DCIM"));
        partial.add(new Catalog.Entry(2, 1, Catalog.FMT_FOLDER, 0, "100MSDCF"));
        partial.add(new Catalog.Entry(3, 2, Catalog.FMT_ARW, 300, "DSC02183.ARW"));

        Catalog first = partial.snapshot();
        check("snapshot：发布时已算好 relPath",
                "DCIM/100MSDCF/DSC02183.ARW".equals(first.get(3).relPath),
                "实际 " + first.get(3).relPath);
        check("snapshot：只含发布时已发现的对象",
                first.all().size() == 3 && first.rawCount() == 1,
                "size=" + first.all().size() + " raw=" + first.rawCount());

        // snapshot() 不得把本源设成 built，否则扫描线程继续 add 会抛 IllegalStateException。
        boolean sourceStillOpen = true;
        try {
            partial.add(new Catalog.Entry(4, 2, Catalog.FMT_ARW, 400, "DSC02184.ARW"));
            partial.build();
        } catch (IllegalStateException e) {
            sourceStillOpen = false;
        }
        check("snapshot：发布不改动本源的 built 状态", sourceStillOpen, "回源 add/build 抛了 IllegalStateException");

        Catalog second = partial.snapshot();
        check("snapshot：旧快照不被后续扫描改写",
                first.all().size() == 3 && first.get(3).relPath != null
                        && "DCIM/100MSDCF/DSC02183.ARW".equals(first.get(3).relPath),
                "size=" + first.all().size());
        check("snapshot：新快照包含继续扫描的对象",
                second.all().size() == 4 && second.rawCount() == 2,
                "size=" + second.all().size() + " raw=" + second.rawCount());

        PhotoWall w1 = PhotoWall.of(first);
        PhotoWall w2 = PhotoWall.of(second);
        check("渐进发布：照片墙张数随快照增长",
                w1.photoCount() == 1 && w2.photoCount() == 2,
                "first=" + w1.photoCount() + " second=" + w2.photoCount());

        Selection s = new Selection(first);
        s.setChecked(3, true);
        check("渐进发布：第一次快照上的勾选生效", s.isChecked(3), "句柄 3 未勾上");

        s.retarget(second);
        check("Selection.retarget：已勾句柄在更大快照里保留",
                s.isChecked(3) && s.fileHandles().size() == 1,
                "checked=" + s.isChecked(3) + " files=" + s.fileHandles());
        s.toggle(4);
        check("Selection.retarget：新句柄可在新快照上勾选",
                s.fileHandles().size() == 2,
                "实际 " + s.fileHandles());
    }

    // ---------------- 渐进发布门槛（#96 的墓碑） ----------------

    /**
     * 墓碑：#96 —— 首屏不该等 ARW。相机只拍 JPG 时目录里始终没有 ARW，
     * 旧门槛 `rawSeen > 0` 恒为 false，照片墙要等整轮扫描结束（约 59 秒）才出现。
     *
     * 判据必须是「已发现至少一张照片」（ARW 或 JPG），且口径与 {@link PhotoWall#of}
     * 的入选集合一致，否则会发布出 0 单元快照、又被 `showPartialCatalog` 拽回 `SCANNING`。
     * 这里按扫描顺序逐项累加，断言第一张照片出现的那一刻判据就已经成立。
     */
    static void progressiveGateCases() {
        Catalog jpgOnly = new Catalog();
        jpgOnly.add(new Catalog.Entry(1, 0, Catalog.FMT_FOLDER, 0, "DCIM"));
        jpgOnly.add(new Catalog.Entry(2, 1, Catalog.FMT_FOLDER, 0, "100MSDCF"));
        check("渐进门槛：只有文件夹时还不发布",
                !jpgOnly.hasPhotoFile(), "文件夹被当成照片");

        jpgOnly.add(new Catalog.Entry(3, 2, Catalog.FMT_JPEG, 120, "DSC00001.JPG"));
        check("渐进门槛：纯 JPG 目录首张照片即具备发布条件（#96）",
                jpgOnly.hasPhotoFile(), "JPG 未被计入照片");
        PhotoWall jpgWall = PhotoWall.of(jpgOnly.build());
        check("渐进门槛：纯 JPG 目录首屏有照片单元",
                jpgWall.photoCount() == 1, "单元数=" + jpgWall.photoCount());
        check("渐进门槛：快照沿用同一判据",
                jpgOnly.snapshot().hasPhotoFile(), "快照丢失照片计数");

        Catalog rawOnly = new Catalog();
        rawOnly.add(new Catalog.Entry(4, 0, Catalog.FMT_ARW, 300, "DSC00002.ARW"));
        check("渐进门槛：ARW-only 目录仍具备发布条件",
                rawOnly.hasPhotoFile(), "ARW 未被计入照片");

        Catalog suffixOnly = new Catalog();
        suffixOnly.add(new Catalog.Entry(5, 0, 0, 0, "DSC00003.JPG"));   // 格式码未知，靠后缀
        check("渐进门槛：格式码未知但后缀是 JPG 也算照片",
                suffixOnly.hasPhotoFile(), "后缀判据未生效");

        Catalog nonPhoto = new Catalog();
        nonPhoto.add(new Catalog.Entry(6, 0, Catalog.FMT_FOLDER, 0, "DCIM"));
        nonPhoto.add(new Catalog.Entry(7, 1, 0x3004, 0, "C0001.MP4"));
        check("渐进门槛：文件夹与 MP4 不算照片",
                !nonPhoto.hasPhotoFile(), "非照片对象被计入照片");
    }

    // ---------------- 聚合与遍历口径（G10 / A11） ----------------

    /**
     * A11 / issue #220：5 个统计 getter 原本各扫一遍全表，口径分散；改为 `add()` 增量维护后，
     * 这里用一份混合目录逐项对账，锁定「增量 = 逐项累加」，并确认快照继承同一口径。
     *
     * 源码守卫：`Catalog` 里只允许 `build()` 与 `snapshot()` 两处全表遍历；`isoDateOf` 必须已删。
     */
    static void aggregateCases() {
        Catalog c = new Catalog();
        c.add(new Catalog.Entry(1, 0, Catalog.FMT_FOLDER, 0, "DCIM"));
        c.add(new Catalog.Entry(2, 1, Catalog.FMT_FOLDER, 0, "100MSDCF"));
        c.add(new Catalog.Entry(3, 2, Catalog.FMT_ARW, 24_000_000L, "DSC00001.ARW"));
        c.add(new Catalog.Entry(4, 2, Catalog.FMT_JPEG, 8_000_000L, "DSC00001.JPG"));
        c.add(new Catalog.Entry(5, 2, Catalog.FMT_ARW, 24_000_000L, "DSC00002.ARW"));      // 无同名 JPG
        c.add(new Catalog.Entry(6, 2, 0x3004, 900_000L, "C0001.MP4"));                      // 非照片
        c.add(new Catalog.Entry(3, 2, Catalog.FMT_ARW, 24_000_000L, "DSC00001.ARW"));       // 重复句柄
        c.build();

        check("聚合：文件夹数与对象总数（重复句柄去重）",
                c.folderCount() == 2 && c.all().size() == 6,
                "folder=" + c.folderCount() + " size=" + c.all().size());
        check("聚合：文件数 = 总数 − 文件夹数", c.fileCount() == 4, "实际 " + c.fileCount());
        check("聚合：全部文件字节", c.fileBytes() == 56_900_000L, "实际 " + c.fileBytes());
        check("聚合：ARW 字节", c.rawBytes() == 48_000_000L, "实际 " + c.rawBytes());
        check("聚合：ARW 张数", c.rawCount() == 2, "实际 " + c.rawCount());

        Catalog snap = c.snapshot();
        check("聚合：快照继承同一口径",
                snap.folderCount() == 2 && snap.fileCount() == 4
                        && snap.fileBytes() == 56_900_000L
                        && snap.rawBytes() == 48_000_000L && snap.rawCount() == 2,
                "snapshot folder=" + snap.folderCount() + " files=" + snap.fileCount()
                        + " bytes=" + snap.fileBytes() + " raw=" + snap.rawCount());

        File srcRoot = SRC_ROOT;
        List<String> hits = new ArrayList<>();
        int scanned = scanSourceText(srcRoot, "String isoDateOf(", null, hits);
        check("日期解析一处实现：isoDateOf 已删（全 src/ 0 命中）", scanned > 0 && hits.isEmpty(),
                "命中：\n" + String.join("\n", hits));
        hits.clear();
        scanned = scanSourceText(srcRoot, "for (Entry e : entries)", null, hits);
        check("Catalog 全表遍历只剩 build() 与 snapshot() 两处", hits.size() == 2,
                "实际 " + hits.size() + " 处：\n" + String.join("\n", hits));
    }

    // ---------------- 只读分块原语（G11 / A9、A10） ----------------

    /**
     * issue #221：`ThumbnailStore.fetchFull` 的分块循环收进 {@link RangeRead}，
     * `decode` / `decodeFull` 合并，三处「epoch 校验 + 落缓存」与 demo 分支收口。
     *
     * 前半用假 reader 锁死短读语义（短返回累计、0 / 负返回判失败、超量返回按请求截断）；
     * 后半是源码守卫——`ThumbnailStore` 依赖 Android `BitmapFactory`，JVM 用例覆盖不到
     * 解码与缓存路径，只能把结构钉在源码上。
     */
    static void rangeReadCases() {
        check("分块读：短返回按实际字节累计读满",
                sequential(tryRead(10, 4, (offset, want, buf) -> {
                    int n = Math.min(want, 2);
                    fillAt(buf, n, offset);
                    return n;
                }), 10),
                "返回 null 或长度 / 内容不符");

        check("分块读：返回 0 判失败",
                tryRead(10, 4, (offset, want, buf) -> 0) == null,
                "返回 0 仍拼出了字节");

        check("分块读：返回负数判失败",
                tryRead(10, 4, (offset, want, buf) -> -1) == null,
                "返回负数仍拼出了字节");

        check("分块读：超量返回按请求长度截断",
                sequential(tryRead(10, 4, (offset, want, buf) -> {
                    fillAt(buf, want, offset);
                    return want + 3;   // 设备返回超过请求长度
                }), 10),
                "超量返回越界或长度 / 内容不符");

        check("分块读：size / chunk 非正数直接返回 null",
                tryRead(0, 4, (offset, want, buf) -> 1) == null
                        && tryRead(10, 0, (offset, want, buf) -> 1) == null,
                "非正数参数仍进入读取");

        File srcRoot = SRC_ROOT;
        List<String> hits = new ArrayList<>();
        int scanned = scanSourceText(srcRoot, "private static Bitmap decodeFull", null, hits);
        check("缩略图：decodeFull 已并入 decode(bytes, maxEdge)", scanned > 0 && hits.isEmpty(),
                "命中：\n" + String.join("\n", hits));

        hits.clear();
        scanned = scanSourceText(srcRoot, "Bitmap decode(byte[] bytes, int maxEdge)", null, hits);
        check("缩略图：decode 只剩一个实现（带 maxEdge）", hits.size() == 1,
                "实际 " + hits.size() + " 处：\n" + String.join("\n", hits));

        hits.clear();
        scanned = scanSourceText(srcRoot, "decode(bytes, MAX_EDGE)", null, hits);
        int maxEdgeCalls = hits.size();
        hits.clear();
        scanned = scanSourceText(srcRoot, "decode(bytes, FULL_EDGE)", null, hits);
        check("缩略图：两档解码各自传长边常量",
                maxEdgeCalls == 1 && hits.size() == 1,
                "MAX_EDGE=" + maxEdgeCalls + " FULL_EDGE=" + hits.size());

        hits.clear();
        scanned = scanSourceText(srcRoot, "return commit(", null, hits);
        check("缩略图：三处加载共用 commit 代际守卫", hits.size() == 3,
                "实际 " + hits.size() + " 处：\n" + String.join("\n", hits));

        hits.clear();
        scanned = scanSourceText(srcRoot, "PhotoPreview d = demo;", null, hits);
        check("缩略图：demo 分支只在一处取值", scanned > 0 && hits.size() == 1,
                "实际 " + hits.size() + " 处：\n" + String.join("\n", hits));

        hits.clear();
        scanned = scanSourceText(srcRoot, "demoPreview(", null, hits);
        check("缩略图：demoPreview 一处声明 + 三处调用", hits.size() == 4,
                "实际 " + hits.size() + " 处：\n" + String.join("\n", hits));

        hits.clear();
        scanned = scanSourceText(srcRoot, "synchronized (epochLock)", null, hits);
        check("缩略图：epochLock 只在 setSession / clear / commit 使用", hits.size() == 3,
                "实际 " + hits.size() + " 处：\n" + String.join("\n", hits));

        hits.clear();
        scanned = scanSourceText(srcRoot, "while (offset < entry.size)", null, hits);
        check("缩略图：分块循环已移出 ThumbnailStore", scanned > 0 && hits.isEmpty(),
                "命中：\n" + String.join("\n", hits));

        hits.clear();
        scanned = scanSourceText(srcRoot, "RangeRead.read(", null, hits);
        check("缩略图：整张 JPG 走 RangeRead 单一原语", hits.size() == 1,
                "实际 " + hits.size() + " 处：\n" + String.join("\n", hits));

        hits.clear();
        scanned = scanSourceText(srcRoot, "applyExifOrientation(decoded, bytes)", null, hits);
        check("缩略图：decode 解码后统一经 applyExifOrientation 纠偏（issue #367, #368）",
                hits.size() == 1, "命中处数：" + hits.size());

        hits.clear();
        scanned = scanSourceText(srcRoot, "ExifInterface.TAG_ORIENTATION", null, hits);
        check("缩略图：接入 EXIF TAG_ORIENTATION 姿态检测（issue #367, #368）",
                hits.size() >= 1, "命中处数：" + hits.size());

        hits.clear();
        scanned = scanSourceText(srcRoot, "matrix.postRotate(90)", null, hits);
        int rot90 = hits.size();
        hits.clear();
        scanned = scanSourceText(srcRoot, "matrix.postRotate(180)", null, hits);
        int rot180 = hits.size();
        hits.clear();
        scanned = scanSourceText(srcRoot, "matrix.postRotate(270)", null, hits);
        int rot270 = hits.size();
        check("缩略图：涵盖 90/180/270 三档旋转（issue #367, #368）",
                rot90 >= 1 && rot180 >= 1 && rot270 >= 1,
                "rot90=" + rot90 + " rot180=" + rot180 + " rot270=" + rot270);

        hits.clear();
        scanned = scanSourceText(srcRoot, "Bitmap.createBitmap(decoded", null, hits);
        check("缩略图：Matrix 纠偏生成新位图（issue #367, #368）",
                hits.size() >= 1, "命中处数：" + hits.size());

        hits.clear();
        scanned = scanSourceText(srcRoot, "decoded.recycle()", null, hits);
        check("缩略图：旋转后显式回收未旋转位图（issue #367, #368）",
                hits.size() >= 1, "命中处数：" + hits.size());
    }

    /** 跑一次分块读，异常按失败（返回 null）处理，避免越界异常中断整个自检。 */
    private static byte[] tryRead(long size, int chunk, RangeRead.Reader reader) {
        try {
            return RangeRead.read(size, chunk, reader);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 假 reader 的填充：第 i 个字节固定为 (byte) i（offset 连续推进时内容也连续）。 */
    private static void fillAt(byte[] buf, int n, long offset) {
        for (int i = 0; i < n; i++) buf[i] = (byte) (offset + i);
    }

    /** 断言「长度正确且逐字节连续」。 */
    private static boolean sequential(byte[] got, int expected) {
        if (got == null || got.length != expected) return false;
        for (int i = 0; i < expected; i++) {
            if (got[i] != (byte) i) return false;
        }
        return true;
    }

    // ---------------- 截断计数（#15 的墓碑） ----------------

    /**
     * 墓碑：#15 —— 底部「还有 N 项未显示」的 N 必须是全树总数 − 已渲染行数，
     * 不能是截断集合的差值。旧实现把遍历卡在 MAX_ROWS + 200，导致 N 恒 ≤200。
     */
    static void truncationCases() {
        Catalog big = flatCatalog(503, 1023);          // 与真机一致：1526 个对象
        Catalog.Flat f = big.flatten(800);
        check("flatten：1526 节点 / 上限 800 时总数仍为 1526", f.total == 1526, "实际 " + f.total);
        check("flatten：1526 节点 / 上限 800 时只渲染 800 行", f.rows.size() == 800,
                "实际 " + f.rows.size());
        check("flatten：1526 节点 / 上限 800 时真实缺口为 726（#15）",
                f.remaining() == 726, "实际 " + f.remaining());

        // 边界：恰好 800 不提示；801 提示 1；1001 提示 201（旧实现从 1001 起恒显示 200）
        Catalog exact = flatCatalog(1, 799);
        Catalog.Flat fe = exact.flatten(800);
        check("flatten：恰好 800 行时缺口为 0",
                fe.total == 800 && fe.rows.size() == 800 && fe.total - fe.rows.size() == 0,
                "total=" + fe.total + " rows=" + fe.rows.size());

        Catalog over = flatCatalog(1, 800);
        Catalog.Flat fo = over.flatten(800);
        check("flatten：801 行时缺口为 1",
                fo.total == 801 && fo.rows.size() == 800 && fo.total - fo.rows.size() == 1,
                "total=" + fo.total + " rows=" + fo.rows.size());

        Catalog over1000 = flatCatalog(1, 1000);
        Catalog.Flat f1000 = over1000.flatten(800);
        check("flatten：1001 行时缺口为 201（#15 的旧实现在这里恒显示 200）",
                f1000.total == 1001 && f1000.rows.size() == 800
                        && f1000.total - f1000.rows.size() == 201,
                "total=" + f1000.total + " rows=" + f1000.rows.size()
                        + " gap=" + (f1000.total - f1000.rows.size()));

        Catalog.Flat small = sample().flatten(3);
        check("flatten：保持父项先于子项的深度优先顺序",
                small.total == 6 && small.rows.size() == 3
                        && small.rows.get(0).handle == 1
                        && small.rows.get(1).handle == 2
                        && small.rows.get(2).handle == 3,
                "total=" + small.total + " rows=" + small.rows);
    }
}
