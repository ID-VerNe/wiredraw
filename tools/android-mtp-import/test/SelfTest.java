package com.rea.mtpimport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;

import com.rea.mtpimport.ResumableTransfer.Kind;

/**
 * JVM 纯逻辑自检（不需要真机；Gradle 配置阶段仍要能定位 Android SDK）。
 *
 * 覆盖 build.gradle.kts 的 `pureJdkClasses` 清单里那些纯 JDK 类（Catalog / Selection / ImportPlan /
 * TransferProgress / Journal / AttachGate 等，清单本身是唯一事实来源）；
 * 它们不 import android.*，所以能在普通 JVM 里直接跑，这也是骨架里唯一
 * 「能拿门禁守住」的部分。真机相关的 MtpSession / CatalogScanner / MtpTransfer
 * 不在本自检的覆盖范围（需要设备），ImportService 只覆盖纯 JDK 编排层，
 * 见 README 的真机待验清单。
 *
 * 运行：bash tools/android-mtp-import/gradlew -p tools/android-mtp-import selfTest
 */
public final class SelfTest {

    static int passed = 0;
    static int failed = 0;
    static final StringBuilder failures = new StringBuilder();

    public static void main(String[] args) throws Exception {
        catalogCases();
        progressiveCases();
        progressiveGateCases();
        truncationCases();
        attachCases();
        permissionCases();
        allowCases();
        resolveCases();
        scanCancelCases();
        scanReleaseCases();
        selectionCases();
        groupSelectionCases();
        planCases();
        spaceCases();
        progressCases();
        progressFileCases();
        journalCases();
        cancelTokenCases();
        importServiceCases();
        indexStoppedCases();
        storageConflictCases();
        destinationPolicyCases();
        pauseCancelCases();
        resumePlanCases();
        importServicePauseClockCases();
        resumeDisabledCases();
        noResumeSourceCases();
        resumeProgressCases();
        partResidueCases();
        uiStateCases();
        uiTransitionCases();
        finishOutcomeCases();
        probeVerdictCases();
        stalledDetectionCases();
        stalledServiceCases();
        importGateCases();
        topBarSpecCases();
        ringTrackCases();
        scanLineCases();
        detachCases();
        requestScanLogCases();
        colorTokensCases();
        backNavCases();
        uiTextCases();
        photoWallCases();
        aggregateCases();
        rangeReadCases();
        derivedValueCases();
        derivedSourceCases();
        typeTokenCases();
        dimsCases();
        typeSourceCases();
        layoutShapeCases();
        demoTreesCases();
        demoTransferCases();
        commentRefCases();
        skippedSourceCases();
        duplicateTextCases();
        importEntryCases();
        importRoundListenerCases();
        spaceGateCases();
        orphanRoundsCases();
        pauseSwitchCases();
        stuckErrorCases();
        dirsCases();
        roundFilesCases();
        entryPathCases();
        skipRuleCases();

        System.out.println();
        if (failed == 0) {
            System.out.println("== 自检全绿：" + passed + " 项 ==");
            System.exit(0);
        }
        System.out.println("== 自检红 " + failed + " 项（绿 " + passed + " 项）==");
        System.out.print(failures);
        System.exit(1);
    }

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

        File srcRoot = new File(System.getProperty("user.dir"), "src");
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

        File srcRoot = new File(System.getProperty("user.dir"), "src");
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

    // ---------------- AttachGate（#17 的墓碑） ----------------

    /**
     * 墓碑：#17 —— 插线事件必须收敛到同一个准入判定。
     *
     * 两个真实的回归点：
     * 1. 同一次插拔可能从 intent 与广播两条路投递两次；第二次必须被拒，
     *    否则会开两个 MTP 会话（相机同一时刻只接受一个）。
     * 2. 拒绝路径不得占用扫描锁：一次「没设备」的 attach 若置了锁，
     *    后面真正插上相机时会被永久拒掉。
     */
    static void attachCases() {
        AttachGate g = new AttachGate();

        // 每个用例只调一次 admit()：它有副作用（准入会置锁），重复调用会把用例自己搞脏。
        AttachGate.Admit a = g.admit(false, false);
        check("attach：没设备时拒绝", a == AttachGate.Admit.NOTHING_NO_DEVICE, "实际 " + a);
        check("attach：没设备的拒绝不占用扫描锁（否则后续真 attach 被永久拒掉）",
                !g.isScanning(), "isScanning=" + g.isScanning());

        a = g.admit(true, false);
        check("attach：没设备拒绝后，设备插上仍可准入", a == AttachGate.Admit.GO, "实际 " + a);
        g.finish();

        a = g.admit(true, true);
        check("attach：传输进行中拒绝", a == AttachGate.Admit.NOTHING_TRANSFERRING, "实际 " + a);
        check("attach：传输中的拒绝不占用扫描锁",
                !g.isScanning(), "isScanning=" + g.isScanning());

        a = g.admit(true, false);
        check("attach：设备在线且空闲时准入", a == AttachGate.Admit.GO, "实际 " + a);
        check("attach：准入后立即持有扫描锁", g.isScanning(), "isScanning=" + g.isScanning());

        a = g.admit(true, false);
        check("attach：准入后重复 attach 被拒（同一次插拔投递两次不得开两个会话）",
                a == AttachGate.Admit.NOTHING_ALREADY_SCANNING, "实际 " + a);
        check("attach：重复 attach 被拒不释放已有的扫描锁",
                g.isScanning(), "isScanning=" + g.isScanning());

        g.finish();
        check("attach：finish() 释放锁", !g.isScanning(), "isScanning=" + g.isScanning());
        a = g.admit(true, false);
        check("attach：finish() 后可再次准入", a == AttachGate.Admit.GO, "实际 " + a);
    }

    // ---------------- PermGate（#11 的墓碑） ----------------

    /**
     * 墓碑：#11 —— 权限等待门必须每次申请都重建，且只认本次序号的回调。
     *
     * 两个真实回归点：
     * 1. 第一次授权成功后 latch 归零并保持 0：不重建的话第二次 await 立即放行、
     *    返回的还是上一次的 granted=true，最终表现为 MtpDevice.open() 失败（#11）。
     * 2. 上一次申请超时后用户才点弹窗，迟到的广播会把新一次申请误标成已回答 / 已授权。
     *
     * 等待都用毫秒级超时，不依赖真实 120 s。
     */
    static void permissionCases() throws InterruptedException {
        PermGate g = new PermGate();

        int s1 = g.begin();
        check("perm：第一次申请的序号从 1 开始", s1 == 1, "实际 " + s1);
        check("perm：begin 之后、回调之前不算已授权", !g.granted(), "granted=" + g.granted());
        check("perm：当前序号的回调被接受", g.onResult(s1, true), "返回值 false");
        check("perm：授权回调后 granted 为 true", g.granted(), "granted=" + g.granted());
        check("perm：本次 answered 后 await 立即返回",
                g.await(1, TimeUnit.SECONDS), "await=false");

        int s2 = g.begin();
        check("perm：第二次申请序号递增", s2 == s1 + 1, "实际 " + s2);
        check("perm：第二次 begin 复位上一次的 granted，不残留 true",
                !g.granted(), "granted=" + g.granted());
        boolean waited = g.await(50, TimeUnit.MILLISECONDS);
        check("perm：第二次申请没有新回调时必须超时，不得立即放行（#11 的根因）",
                !waited, "await=" + waited);

        // 第一次申请迟到的回调不得污染第二次
        check("perm：旧序号的回调被忽略", !g.onResult(s1, true), "返回值 true");
        check("perm：旧回调不释放本次等待", !g.await(20, TimeUnit.MILLISECONDS), "await=true");
        check("perm：旧回调不写 granted", !g.granted(), "granted=" + g.granted());

        check("perm：新序号的拒绝回调被接受", g.onResult(s2, false), "返回值 false");
        check("perm：拒绝后 granted 为 false", !g.granted(), "granted=" + g.granted());
        check("perm：拒绝后 await 立即返回（等的是回答，不是授权）",
                g.await(1, TimeUnit.SECONDS), "await=false");
        check("perm：同一序号的重复回调被忽略", !g.onResult(s2, true), "返回值 true");
        check("perm：重复回调不改写拒绝结果", !g.granted(), "granted=" + g.granted());

        g.begin();
        boolean timedOut = g.await(20, TimeUnit.MILLISECONDS);
        check("perm：超时的 await 返回 false 且不算已回答",
                !timedOut && !g.granted(), "await=" + timedOut + " granted=" + g.granted());

        g.cancel();
        check("perm：cancel() 提前释放 await，但不能被当成授权",
                g.await(1, TimeUnit.SECONDS) && !g.granted(),
                "granted=" + g.granted());

        // 迟到回调的最坏形态（#11 复评）：第一轮已被「允许」，第二轮 begin() 之后
        // 第一轮的回调才到。PendingIntent 若共用一条记录（requestCode 固定 0 +
        // FLAG_UPDATE_CURRENT），这条迟到广播会带着第二轮写入的新 seq 到达，
        // 序号判定就被绕过；requestCode=seq 让每条记录自带本轮 extras。
        // 这里钉住 PermGate 这一侧的合同：拿旧 seq 回来一定不被接受。
        PermGate late = new PermGate();
        int round1 = late.begin();
        check("perm：迟到用例的第一轮序号为 1", round1 == 1, "实际 " + round1);
        check("perm：第一轮允许生效", late.onResult(round1, true) && late.granted(),
                "granted=" + late.granted());

        int round2 = late.begin();
        check("perm：第二轮 begin 复位了第一轮的 granted", !late.granted(), "granted=" + late.granted());
        check("perm：迟到回调（第一轮 seq）不被接受", !late.onResult(round1, true), "返回值 true");
        check("perm：迟到回调不把 granted 改成 true", !late.granted(), "granted=" + late.granted());
        check("perm：迟到回调没有释放第二轮的等待",
                !late.await(30, TimeUnit.MILLISECONDS), "await=true");
        check("perm：迟到回调之后，第二轮自己的回调仍然生效",
                late.onResult(round2, true) && late.granted(), "granted=" + late.granted());
    }

    // ---------------- PermGate 重发（PR #41 首轮评审 B1） ----------------

    /**
     * 墓碑 B1：`PERMISSION_PENDING` 里点 `Allow` 必须能把权限申请重发一次，
     * 且**新序号**的回调要能结束扫描线程正在进行的同一次等待。
     *
     * 现场：扫描线程阻塞在 `permGate.await`（第一轮），用户点 `Allow` → `begin()` 开第二轮。
     * 旧实现里 `await` 只捕获调用瞬间的 latch、`begin()` 又不唤醒旧 latch，
     * 扫描线程会一直卡在第一轮的 latch 上直到 120 s 超时——界面看起来「点了 Allow 没反应」，
     * 这正是 U-42 必失败的原因。
     *
     * 等待用秒级超时，不依赖真实 120 s；join 只等 2 s，旧实现会红在最后一条断言上。
     */
    static void allowCases() throws InterruptedException {
        PermGate g = new PermGate();
        int first = g.begin();
        final AtomicBoolean released = new AtomicBoolean(false);
        Thread scanner = new Thread(() -> {
            try {
                released.set(g.await(3, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "perm-waiter");
        scanner.start();
        // 让扫描线程确实阻塞在 await 上，再模拟「点 Allow」
        Thread.sleep(100);

        int second = g.begin();
        check("allow：重发生成新序号", second == first + 1, "first=" + first + " second=" + second);
        check("allow：重发不得把未授权的等待当成已回答放行",
                !released.get(), "released=" + released.get());
        check("allow：重发后、Allow 的回调到达前扫描线程仍在等待",
                scanner.isAlive(), "isAlive=" + scanner.isAlive());

        boolean accepted = g.onResult(second, true);
        scanner.join(2_000);
        check("allow：重发后的新序号回调被接受", accepted, "accepted=" + accepted);
        check("allow：重发后的新序号回调结束同一次等待（U-42 的根因）",
                !scanner.isAlive() && released.get() && g.granted(),
                "isAlive=" + scanner.isAlive() + " released=" + released.get()
                        + " granted=" + g.granted());
    }

    // ---------------- PermGate 直接判定（PR #41 第三轮评审小项 1） ----------------

    /**
     * 墓碑：`PERMISSION_PENDING` 里点 `Allow` 时 `UsbManager.hasPermission()` 已经为真，
     * 系统广播可能永远不会再来；只记日志会让扫描线程继续卡在 `await` 上直到 120 s 超时。
     * `resolve(true)` 要把这一轮直接判成「已授权」并释放等待，重复调用不得改写结果。
     */
    static void resolveCases() throws InterruptedException {
        PermGate g = new PermGate();
        int seq = g.begin();
        final AtomicBoolean released = new AtomicBoolean(false);
        Thread scanner = new Thread(() -> {
            try {
                released.set(g.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "perm-resolve-waiter");
        scanner.start();
        Thread.sleep(100);

        boolean resolved = g.resolve(true);
        scanner.join(2_000);
        check("resolve：直接判定已授权并释放等待（小项 1 的根因）",
                resolved && !scanner.isAlive() && released.get() && g.granted(),
                "resolved=" + resolved + " isAlive=" + scanner.isAlive()
                        + " released=" + released.get() + " granted=" + g.granted());
        check("resolve：已回答过的一轮不再被改写",
                !g.resolve(false) && g.granted(), "granted=" + g.granted());
        check("resolve：序号没被偷偷 +1", g.begin() == seq + 1, "seq 漂移");
    }


    // ---------------- 扫描取消（PR #41 第四轮评审 B1 / U-06 / P-02） ----------------

    /**
     * 墓碑：`SCANNING` 点 `Cancel` 后，扫描线程迟到的进度、结果、错误都不得再改界面。
     *
     * 这里用一个字符串代替 `AppModel` 的界面态，判的仍是 `ScanRun.accept()` 这道闸——
     * `Main.scanWorker` 的进度回调、`showCatalog`、`showError` 三处都先问它。
     * 取消必须真取消：只把界面退回 `COLD` 而不置位，扫描完成时会把界面翻回
     * `CATALOG_READY`（第四轮评审 B1 的现象）。
     */
    static void scanCancelCases() {
        ScanRun run = new ScanRun();
        String[] screen = {"SCANNING"};
        int[] progressLanded = {0};

        // 用户在 SCANNING 点 Cancel：Main.onCancel 置位令牌，再把界面退回目录。
        run.cancel();
        screen[0] = "COLD";

        // 取消之后才到达的三类迟到回调。
        if (run.accept()) progressLanded[0]++;
        if (run.accept()) screen[0] = "CATALOG_READY";
        if (run.accept()) screen[0] = "ERROR";

        check("取消后迟到的扫描结果不得翻回 CATALOG_READY", "COLD".equals(screen[0]),
                "界面停在 " + screen[0]);
        check("取消后不再刷新扫描进度", progressLanded[0] == 0,
                "落地 " + progressLanded[0] + " 次");
        check("cancel() 置位后 isCancelled() 为真", run.isCancelled(), "未置位");

        // 对照：没取消时三类回调都必须照常落地，否则上面两条恒真、测不出回归。
        ScanRun live = new ScanRun();
        String[] liveScreen = {"SCANNING"};
        int[] liveProgress = {0};
        if (live.accept()) liveProgress[0]++;
        if (live.accept()) liveScreen[0] = "CATALOG_READY";
        check("未取消时扫描结果照常落到照片墙", "CATALOG_READY".equals(liveScreen[0]),
                "界面停在 " + liveScreen[0]);
        check("未取消时进度照常刷新", liveProgress[0] == 1,
                "落地 " + liveProgress[0] + " 次");
    }

    // ---------------- 扫描锁与重试循环的取消交互（PR #41 第五轮评审 B-1） ----------------

    /**
     * 墓碑 B-1：取消必须让扫描线程**尽快退出**，否则 {@link AttachGate} 的扫描锁不释放，
     * README 里「取消后再点 `Connect camera` 能重新开始扫描」就是假的。
     *
     * 钉三件事（旧实现全部不成立）：
     * 1. `storageIds` 的重试循环在取消后最多只多睡一轮 —— 旧实现睡满 8 × 3 秒 ≈ 24 秒；
     * 2. 已经取消的话一次都不取、一次都不睡（不再碰相机）；
     * 3. `admit → 取消 → 工作线程收尾 finish() → admit` 必须重新拿到 `GO`。
     *    原来只有 `ScanRun` 自身的 4 条用例，锁与重试循环的交互没人守（评审原话）。
     */
    static void scanReleaseCases() {
        // 取消发生在第一次重试的等待里：只许 1 次取 + 1 次睡，然后返回空数组。
        ScanRun run = new ScanRun();
        int[] fetches = {0};
        int[] sleeps = {0};
        StorageRetry.Sleeper cancellingSleeper = ms -> {
            sleeps[0]++;
            run.cancel();          // 模拟 UI 线程在这 3 秒里点了 Cancel
        };
        int[] sids = StorageRetry.fetch(() -> {
            fetches[0]++;
            return new int[0];
        }, cancellingSleeper, run, 8, 3000, Log.NOOP);
        check("取消后第 1 次重试即返回空数组（不再睡满 8 轮 ≈ 24 秒）",
                sids.length == 0 && fetches[0] == 1 && sleeps[0] == 1,
                "sids=" + sids.length + " fetch=" + fetches[0] + " sleep=" + sleeps[0]);
        check("取消后重试循环不再发起第 2 次取",
                fetches[0] == 1, "fetch=" + fetches[0]);

        // 已经取消：一次都不取、一次都不睡。
        ScanRun dead = new ScanRun();
        dead.cancel();
        int[] fetches2 = {0};
        int[] sleeps2 = {0};
        int[] empty = StorageRetry.fetch(() -> {
            fetches2[0]++;
            return new int[0];
        }, ms -> sleeps2[0]++, dead, 8, 3000, Log.NOOP);
        check("已取消时 getStorageIds 一次都不调（不碰相机）",
                empty.length == 0 && fetches2[0] == 0, "fetch=" + fetches2[0]);
        check("已取消时一次都不睡（不等 3 秒）", sleeps2[0] == 0, "sleep=" + sleeps2[0]);

        // onCancel 会 interrupt 扫描线程：等待被打断要立刻退出，不睡满一轮。
        int[] fetches3 = {0};
        int[] interrupted = StorageRetry.fetch(() -> {
            fetches3[0]++;
            return new int[0];
        }, ms -> {
            throw new InterruptedException("模拟 onCancel 的 interrupt");
        }, new ScanRun(), 8, 3000, Log.NOOP);
        Thread.interrupted();      // 清掉 StorageRetry 回写的标志，别影响后续用例
        check("等待被 interrupt 时立即退出（不再睡满 8 轮）",
                interrupted.length == 0 && fetches3[0] == 1, "fetch=" + fetches3[0]);

        // 对照：没取消时必须照常重试满 8 轮，否则上面「只睡 1 轮」恒真、测不出回归。
        int[] fetches4 = {0};
        int[] sleeps4 = {0};
        int[] exhausted = StorageRetry.fetch(() -> {
            fetches4[0]++;
            return new int[0];
        }, ms -> sleeps4[0]++, new ScanRun(), 8, 3000, Log.NOOP);
        check("未取消时重试满 8 轮（对照）",
                exhausted.length == 0 && fetches4[0] == 8 && sleeps4[0] == 8,
                "fetch=" + fetches4[0] + " sleep=" + sleeps4[0]);

        // 扫描锁：取消 → 工作线程收尾 finish() → 再次 attach 必须重新准入（U-06 / P-02）。
        AttachGate gate = new AttachGate();
        check("取消前首次 attach 准入",
                gate.admit(true, false) == AttachGate.Admit.GO, "未准入");
        run.cancel();              // Main.onCancel
        gate.finish();             // scanWorker 的 finally（此时工作线程已退出）
        AttachGate.Admit again = gate.admit(true, false);
        check("取消并收尾后再点 Connect camera 必须重新准入（不得停在 NOTHING_ALREADY_SCANNING）",
                again == AttachGate.Admit.GO, "实际 " + again);
        gate.finish();
    }

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

    // ---------------- TransferProgress ----------------

    static void progressCases() {
        TransferProgress p = new TransferProgress(1000, 2);
        long t0 = 1_000_000_000L;
        p.start(t0);

        p.onBytes(250);
        check("fraction 按已传/总量计算",
                Math.abs(p.fraction() - 0.25) < 1e-9, "实际 " + p.fraction());

        p.onBytes(5000);
        check("超出总量的字节被钳到 1.0（不出现 105%）",
                Math.abs(p.fraction() - 1.0) < 1e-9 && p.bytesDone() == 1000,
                "fraction=" + p.fraction() + " done=" + p.bytesDone());

        p.onBytes(-999);
        check("回退的负数不减少已完字节", p.bytesDone() == 1000, "实际 " + p.bytesDone());

        TransferProgress zero = new TransferProgress(0, 0);
        zero.start(t0);
        check("总量为 0 不除零，fraction 返回 0.0",
                Math.abs(zero.fraction()) < 1e-9, "实际 " + zero.fraction());

        TransferProgress speed = new TransferProgress(10_000_000, 1);
        speed.start(t0);
        speed.onBytes(5_000_000);
        check("bytesPerSec 用注入时钟：5 MB / 1 s = 5 MB/s",
                Math.abs(speed.bytesPerSec(t0 + 1_000_000_000L) - 5_000_000) < 1.0,
                "实际 " + speed.bytesPerSec(t0 + 1_000_000_000L));

        check("etaSeconds = 剩余 / 速度，向上取整",
                speed.etaSeconds(t0 + 1_000_000_000L) == 1,
                "实际 " + speed.etaSeconds(t0 + 1_000_000_000L));

        // 剩余 7 MB / 3 MB/s = 2.33 s：向上取整应为 3，截断会得 2，用来钉住 ceil
        TransferProgress frac = new TransferProgress(10_000_000, 1);
        frac.start(t0);
        frac.onBytes(3_000_000);
        check("etaSeconds 对非整数秒向上取整（2.33 s → 3 s）",
                frac.etaSeconds(t0 + 1_000_000_000L) == 3,
                "实际 " + frac.etaSeconds(t0 + 1_000_000_000L));

        TransferProgress stalled = new TransferProgress(100, 1);
        stalled.start(t0);
        check("速度为 0 时 eta 返回 -1 而不是无穷大",
                stalled.etaSeconds(t0) == -1, "实际 " + stalled.etaSeconds(t0));

        // ---------------- #58：暂停时长不得计入速率/ETA ----------------
        // 场景：10 MB 已传、实际传输 1 s、随后暂停 100 s。
        // 修复前分母是 101 s（≈0.099 MB/s）；修复后应按实际传输的 1 s 算（10 MB/s）。
        long pauseT0 = 1_000_000_000L;
        long pauseEnd = pauseT0 + 101_000_000_000L;

        TransferProgress paused = new TransferProgress(20_000_000, 1);
        paused.start(pauseT0);
        paused.onBytes(10_000_000);
        paused.pause(pauseT0 + 1_000_000_000L);
        paused.resume(pauseEnd);
        check("暂停 100 s 后速率仍按实际传输 1 s 计（10 MB/s）",
                Math.abs(paused.bytesPerSec(pauseEnd) - 10_000_000) < 1.0,
                "实际 " + paused.bytesPerSec(pauseEnd));
        check("暂停 100 s 后 ETA 与不暂停一致（剩余 10 MB / 10 MB/s = 1 s）",
                paused.etaSeconds(pauseEnd) == 1,
                "实际 " + paused.etaSeconds(pauseEnd));

        TransferProgress duringPause = new TransferProgress(20_000_000, 1);
        duringPause.start(pauseT0);
        duringPause.onBytes(10_000_000);
        duringPause.pause(pauseT0 + 1_000_000_000L);
        check("暂停中读取速率不随暂停时长下降",
                Math.abs(duringPause.bytesPerSec(pauseT0 + 51_000_000_000L) - 10_000_000) < 1.0,
                "实际 " + duringPause.bytesPerSec(pauseT0 + 51_000_000_000L));

        TransferProgress doublePause = new TransferProgress(20_000_000, 1);
        doublePause.start(pauseT0);
        doublePause.onBytes(10_000_000);
        doublePause.pause(pauseT0 + 1_000_000_000L);
        doublePause.pause(pauseT0 + 6_000_000_000L);
        doublePause.resume(pauseEnd);
        check("重复 pause 只认第一次，不把暂停拆成两段重复扣",
                Math.abs(doublePause.bytesPerSec(pauseEnd) - 10_000_000) < 1.0,
                "实际 " + doublePause.bytesPerSec(pauseEnd));

        TransferProgress noPair = new TransferProgress(20_000_000, 1);
        noPair.start(pauseT0);
        noPair.onBytes(5_000_000);
        noPair.resume(pauseT0 + 2_000_000_000L);
        check("没有 pause 的 resume 是 no-op（5 MB / 2 s）",
                Math.abs(noPair.bytesPerSec(pauseT0 + 2_000_000_000L) - 2_500_000) < 1.0,
                "实际 " + noPair.bytesPerSec(pauseT0 + 2_000_000_000L));

        // 第二轮 start 必须清掉上一轮的暂停累计，否则新一轮会被上一轮的 100 s 拖成负时长。
        TransferProgress restarted = new TransferProgress(20_000_000, 1);
        restarted.start(pauseT0);
        restarted.onBytes(10_000_000);
        restarted.pause(pauseT0 + 1_000_000_000L);
        restarted.resume(pauseEnd);
        long secondStart = pauseT0 + 200_000_000_000L;
        restarted.start(secondStart);
        restarted.onBytes(10_000_000);
        check("start() 重置暂停累计（第二轮按自己的 1 s 计时）",
                Math.abs(restarted.bytesPerSec(secondStart + 1_000_000_000L) - 10_000_000) < 1.0,
                "实际 " + restarted.bytesPerSec(secondStart + 1_000_000_000L));
    }

    // ---------------- 文件内进度（issue #70） ----------------

    /**
     * 文件内进度的记账单元：已完成字节（doneBytes）与当前文件字节分开，bytesDone() 是两者之和。
     *
     * 关键两条：
     * - `beginFile` 是**重设**当前文件，不是往已完成字节里再加一遍（重试不得双计）；
     * - `onFileProgress` 收**绝对偏移**，回退被忽略、超出文件大小被钳。
     */
    static void progressFileCases() {
        long t0 = 1_000_000_000L;

        TransferProgress p = new TransferProgress(200, 2);
        p.start(t0);
        p.onBytes(100);
        p.beginFile(40, 100);
        check("文件内进度：beginFile 把已落盘的 40 字节计入总量（100 + 40 = 140）",
                p.bytesDone() == 140, "实际 " + p.bytesDone());

        p.onFileProgress(60);
        check("文件内进度：onFileProgress 按绝对偏移上报（100 + 60 = 160）",
                p.bytesDone() == 160, "实际 " + p.bytesDone());

        p.onFileProgress(50);
        check("文件内进度：绝对偏移回退被忽略（160 不降到 150）",
                p.bytesDone() == 160, "实际 " + p.bytesDone());

        p.beginFile(40, 100);
        check("文件内进度：同一文件重新 beginFile 是重设而不是累加（仍为 140）",
                p.bytesDone() == 140, "实际 " + p.bytesDone());

        p.onFileProgress(60);
        p.commitFile(100);
        check("文件内进度：commitFile 只按 size 记一次，不叠加当前文件字节（总 200）",
                p.bytesDone() == 200 && p.filesDone() == 1,
                "bytes=" + p.bytesDone() + " files=" + p.filesDone());

        TransferProgress rb = new TransferProgress(200, 2);
        rb.start(t0);
        rb.onBytes(100);
        rb.beginFile(40, 100);
        rb.onFileProgress(80);
        rb.rollbackFile();
        check("文件内进度：失败回退只丢掉当前文件，保留已完成部分（100）",
                rb.bytesDone() == 100, "实际 " + rb.bytesDone());

        TransferProgress cl = new TransferProgress(200, 2);
        cl.start(t0);
        cl.beginFile(0, 100);
        cl.onFileProgress(120);
        check("文件内进度：当前文件进度被钳在 size（120 → 100）",
                cl.bytesDone() == 100, "实际 " + cl.bytesDone());
        cl.onFileProgress(-5);
        check("文件内进度：负的绝对偏移被忽略（仍为 100）",
                cl.bytesDone() == 100, "实际 " + cl.bytesDone());
    }

    // ---------------- Journal ----------------

    static void journalCases() throws RuntimeException {
        Journal j = new Journal();
        j.markDone(12345, 7340032, 24389632, "DCIM/100MSDCF/DSC02183.ARW");
        j.markDone(22222, 1024, 1024, "DCIM/100MSDCF/DSC02184.ARW");

        check("doneBytes(relPath,size) 记住已落盘字节",
                j.doneBytes("DCIM/100MSDCF/DSC02183.ARW", 24389632) == 7340032,
                "实际 " + j.doneBytes("DCIM/100MSDCF/DSC02183.ARW", 24389632));
        check("未知路径的 doneBytes 为 0",
                j.doneBytes("DCIM/100MSDCF/DSC99999.ARW", 1024) == 0,
                "实际 " + j.doneBytes("DCIM/100MSDCF/DSC99999.ARW", 1024));
        check("isComplete(relPath,size)：size<=0 一律不算完成",
                !j.isComplete("DCIM/100MSDCF/DSC02184.ARW", 0),
                "实际 " + j.isComplete("DCIM/100MSDCF/DSC02184.ARW", 0));
        check("isComplete(relPath,size)：空 relPath 一律不算完成",
                !j.isComplete("", 1024), "实际 " + j.isComplete("", 1024));
        check("isComplete(relPath,size)：未传满不算完成",
                !j.isComplete("DCIM/100MSDCF/DSC02183.ARW", 24389632),
                "实际 " + j.isComplete("DCIM/100MSDCF/DSC02183.ARW", 24389632));
        check("isComplete(relPath,size)：跨会话按路径+大小也认完成",
                j.isComplete("DCIM/100MSDCF/DSC02184.ARW", 1024),
                "实际 " + j.isComplete("DCIM/100MSDCF/DSC02184.ARW", 1024));
        check("isComplete(relPath,size)：同名但大小变了不算完成",
                !j.isComplete("DCIM/100MSDCF/DSC02184.ARW", 2048),
                "实际 " + j.isComplete("DCIM/100MSDCF/DSC02184.ARW", 2048));
        check("isComplete(relPath,size)：未知路径不算完成",
                !j.isComplete("DCIM/100MSDCF/DSC99999.ARW", 1024),
                "实际 " + j.isComplete("DCIM/100MSDCF/DSC99999.ARW", 1024));
        check("doneBytes(relPath,size)：只认同路径同大小的记录",
                j.doneBytes("DCIM/100MSDCF/DSC02183.ARW", 24389632) == 7340032
                        && j.doneBytes("DCIM/100MSDCF/DSC02183.ARW", 1) == 0,
                "实际 " + j.doneBytes("DCIM/100MSDCF/DSC02183.ARW", 24389632));
        check("partPath 按 relPath + size 稳定命名、跨会话不变（PC / #50）",
                j.partPath("/sdcard/out", "DCIM/100MSDCF/DSC02183.ARW", 24389632)
                        .replace('\\', '/')
                        .equals("/sdcard/out/DCIM_100MSDCF_DSC02183.ARW.24389632.part"),
                "实际 " + j.partPath("/sdcard/out", "DCIM/100MSDCF/DSC02183.ARW", 24389632));
        check("partPath 同名同大小两次生成同一个半成品名",
                j.partPath("/sdcard/out", "DCIM/100MSDCF/DSC02183.ARW", 24389632)
                        .equals(j.partPath("/sdcard/out", "DCIM/100MSDCF/DSC02183.ARW", 24389632)),
                "实际 " + j.partPath("/sdcard/out", "DCIM/100MSDCF/DSC02183.ARW", 24389632));
        check("partPath 同名不同大小 → 不同半成品名（B-1：旧前缀不能续进新对象）",
                !j.partPath("/sdcard/out", "DCIM/100MSDCF/DSC02183.ARW", 300)
                        .equals(j.partPath("/sdcard/out", "DCIM/100MSDCF/DSC02183.ARW", 301)),
                "实际 " + j.partPath("/sdcard/out", "DCIM/100MSDCF/DSC02183.ARW", 300)
                        + " vs " + j.partPath("/sdcard/out", "DCIM/100MSDCF/DSC02183.ARW", 301));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            j.save(bos);
        } catch (Exception e) {
            check("save() 不抛异常", false, e.toString());
            return;
        }
        String text = bos.toString(java.nio.charset.StandardCharsets.UTF_8);
        check("save() 写出表头", text.startsWith(Journal.HEADER), "实际首行 " + text.split("\n")[0]);

        Journal reloaded = new Journal();
        try {
            reloaded.load(new ByteArrayInputStream(bos.toByteArray()));
        } catch (Exception e) {
            check("load() 不抛异常", false, e.toString());
            return;
        }
        check("save → load 往返保留条数", reloaded.size() == 2, "实际 " + reloaded.size());
        check("save → load 往返保留已传字节与大小",
                reloaded.doneBytes("DCIM/100MSDCF/DSC02183.ARW", 24389632) == 7340032
                        && reloaded.isComplete("DCIM/100MSDCF/DSC02184.ARW", 1024),
                "done=" + reloaded.doneBytes("DCIM/100MSDCF/DSC02183.ARW", 24389632));
        check("save → load 后仍可按 relPath+size 跳过（跨会话）",
                reloaded.isComplete("DCIM/100MSDCF/DSC02184.ARW", 1024)
                        && !reloaded.isComplete("DCIM/100MSDCF/DSC02184.ARW", 2048),
                "实际 " + reloaded.isComplete("DCIM/100MSDCF/DSC02184.ARW", 1024));

        // B-1（PR #51 评审）：handle 只在单次会话内有效，重连后同一个 handle 可能指向另一个文件。
        // 记录若以 handle 为主键，下一次会话导入新文件时会冲掉上一会话旧路径的完成记录，
        // 再连回来就会把已导入的照片重传一遍。这里用真实的两会话流程钉死该行为。
        Journal session1 = new Journal();
        session1.markDone(7, 24389632, 24389632, "DCIM/100MSDCF/DSC02183.ARW");
        ByteArrayOutputStream session1Bytes = new ByteArrayOutputStream();
        try {
            session1.save(session1Bytes);
        } catch (Exception e) {
            check("Journal：会话 1 落盘不抛异常", false, e.toString());
            return;
        }
        Journal session2 = new Journal();
        try {
            session2.load(new ByteArrayInputStream(session1Bytes.toByteArray()));
        } catch (Exception e) {
            check("Journal：会话 2 读取不抛异常", false, e.toString());
            return;
        }
        // 会话 2 里 handle 7 指向 2184 并导入成功：这里只记新文件，不应影响 2183 的记录
        session2.markDone(7, 1024, 1024, "DCIM/100MSDCF/DSC02184.ARW");
        boolean oldStillComplete = session2.isComplete("DCIM/100MSDCF/DSC02183.ARW", 24389632);
        check("Journal：handle 复用不冲掉上一会话的完成记录",
                oldStillComplete
                        && session2.isComplete("DCIM/100MSDCF/DSC02184.ARW", 1024)
                        && session2.size() == 2,
                "2183=" + oldStillComplete
                        + " 2184=" + session2.isComplete("DCIM/100MSDCF/DSC02184.ARW", 1024)
                        + " size=" + session2.size());

        Journal junk = new Journal();
        try {
            junk.load(new ByteArrayInputStream(
                    "# garbage\n\nnot-a-row\nabc\tdef\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            check("load() 遇到坏行不该崩", false, e.toString());
            return;
        }
        check("load() 忽略坏行而不是抛异常", junk.size() == 0, "实际 " + junk.size());

        check("sanitize 把制表符与换行换成下划线",
                "_a_b_".equals(Journal.sanitize("\ta\nb\r")),
                "实际 " + Journal.sanitize("\ta\nb\r"));
    }

    // ---------------- ImportService（真实传输编排，PA / issue #50） ----------------

    /**
     * 记录日志出口（#54：冲突必须在用户看得见的地方，不能只写调试日志）。
     */
    static final class RecordingLog implements Log {
        final List<String> lines = new ArrayList<>();

        @Override
        public void line(String s) {
            lines.add(s);
        }

        boolean contains(String part) {
            for (String line : lines) {
                if (line.contains(part)) return true;
            }
            return false;
        }
    }

    /**
     * 假传输：只验证编排层（跳过、失败计数、进度、日志），不碰 android。
     * 真实 `MtpTransfer` 用 `MtpDevice.importFile`，只能在 ILCE-7C 真机上验。
     *
     * 六个场景共用这一份实现，按 {@link Mode} 分派（issue #212）：
     *
     * - {@link Mode#RECORD_PART}：只写 `.part`，不产生成品（默认构造）；
     * - {@link Mode#FLUKY}：每个文件首次失败、第二次成功（PD / issue #50）；
     * - {@link Mode#RENAME_TO_DEST}：落满后 rename 到 `outDir/<relPath>`，供索引/跳过用例；
     * - {@link Mode#GATED}：第一个文件卡到测试放行，用来钉住「暂停只落在文件边界」；
     * - {@link Mode#OFFSET_IGNORING}：复刻真机 `getPartialObject` 忽略 offset 的旧语义；
     * - {@link Mode#HANGING}：一直卡在 importFile 里，模拟相机休眠/USB 掉线。
     *
     * 「写 `.part` / 落成品」只保留 {@link #writeFakePart} / {@link #writeFakeFile} 一份。
     */
    static final class FakeTransfer implements ResumableTransfer {
        enum Mode { RECORD_PART, FLUKY, RENAME_TO_DEST, GATED, OFFSET_IGNORING, HANGING }

        final Mode mode;
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final List<Long> resumeFroms = Collections.synchronizedList(new ArrayList<>());
        boolean failAll;
        /** FLUKY：首次失败前落盘的字节数；0 表示不落盘。 */
        long firstFailureBytes;
        final CountDownLatch firstEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final CountDownLatch secondEntered = new CountDownLatch(1);
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        private final File outDir;
        private final byte[] payload;
        private final java.util.Set<String> seen = new java.util.HashSet<>();

        /** RECORD_PART：只写 `.part`，不产生成品。 */
        FakeTransfer() {
            this(Mode.RECORD_PART, null, 0);
        }

        /** RENAME_TO_DEST：落满后 rename 到 `outDir/<relPath>`。 */
        FakeTransfer(File outDir) {
            this(Mode.RENAME_TO_DEST, outDir, 0);
        }

        /** OFFSET_IGNORING：与真机旧语义一样，忽略 offset、从 0 给 `payloadSize` 字节。 */
        FakeTransfer(int payloadSize) {
            this(Mode.OFFSET_IGNORING, null, payloadSize);
        }

        FakeTransfer(Mode mode) {
            this(mode, null, 0);
        }

        private FakeTransfer(Mode mode, File outDir, int payloadSize) {
            this.mode = mode;
            this.outDir = outDir;
            if (payloadSize > 0) {
                payload = new byte[payloadSize];
                for (int i = 0; i < payloadSize; i++) payload[i] = (byte) (i % 251);
            } else {
                payload = null;
            }
        }

        @Override
        public Result transfer(ImportPlan.Item item, String partPath, long resumeFrom,
                               CancelToken token, LongConsumer progress) {
            calls.add(item.relPath);
            if (mode == Mode.HANGING) {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return writeFakePart(item, partPath);
            }
            resumeFroms.add(resumeFrom);
            if (mode == Mode.FLUKY) {
                // 同一个 relPath 首次失败，第二次成功（模拟一次偶发读失败）。
                if (!seen.add(item.relPath)) return writeFakePart(item, partPath);
                if (firstFailureBytes > 0) {
                    try {
                        File part = new File(partPath);
                        indexMkdirs(part.getParentFile());
                        try (FileOutputStream out = new FileOutputStream(part)) {
                            out.write(new byte[(int) firstFailureBytes]);
                        }
                    } catch (IOException e) {
                        return new Result(Kind.FAILED, e.toString());
                    }
                }
                return new Result(Kind.FAILED, "fake first failure");
            }
            if (mode == Mode.GATED) {
                int n = calls.size();
                if (n == 1) {
                    firstEntered.countDown();
                    try {
                        if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                            return new Result(Kind.FAILED, "测试未放行第一个文件");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return new Result(Kind.FAILED, "测试线程被中断");
                    }
                } else if (n == 2) {
                    secondEntered.countDown();
                }
                return writeFakeFile(item, partPath);
            }
            if (failAll) return new Result(Kind.FAILED, "fake failure");
            if (mode == Mode.OFFSET_IGNORING) return offsetIgnoring(item, partPath, resumeFrom, progress);
            if (mode == Mode.RENAME_TO_DEST) {
                try {
                    File part = new File(partPath);
                    indexMkdirs(part.getParentFile());
                    try (FileOutputStream out = new FileOutputStream(part)) {
                        out.write(new byte[(int) item.size]);
                    }
                    File dest = new File(outDir, item.relPath);
                    indexMkdirs(dest.getParentFile());
                    if (!part.renameTo(dest)) return new Result(Kind.FAILED, "rename failed");
                    return Result.of(Kind.OK);
                } catch (IOException e) {
                    return new Result(Kind.FAILED, e.toString());
                }
            }
            return writeFakePart(item, partPath);
        }

        /** 旧 reader 语义：`from == size` 直通、非 0 起点按 append 写，用于钉住「续传已停用」。 */
        private Result offsetIgnoring(ImportPlan.Item item, String partPath, long resumeFrom,
                                      LongConsumer progress) {
            try {
                File part = new File(partPath);
                indexMkdirs(part.getParentFile());
                File dest = new File(part.getParentFile(), item.relPath);
                if (resumeFrom == item.size) {
                    indexMkdirs(dest.getParentFile());
                    return part.renameTo(dest)
                            ? Result.of(Kind.OK)
                            : new Result(Kind.FAILED, "rename failed");
                }
                if (resumeFrom > 0) {
                    try (FileOutputStream out = new FileOutputStream(part, true)) {
                        long offset = resumeFrom;
                        while (offset < item.size) {
                            long n = Math.min(64L, item.size - offset);
                            out.write(payload, 0, (int) n);
                            offset += n;
                            progress.accept(offset);
                        }
                    }
                } else {
                    // 新契约：旧 `.part` 一律作废（删除），从 0 整段写。
                    if (part.exists() && !part.delete()) {
                        return new Result(Kind.FAILED, "旧 .part 删除失败");
                    }
                    try (FileOutputStream out = new FileOutputStream(part)) {
                        out.write(payload);
                    }
                    progress.accept(item.size);
                }
                indexMkdirs(dest.getParentFile());
                if (!part.renameTo(dest)) return new Result(Kind.FAILED, "rename failed");
                return Result.of(Kind.OK);
            } catch (IOException e) {
                return new Result(Kind.FAILED, e.toString());
            }
        }
    }

    /**
     * issue #70：按块上报**当前文件的绝对偏移**，并可选「首次失败 + 重试闸门」。
     *
     * - 每次调用都从 0 截断重写（续传已停用，#177：旧 `.part` 不得作为前缀）。
     * - `failFirst=true`：同一 relPath 的第一次调用在落 40 字节后返回 FAILED；
     *   第二次调用（重试）刚上报起点 0 就停住，等用例核对「重试不双计」后再放行。
     * - 闸门只挂在第 2 次调用（第一个文件的重试），不干扰后续文件。
     */
    static final class ResumeReportingTransfer implements ResumableTransfer {
        final List<String> calls = new ArrayList<>();
        final List<Long> resumeFroms = new ArrayList<>();
        final CountDownLatch retryEntered = new CountDownLatch(1);
        final CountDownLatch releaseRetry = new CountDownLatch(1);
        private final boolean failFirst;
        private final long chunk;
        private final Set<String> failed = new HashSet<>();

        ResumeReportingTransfer(boolean failFirst, long chunk) {
            this.failFirst = failFirst;
            this.chunk = chunk;
        }

        @Override
        public Result transfer(ImportPlan.Item item, String partPath, long resumeFrom,
                               CancelToken token, LongConsumer progress) {
            calls.add(item.relPath);
            resumeFroms.add(resumeFrom);
            int index = calls.size();
            try {
                File part = new File(partPath);
                indexMkdirs(part.getParentFile());
                long offset = resumeFrom;
                // 续传停用（#177）：无论磁盘上有没有残留，都从 0 截断重写。
                try (FileOutputStream out = new FileOutputStream(part, false)) {
                    progress.accept(offset);
                    if (failFirst && index == 2) {
                        retryEntered.countDown();
                        if (!releaseRetry.await(5, TimeUnit.SECONDS)) {
                            return new Result(Kind.FAILED, "重试闸门超时");
                        }
                    }
                    if (failFirst && failed.add(item.relPath)) {
                        long end = Math.min(item.size, offset + 40L);
                        out.write(new byte[(int) (end - offset)]);
                        offset = end;
                        progress.accept(offset);
                        return new Result(Kind.FAILED, "伪首次失败");
                    }
                    while (offset < item.size) {
                        long n = Math.min(chunk, item.size - offset);
                        out.write(new byte[(int) n]);
                        offset += n;
                        progress.accept(offset);
                    }
                }
                return Result.of(Kind.OK);
            } catch (IOException e) {
                return new Result(Kind.FAILED, e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(Kind.FAILED, "被中断");
            }
        }
    }

    /**
     * 第一次调用先请求取消、再返回 FAILED：钉住「取消优先于失败」的分支（PD / issue #50）。
     * 若编排层把这条计成失败，`failed` 会变成 1，用例即红。
     */
    static final class CancelOnFailTransfer implements ResumableTransfer {
        final List<String> calls = new ArrayList<>();
        Runnable onFirstCall;

        @Override
        public Result transfer(ImportPlan.Item item, String partPath, long resumeFrom,
                               CancelToken token, LongConsumer progress) {
            calls.add(item.relPath);
            Runnable r = onFirstCall;
            if (r != null) {
                onFirstCall = null;
                r.run();
            }
            return new Result(Kind.FAILED, "fake failure after cancel");
        }
    }

    static final class FinishRecorder implements ImportListener {
        final CountDownLatch done = new CountDownLatch(1);
        /** issue #70：每次 onProgress 时的 bytesDone() 快照，用来核对文件内进度与重试不双计。 */
        final List<Long> progressBytes = new ArrayList<>();
        /** issue #70：每个文件结束（onFileFinished）那一刻的 bytesDone()。 */
        final List<Long> bytesAtFileFinish = new ArrayList<>();
        boolean cancelled;
        int ok;
        int skipped;
        int failed;
        String summary;
        TransferProgress lastProgress;

        @Override
        public void onProgress(TransferProgress progress, ImportPlan.Item current) {
            lastProgress = progress;
            progressBytes.add(progress.bytesDone());
        }

        @Override
        public void onFileFinished(ImportPlan.Item item, ResumableTransfer.Kind kind, String note) {
            if (lastProgress != null) bytesAtFileFinish.add(lastProgress.bytesDone());
        }

        @Override
        public void onFinished(boolean cancelled, int ok, int skipped, int failed, String summary) {
            this.cancelled = cancelled;
            this.ok = ok;
            this.skipped = skipped;
            this.failed = failed;
            this.summary = summary;
            done.countDown();
        }
    }

    static void importServiceCases() throws Exception {
        Catalog c = sample();
        Selection s = new Selection(c);
        // DCIM 下 2 个照片单元：DSC02183（All 档取 JPG 100）、DSC02999（回退 ARW 50）。
        s.setChecked(1, true);
        ImportPlan plan = ImportPlan.of(c, s, PhotoWall.of(c).units(),
                PhotoWall.Export.JPG_FIRST);

        File out = Files.createTempDirectory("wiredraw-import").toFile();
        // 续传停用（#177）：只有真正产出成品的假传输才能让 Journal 记账可核对。
        FakeTransfer t1 = new FakeTransfer(out);
        FinishRecorder r1 = new FinishRecorder();
        List<String> r1Log = new ArrayList<>();
        new ImportService(r1Log::add, t1, out).start(plan, r1);
        check("ImportService：一轮在 5 秒内结束", r1.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("ImportService：2 个单元文件全部 OK（不再有「未实现」）",
                r1.ok == 2 && r1.skipped == 0 && r1.failed == 0,
                "ok=" + r1.ok + " skipped=" + r1.skipped + " failed=" + r1.failed);
        check("ImportService：summary 不再出现「未实现」",
                r1.summary != null && !r1.summary.contains("未实现"), "实际 " + r1.summary);
        check("ImportService：进度按成功文件累加字节",
                r1.lastProgress != null && r1.lastProgress.bytesDone() == 150
                        && r1.lastProgress.filesDone() == 2,
                r1.lastProgress == null ? "无进度"
                        : r1.lastProgress.bytesDone() + " / " + r1.lastProgress.filesDone());
        Journal j = new Journal();
        check("ImportService：成品落在 outDir 的镜像 relPath 处（PC / #50）",
                new File(out, "DCIM/100MSDCF/DSC02183.JPG").isFile()
                        && new File(out, "DCIM/DSC02999.ARW").isFile(),
                "实际 " + Arrays.toString(out.list()));
        check("ImportService：成功一轮不留 .part（续传停用，#177）",
                !new File(j.partPath(out.getAbsolutePath(), "DCIM/100MSDCF/DSC02183.JPG", 100)).exists()
                        && !new File(j.partPath(out.getAbsolutePath(), "DCIM/DSC02999.ARW", 50)).exists(),
                "实际 " + Arrays.toString(out.list()));
        check("ImportService：日志文件写出 import-journal.txt",
                new File(out, "import-journal.txt").isFile(),
                "实际 " + Arrays.toString(out.list()));

        // #20 裁决 B 的轮级断言：源码守卫只能防「名字被删」，这里跑真一轮确认不产索引文件。
        check("ImportService：一轮结束不产 imported-index.txt 与 .tmp（#20 停写）",
                !new File(out, "imported-index.txt").exists()
                        && !new File(out, "imported-index.txt.tmp").exists(),
                "实际 " + Arrays.toString(out.list()));
        check("ImportService：一轮日志不含「已导入索引已重建」（#20 停写）",
                r1Log.stream().noneMatch(line -> line.contains("已导入索引已重建")),
                "实际日志：\n" + String.join("\n", r1Log));

        // 第二轮：同一目录 + 同一 plan，成品与账本都在，应该全部跳过，不再调 transfer
        FakeTransfer t2 = new FakeTransfer();
        FinishRecorder r2 = new FinishRecorder();
        new ImportService(Log.NOOP, t2, out).start(plan, r2);
        check("ImportService：第二轮 5 秒内结束", r2.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("ImportService：第二轮全部 SKIPPED 且不再调 transfer",
                r2.ok == 0 && r2.skipped == 2 && r2.failed == 0 && t2.calls.isEmpty(),
                "ok=" + r2.ok + " skipped=" + r2.skipped + " calls=" + t2.calls.size());

        // 全失败：新目录，不能把失败冒充成完成
        File badOut = Files.createTempDirectory("wiredraw-import-bad").toFile();
        FakeTransfer t3 = new FakeTransfer();
        t3.failAll = true;
        FinishRecorder r3 = new FinishRecorder();
        new ImportService(Log.NOOP, t3, badOut).start(plan, r3);
        check("ImportService：全失败一轮结束", r3.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("ImportService：全失败时 ok=0 / failed=2，不冒充完成",
                r3.ok == 0 && r3.failed == 2 && r3.skipped == 0,
                "ok=" + r3.ok + " failed=" + r3.failed + " skipped=" + r3.skipped);
        // PD / issue #50：重试必须有上限。全失败时每个文件恰好尝试 2 次（硬编码，
        // 不引用 MAX_FILE_ATTEMPTS，避免「断言跟着实现走」的自指）。
        check("PD 重试：全失败时每个文件恰好尝试 2 次（有上限）",
                t3.calls.size() == plan.count() * 2,
                "calls=" + t3.calls.size() + " 期望=" + (plan.count() * 2));

        // PD：首次失败、第二次成功的文件仍算完成，且尝试次数有上限。
        File flakyOut = Files.createTempDirectory("wiredraw-import-flaky").toFile();
        FakeTransfer t4 = new FakeTransfer(FakeTransfer.Mode.FLUKY);
        FinishRecorder r4 = new FinishRecorder();
        new ImportService(Log.NOOP, t4, flakyOut).start(plan, r4);
        check("PD 重试：偶发失败一轮在 5 秒内结束", r4.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("PD 重试：首次失败、第二次成功仍算完成",
                r4.ok == 2 && r4.failed == 0 && r4.skipped == 0,
                "ok=" + r4.ok + " failed=" + r4.failed);
        check("PD 重试：每个文件恰好尝试 2 次（首次 + 1 次同步重试）",
                t4.calls.size() == plan.count() * 2,
                "calls=" + t4.calls.size());

        // #177：续传停用。首次失败即使已落 40 字节，第二次 attempt 也必须从 0 重传，
        // 否则「忽略 offset」的 reader 会把 0 前缀追加到残留后面，尺寸对、内容错。
        File partialOut = Files.createTempDirectory("wiredraw-import-partial").toFile();
        FakeTransfer t5 = new FakeTransfer(FakeTransfer.Mode.FLUKY);
        t5.firstFailureBytes = 40L;
        FinishRecorder r5 = new FinishRecorder();
        new ImportService(Log.NOOP, t5, partialOut).start(plan, r5);
        check("PD 重试：落部分字节的一轮在 5 秒内结束", r5.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("PD 重试：落部分字节后第二次仍算完成",
                r5.ok == 2 && r5.failed == 0 && r5.skipped == 0,
                "ok=" + r5.ok + " failed=" + r5.failed + " skipped=" + r5.skipped);
        boolean resumesOk = t5.resumeFroms.size() == plan.count() * 2;
        for (int i = 0; resumesOk && i < t5.resumeFroms.size(); i++) {
            resumesOk = t5.resumeFroms.get(i) == 0L;
        }
        check("PD 重试：每次 attempt 的 resumeFrom 都是 0（续传停用，#177）",
                resumesOk, "实际 " + t5.resumeFroms);

        // PD / B-2：首次 FAILED 同时已请求取消 → 归为已取消，不计失败、也不再重试。
        File cancelOut = Files.createTempDirectory("wiredraw-import-cancel-fail").toFile();
        CancelOnFailTransfer t6 = new CancelOnFailTransfer();
        ImportService svc6 = new ImportService(Log.NOOP, t6, cancelOut);
        t6.onFirstCall = svc6::cancel;
        FinishRecorder r6 = new FinishRecorder();
        svc6.start(plan, r6);
        check("PD 重试：取消与失败竞态一轮在 5 秒内结束",
                r6.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("PD 重试：FAILED 同时已请求取消归为 CANCELLED，failed 不增",
                r6.cancelled && r6.failed == 0 && r6.ok == 0,
                "cancelled=" + r6.cancelled + " failed=" + r6.failed + " ok=" + r6.ok);
        check("PD 重试：取消优先，不再发起同步重试",
                t6.calls.size() == 1, "calls=" + t6.calls.size());

        deleteTree(out);
        deleteTree(badOut);
        deleteTree(flakyOut);
        deleteTree(partialOut);
        deleteTree(cancelOut);
    }

    // ---------------- 一轮导入的落盘文件（#219 / A2；索引已按 #20 裁决 B 停写） ----------------

    static void indexMkdirs(File dir) throws IOException {
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new IOException("mkdir failed: " + dir);
        }
    }

    /** 造一个指定字节数的文件；0 字节用来验证「不做成品」。 */
    static File writeSizedFile(File f, int size) throws IOException {
        indexMkdirs(f.getParentFile());
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[size]);
        }
        return f;
    }

    static String readSourceOrNull(File f) {
        try {
            return Files.readString(f.toPath());
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * #20 裁决 B：`imported-index.txt` 有写方、无读方，**先停写**。
     *
     * 停写的完成标准是「没有读方就不写」：轮末写入调用点、纯 JDK 类本身与
     * `pureJdkClasses` 清单项必须一起消失。将来要恢复索引，读方与写方必须
     * 同一个 PR 交付，并由界面真正用上。
     */
    static void indexStoppedCases() throws Exception {
        File src = new File(System.getProperty("user.dir"), "src/com/rea/mtpimport");
        String round = readSourceOrNull(new File(src, "RoundFiles.java"));
        String service = readSourceOrNull(new File(src, "ImportService.java"));
        String gradle = readSourceOrNull(new File(System.getProperty("user.dir"), "build.gradle.kts"));
        check("已导入索引：写入方 rebuildIndex 已移除（#20 裁决 B）",
                round != null && !round.contains("rebuildIndex"),
                round == null ? "读不到 RoundFiles.java" : "仍有 rebuildIndex");
        check("已导入索引：ImportService 不再调写入方（#20 裁决 B）",
                service != null && !service.contains("rebuildIndex"),
                service == null ? "读不到 ImportService.java" : "仍调用 rebuildIndex");
        check("已导入索引：纯 JDK 类已删除（#20 裁决 B）",
                !new File(src, "ImportedIndex.java").exists(),
                "ImportedIndex.java 仍在");
        check("已导入索引：纯 JDK 清单不再列它（#20 裁决 B）",
                gradle != null && !gradle.contains("ImportedIndex.java"),
                gradle == null ? "读不到 build.gradle.kts" : "清单仍列 ImportedIndex.java");
    }

    // ---------------- 取消令牌与暂停/取消/恢复语义（PB / issue #50） ----------------

    static void cancelTokenCases() {
        CancelToken token = new CancelToken();
        check("CancelToken：初始未取消", !token.isCancelled(), "实际 true");
        token.cancel();
        check("CancelToken：cancel() 后置位", token.isCancelled(), "实际 false");

        CancelToken late = new CancelToken();
        late.cancel();
        AtomicInteger lateRuns = new AtomicInteger();
        late.setOnCancel(lateRuns::incrementAndGet);
        check("CancelToken：取消后注册的动作立即执行",
                lateRuns.get() == 1, "实际 " + lateRuns.get());

        CancelToken once = new CancelToken();
        AtomicInteger runs = new AtomicInteger();
        once.setOnCancel(runs::incrementAndGet);
        once.cancel();
        once.cancel();
        check("CancelToken：取消动作只执行一次",
                runs.get() == 1, "实际 " + runs.get());

        CancelToken cleared = new CancelToken();
        AtomicInteger clearedRuns = new AtomicInteger();
        cleared.setOnCancel(clearedRuns::incrementAndGet);
        cleared.clearOnCancel();
        cleared.cancel();
        check("CancelToken：clearOnCancel 后不再执行旧动作",
                clearedRuns.get() == 0, "实际 " + clearedRuns.get());
    }

    /**
     * 模拟「取消真的打断了当前 importFile」：传输实现等到取消令牌的动作触发（真实路径里
     * 那是关闭 ParcelFileDescriptor），然后返回 CANCELLED。
     */
    static final class CancellingTransfer implements ResumableTransfer {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch tokenCancelled = new CountDownLatch(1);

        @Override
        public Result transfer(ImportPlan.Item item, String partPath, long resumeFrom,
                               CancelToken token, LongConsumer progress) {
            calls.add(item.relPath);
            token.setOnCancel(tokenCancelled::countDown);
            entered.countDown();
            try {
                if (!tokenCancelled.await(5, TimeUnit.SECONDS)) {
                    token.clearOnCancel();
                    return new Result(Kind.FAILED, "取消动作未触发，传输无法被中断");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                token.clearOnCancel();
                return new Result(Kind.FAILED, "测试线程被中断");
            }
            token.clearOnCancel();
            return new Result(Kind.CANCELLED, "已取消，本次半成品已删除");
        }
    }

    // ---------------- 多存储同名冲突（#54） ----------------

    /** 两个存储卷、同一 relPath、同一尺寸：最容易静默丢数据的场景。 */
    static Catalog conflictCatalog() {
        Catalog c = new Catalog();
        c.add(new Catalog.Entry(1, 0, Catalog.FMT_FOLDER, 0, "DCIM", 1));
        c.add(new Catalog.Entry(2, 1, Catalog.FMT_FOLDER, 0, "100MSDCF", 1));
        c.add(new Catalog.Entry(3, 2, Catalog.FMT_ARW, 300, "DSC02183.ARW", 1));
        c.add(new Catalog.Entry(11, 0, Catalog.FMT_FOLDER, 0, "DCIM", 2));
        c.add(new Catalog.Entry(12, 11, Catalog.FMT_FOLDER, 0, "100MSDCF", 2));
        c.add(new Catalog.Entry(13, 12, Catalog.FMT_ARW, 300, "DSC02183.ARW", 2));
        return c.build();
    }

    static void storageConflictCases() throws Exception {
        Catalog c = conflictCatalog();
        List<Catalog.Entry> conflicts = c.storageConflicts(c.all());
        check("多存储同名：storageConflicts 暴露两个同 relPath 同尺寸文件（#54）",
                conflicts.size() == 2
                        && "DCIM/100MSDCF/DSC02183.ARW".equals(conflicts.get(0).relPath)
                        && conflicts.get(1).relPath.equals(conflicts.get(0).relPath)
                        && conflicts.get(0).size == conflicts.get(1).size,
                "conflicts=" + conflicts);

        Selection s = new Selection(c);
        ImportPlan p = ImportPlan.of(c, s, PhotoWall.of(c).units(), PhotoWall.Export.RAW);
        boolean marked = p.count() == 2;
        for (ImportPlan.Item it : p.items()) marked &= it.conflict;
        check("多存储同名：清单把两项都标成冲突（#54）", marked,
                "count=" + p.count() + " items=" + p.items());

        // 冲突只应在「本次实际选中」的集合内判定：两张卡同时插着、只勾一张时没有争用。
        Selection one = new Selection(c);
        one.setChecked(13, true);   // 只勾第二个存储卷的那个文件
        ImportPlan single = ImportPlan.of(c, one, PhotoWall.of(c).units(),
                PhotoWall.Export.RAW);
        check("多存储同名：只勾一个时不算冲突（#54）",
                single.count() == 1 && !single.items().get(0).conflict
                        && single.items().get(0).storageId == 2,
                "count=" + single.count() + " items=" + single.items());

        File singleOut = Files.createTempDirectory("wiredraw-single").toFile();
        FakeTransfer st = new FakeTransfer();
        FinishRecorder sr = new FinishRecorder();
        new ImportService(new RecordingLog(), st, singleOut).start(single, sr);
        check("多存储同名：只勾一个能正常导入（#54）",
                sr.done.await(5, TimeUnit.SECONDS) && sr.ok == 1 && sr.failed == 0
                        && sr.skipped == 0 && st.calls.size() == 1,
                "ok=" + sr.ok + " failed=" + sr.failed + " skipped=" + sr.skipped
                        + " calls=" + st.calls.size());

        File out = Files.createTempDirectory("wiredraw-conflict").toFile();
        FakeTransfer t = new FakeTransfer();
        RecordingLog logs = new RecordingLog();
        FinishRecorder r = new FinishRecorder();
        new ImportService(logs, t, out).start(p, r);
        check("多存储同名：一轮在 5 秒内结束", r.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("多存储同名：两项都失败、不调 transfer、不冒充跳过",
                r.ok == 0 && r.failed == 2 && r.skipped == 0 && t.calls.isEmpty(),
                "ok=" + r.ok + " failed=" + r.failed + " skipped=" + r.skipped
                        + " calls=" + t.calls.size());
        check("多存储同名：失败进入 summary（不是一切顺利）",
                r.summary != null && r.summary.contains("失败 2"), "实际 " + r.summary);
        check("多存储同名：日志有醒目出口（不只调试日志）", logs.contains("拒绝覆盖"),
                "日志=" + logs.lines);

        File jf = new File(out, "import-journal.txt");
        Journal jr = new Journal();
        try (ByteArrayInputStream in = new ByteArrayInputStream(Files.readAllBytes(jf.toPath()))) {
            jr.load(in);
        }
        check("多存储同名：冲突项不写 Journal（不产生假完成）", jr.size() == 0,
                "journal 条数=" + jr.size());

        // DestPolicy：落盘冲突策略本身（MtpTransfer.finish 是唯一调用方，真机验）。
        File base = Files.createTempDirectory("wiredraw-dest").toFile();
        check("DestPolicy：正常 relPath 落到 outDir 下",
                DestPolicy.resolve(base, "DCIM/x.jpg").getCanonicalFile()
                        .equals(new File(base, "DCIM/x.jpg").getCanonicalFile()),
                "实际 " + DestPolicy.resolve(base, "DCIM/x.jpg"));
        check("DestPolicy：`..` 越界返回 null", DestPolicy.resolve(base, "../evil") == null,
                "实际 " + DestPolicy.resolve(base, "../evil"));
        File same = writeSizedFile(new File(base, "same.jpg"), 300);
        File diff = writeSizedFile(new File(base, "diff.jpg"), 100);
        check("DestPolicy：目标不存在 → NONE",
                DestPolicy.existing(new File(base, "missing.jpg"), 300) == DestPolicy.Existing.NONE,
                "实际 " + DestPolicy.existing(new File(base, "missing.jpg"), 300));
        check("DestPolicy：同尺寸视为同一文件，不覆盖（#20 口径）",
                DestPolicy.existing(same, 300) == DestPolicy.Existing.SAME_SIZE,
                "实际 " + DestPolicy.existing(same, 300));
        check("DestPolicy：异尺寸拒绝覆盖（不静默丢数据）",
                DestPolicy.existing(diff, 300) == DestPolicy.Existing.DIFFERENT_SIZE,
                "实际 " + DestPolicy.existing(diff, 300));
    }

    /**
     * 落点策略（issue #28）：默认私有根、相册请求显式回退、镜像 relPath 布局、存量兼容。
     * 这些都不需要真机；相册实际写入与权限行为仍是真机待验项。
     */
    static void destinationPolicyCases() throws Exception {
        File filesDir = Files.createTempDirectory("wiredraw-destpolicy").toFile();
        // 用字面量而不是 DestPolicy.PRIVATE_DIR：常量被改名时这条断言必须能红。
        File privateRoot = new File(filesDir, "imported");

        DestPolicy.Destination def = DestPolicy.choose(filesDir, DestPolicy.Kind.PRIVATE);
        check("落点：默认私有根 = getFilesDir()/imported",
                def.kind == DestPolicy.Kind.PRIVATE
                        && def.root.getCanonicalFile().equals(privateRoot.getCanonicalFile())
                        && !def.fellBack() && def.fallbackReason.isEmpty(),
                "kind=" + def.kind + " root=" + def.root + " reason=[" + def.fallbackReason + "]");

        DestPolicy.Destination none = DestPolicy.choose(filesDir, null);
        check("落点：未提供请求按默认私有",
                none.kind == DestPolicy.Kind.PRIVATE
                        && none.root.getCanonicalFile().equals(privateRoot.getCanonicalFile())
                        && !none.fellBack(),
                "kind=" + none.kind + " root=" + none.root + " reason=[" + none.fallbackReason + "]");

        DestPolicy.Destination gallery = DestPolicy.choose(filesDir, DestPolicy.Kind.GALLERY);
        check("落点：相册请求回退私有（不能静默落到未验位置）",
                gallery.kind == DestPolicy.Kind.PRIVATE
                        && gallery.root.getCanonicalFile().equals(privateRoot.getCanonicalFile()),
                "kind=" + gallery.kind + " root=" + gallery.root);
        check("落点：回退必须带非空原因（日志/界面要能看出来）",
                gallery.fellBack() && gallery.fallbackReason.contains("回退"),
                "reason=[" + gallery.fallbackReason + "]");
        check("落点：回退根与默认根相同（存量文件不会被当冲突）",
                gallery.root.getCanonicalFile().equals(def.root.getCanonicalFile()),
                "fallback=" + gallery.root + " default=" + def.root);

        File mirrorDate = DestPolicy.resolve(def.root, "2025-06-07/C0001.MP4");
        check("落点：镜像相机日期目录，不额外重排",
                mirrorDate != null && mirrorDate.getCanonicalFile()
                        .equals(new File(def.root, "2025-06-07/C0001.MP4").getCanonicalFile()),
                "实际 " + mirrorDate);
        File mirrorDcim = DestPolicy.resolve(def.root, "DCIM/100MSDCF/DSC09782.ARW");
        check("落点：镜像 DCIM 目录，不加机型/日期层",
                mirrorDcim != null && mirrorDcim.getCanonicalFile()
                        .equals(new File(def.root, "DCIM/100MSDCF/DSC09782.ARW").getCanonicalFile()),
                "实际 " + mirrorDcim);
    }

    /**
     * 模拟「取消动作打不断传输」：完全忽略取消令牌，直到测试放行才返回。
     * 用来钉住二审 B-1 的死路判定：worker 不退出时界面必须能判出「卡住」并给 ERROR 出路。
     */
    static final class StuckTransfer implements ResumableTransfer {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public Result transfer(ImportPlan.Item item, String partPath, long resumeFrom,
                               CancelToken token, LongConsumer progress) {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Result(Kind.FAILED, "测试放行");
        }
    }

    /**
     * 落满尺寸的 `.part` 再 rename 到 outDir/<relPath>，模拟真实传输的收尾。
     * 续传停用（#177）后，Journal 的跳过必须能在磁盘上看到成品，暂停/取消用例要用这条。
     */
    static ResumableTransfer.Result writeFakeFile(ImportPlan.Item item, String partPath) {
        try {
            File part = new File(partPath);
            indexMkdirs(part.getParentFile());
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(new byte[(int) item.size]);
            }
            File dest = new File(part.getParentFile(), item.relPath);
            indexMkdirs(dest.getParentFile());
            if (!part.renameTo(dest)) {
                return new ResumableTransfer.Result(Kind.FAILED, "rename failed");
            }
            return ResumableTransfer.Result.of(Kind.OK);
        } catch (IOException e) {
            return new ResumableTransfer.Result(Kind.FAILED, e.toString());
        }
    }

    /** 只落一个假 `.part`（不产生成品），供 FakeTransfer 的 FLUKY / HANGING 模式复用。 */
    static ResumableTransfer.Result writeFakePart(ImportPlan.Item item, String partPath) {
        try {
            File part = new File(partPath);
            File parent = part.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return new ResumableTransfer.Result(Kind.FAILED, "mkdir failed");
            }
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(new byte[(int) Math.min(item.size, 16)]);
            }
            return ResumableTransfer.Result.of(Kind.OK);
        } catch (IOException e) {
            return new ResumableTransfer.Result(Kind.FAILED, e.toString());
        }
    }

    static void pauseCancelCases() throws Exception {
        Catalog c = sample();
        Selection s = new Selection(c);
        s.setChecked(3, true);      // DSC02183 单元（All 档取 JPG 100，先）
        s.setChecked(6, true);      // DSC02999 单元（回退 ARW 50，后）
        ImportPlan plan = ImportPlan.of(c, s, PhotoWall.of(c).units(),
                PhotoWall.Export.JPG_FIRST);
        check("PB 用例清单恰为 2 个单元文件", plan.count() == 2, "实际 " + plan.count());

        // 1) 暂停落在文件边界：在传的完成，下一个等 resume
        File out = Files.createTempDirectory("wiredraw-pb-pause").toFile();
        FakeTransfer g = new FakeTransfer(FakeTransfer.Mode.GATED);
        FinishRecorder r1 = new FinishRecorder();
        ImportService svc = new ImportService(Log.NOOP, g, out);
        svc.start(plan, r1);
        check("PB 暂停：第一个文件已进入传输",
                g.firstEntered.await(5, TimeUnit.SECONDS), "等待超时");
        svc.pause();
        check("PB 暂停：service.isPaused() 为真", svc.isPaused(), "实际 false");
        g.releaseFirst.countDown();
        check("PB 暂停：当前文件传完后停在边界，第二个文件不启动",
                !g.secondEntered.await(400, TimeUnit.MILLISECONDS),
                "第二个文件在暂停期间启动了");
        svc.resume();
        check("PB 恢复：resume 后第二个文件开始",
                g.secondEntered.await(5, TimeUnit.SECONDS), "等待超时");
        check("PB 恢复：本轮 5 秒内结束", r1.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("PB 恢复：两个文件都完成，无跳过/失败",
                r1.ok == 2 && r1.skipped == 0 && r1.failed == 0,
                "ok=" + r1.ok + " skipped=" + r1.skipped + " failed=" + r1.failed);
        check("PB 恢复：两次调用的 resumeFrom 都是 0（不做假续传）",
                g.resumeFroms.size() == 2 && g.resumeFroms.get(0) == 0 && g.resumeFroms.get(1) == 0,
                "实际 " + g.resumeFroms);

        // 暂停期间完成的那个文件已记账：同目录重跑应全部跳过
        FakeTransfer t2 = new FakeTransfer();
        FinishRecorder r2 = new FinishRecorder();
        new ImportService(Log.NOOP, t2, out).start(plan, r2);
        check("PB 暂停：完成的文件下次按 Skipped 跳过",
                r2.done.await(5, TimeUnit.SECONDS) && r2.skipped == 2 && t2.calls.isEmpty(),
                "skipped=" + r2.skipped + " calls=" + t2.calls.size());
        deleteTree(out);

        // 2) 取消后模拟传输在当前文件上返回，且不把该文件记成完成
        File out2 = Files.createTempDirectory("wiredraw-pb-cancel").toFile();
        CancellingTransfer ct = new CancellingTransfer();
        FinishRecorder r3 = new FinishRecorder();
        // #65：取消日志必须能核对，不能再用 Log.NOOP 把文案放过去。
        List<String> cancelLogs = Collections.synchronizedList(new ArrayList<>());
        ImportService svc2 = new ImportService(cancelLogs::add, ct, out2);
        svc2.start(plan, r3);
        check("PB 取消：第一个文件已进入传输",
                ct.entered.await(5, TimeUnit.SECONDS), "等待超时");
        svc2.cancel();
        check("PB 取消：取消动作在传输中被触发（不是只停界面）",
                ct.tokenCancelled.await(5, TimeUnit.SECONDS), "取消动作未触发");
        check("PB 取消：本轮 5 秒内结束", r3.done.await(5, TimeUnit.SECONDS), "等待超时");
        // #65：关闭 fd 只是「尝试」中断原生调用；本机型实测约 0.6 s 返回，能否打断没有
        // 跨机型保证（见 ImportService 类注释与 README）。日志串不得写成已经打断。
        // 负向断言用 noneMatch 扫全部行，不能只对 anyMatch 命中的那一行生效。
        check("PB 取消：日志不把「打断」写成保证（#65）",
                cancelLogs.stream().noneMatch(l -> l.contains("会被中断"))
                        && cancelLogs.stream().anyMatch(l -> l.contains("已请求取消")
                                && l.contains("无跨机型保证")),
                "实际 " + cancelLogs);
        check("PB 取消：cancelled=true 且取消的文件不计失败",
                r3.cancelled && r3.ok == 0 && r3.skipped == 0 && r3.failed == 0,
                "cancelled=" + r3.cancelled + " ok=" + r3.ok + " failed=" + r3.failed);
        check("PB 取消：只尝试了当前文件，后续文件未启动",
                ct.calls.size() == 1, "实际调用 " + ct.calls.size() + " 个文件");
        check("PB 取消：摘要标明已取消",
                r3.summary != null && r3.summary.contains("已取消"), "实际 " + r3.summary);

        FakeTransfer t4 = new FakeTransfer();
        FinishRecorder r4 = new FinishRecorder();
        new ImportService(Log.NOOP, t4, out2).start(plan, r4);
        check("PB 取消：被取消的文件下次重传（不能留下假完成记录）",
                r4.done.await(5, TimeUnit.SECONDS) && r4.ok == 2 && t4.calls.size() == 2,
                "ok=" + r4.ok + " calls=" + t4.calls.size());
        check("PB 取消：重跑时 resumeFrom 仍为 0（不假装续传）",
                t4.resumeFroms.size() == 2 && t4.resumeFroms.get(0) == 0 && t4.resumeFroms.get(1) == 0,
                "实际 " + t4.resumeFroms);
        deleteTree(out2);

        // 3) 「取消卡住」纯判定（二审 B-1）：本机型实测约 0.6 s 返回，打断的跨机型保证未验，
        //    打不断时 worker 永活，界面必须能判出卡住并给 ERROR 出路，不能静默变死键。
        long grace = ImportService.CANCEL_STUCK_GRACE_NANOS;
        check("PB 卡死判定：未请求取消 → 不判定卡死",
                !ImportService.cancelStuck(false, true, 100L, 100L + grace, grace), "实际 true");
        check("PB 卡死判定：已请求取消但 worker 已退出 → 不判定卡死",
                !ImportService.cancelStuck(true, false, 100L, 100L + grace, grace), "实际 true");
        check("PB 卡死判定：宽限期内 → 不判定卡死（正常取消窗口不得误报）",
                !ImportService.cancelStuck(true, true, 100L, 100L + grace - 1, grace), "实际 true");
        check("PB 卡死判定：恰好到宽限期 → 判定卡死",
                ImportService.cancelStuck(true, true, 100L, 100L + grace, grace), "实际 false");
        check("PB 卡死判定：没有记录取消时刻 → 不判定卡死",
                !ImportService.cancelStuck(true, true, 0L, 100L + grace, grace), "实际 true");

        // 4) 真实线程：取消动作打不断传输时 worker 不退出，必须能判定卡住
        File out3 = Files.createTempDirectory("wiredraw-pb-stuck").toFile();
        StuckTransfer st = new StuckTransfer();
        FinishRecorder r5 = new FinishRecorder();
        ImportService svc3 = new ImportService(Log.NOOP, st, out3);
        svc3.start(plan, r5);
        check("PB 卡死：worker 已进入传输", st.entered.await(5, TimeUnit.SECONDS), "等待超时");
        svc3.cancel();
        long afterCancel = System.nanoTime();
        check("PB 卡死：取消后 worker 仍未退出（模拟 pfd.close 打不断原生调用）",
                svc3.isRunning(), "worker 已退出");
        // PD / issue #50：onDestroy 关闭会话前先有界 join；worker 卡住时必须如实返回 false。
        check("PD onDestroy：worker 卡住时 awaitWorker 超时返回 false",
                !svc3.awaitWorker(50), "实际返回 true");
        check("PB 卡死：宽限期内不判定卡死",
                !svc3.isCancelStuckAt(afterCancel), "实际 true");
        check("PB 卡死：超过宽限期判定为卡死（界面必须能给 ERROR 出路）",
                svc3.isCancelStuckAt(afterCancel + grace), "实际 false");
        // 三审 B-3：Main 判定卡死后会放弃该轮；旧轮迟到的 finally 不得覆写 journal。
        svc3.abandon();
        st.release.countDown();
        check("PB 卡死：放行后本轮 5 秒内结束", r5.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("PB 放弃：被放弃的轮次跳过导入日志保存（不覆盖磁盘上的现有记录）",
                !new File(out3, "import-journal.txt").exists(), "日志仍被写出");
        check("PB 卡死：worker 退出后不再判定卡死", !svc3.isCancelStuck(), "实际 true");
        check("PD onDestroy：worker 退出后 awaitWorker 返回 true",
                svc3.awaitWorker(5000), "实际返回 false");
        deleteTree(out3);

        // 5) #65：PAUSED 稳态取消时没有在传的文件，取消动作槽也已空（上一个文件返回时
        //    走 clearOnCancel），日志必须用条件句，不能声称「正在传的文件」在被打断。
        File out4 = Files.createTempDirectory("wiredraw-pb-cancel-paused").toFile();
        FakeTransfer g2 = new FakeTransfer(FakeTransfer.Mode.GATED);
        FinishRecorder r6 = new FinishRecorder();
        List<String> pausedCancelLogs = Collections.synchronizedList(new ArrayList<>());
        ImportService svc4 = new ImportService(pausedCancelLogs::add, g2, out4);
        svc4.start(plan, r6);
        check("PAUSED 取消：第一个文件已进入传输",
                g2.firstEntered.await(5, TimeUnit.SECONDS), "等待超时");
        svc4.pause();
        g2.releaseFirst.countDown();
        check("PAUSED 取消：暂停期间第二个文件不启动",
                !g2.secondEntered.await(400, TimeUnit.MILLISECONDS), "第二个文件启动了");
        check("PAUSED 取消：worker 停在暂停边界（活着但没有文件在传）",
                svc4.isPaused() && svc4.isRunning(),
                "paused=" + svc4.isPaused() + " running=" + svc4.isRunning());
        svc4.cancel();
        check("PAUSED 取消：本轮 5 秒内结束", r6.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("PAUSED 取消：日志不声称正在传文件（#65）",
                pausedCancelLogs.stream().noneMatch(l -> l.contains("会被中断"))
                        && pausedCancelLogs.stream().anyMatch(l -> l.contains("已请求取消")
                                && l.contains("若正在传文件") && l.contains("无跨机型保证")),
                "实际 " + pausedCancelLogs);
        deleteTree(out4);
    }

    // ---------------- 恢复前比较暂停轮次的清单（issue #57） ----------------

    /**
     * 暂停轮次只认自己那份清单：同一批文件可恢复，勾选 / 档位 / filter 变了必须按新计划重开。
     * 这里直接验证 {@link ImportService#canResumeWith(ImportPlan)} 的判定，不模拟 Main 的界面分支。
     */
    static void resumePlanCases() throws Exception {
        Catalog c = sample();
        PhotoWall wall = PhotoWall.of(c);
        Selection sa = new Selection(c);
        sa.setChecked(3, true);   // DSC02183 单元，JPG 优先 → 1 个文件
        ImportPlan planA = ImportPlan.of(c, sa, wall.units(), PhotoWall.Export.JPG_FIRST);
        Selection sb = new Selection(c);
        sb.setChecked(6, true);   // DSC02999 仅 ARW → 1 个文件，与 planA 不同
        ImportPlan planB = ImportPlan.of(c, sb, wall.units(), PhotoWall.Export.JPG_FIRST);
        check("issue #57 用例：两份清单条数相同但文件不同",
                planA.count() == planB.count() && !ImportPlan.sameFileSet(planA, planB),
                "A=" + planA.count() + " B=" + planB.count());

        File out = Files.createTempDirectory("wiredraw-57-resume").toFile();
        FakeTransfer g = new FakeTransfer(FakeTransfer.Mode.GATED);
        FinishRecorder r = new FinishRecorder();
        ImportService svc = new ImportService(Log.NOOP, g, out);
        svc.start(planA, r);
        check("issue #57：start() 固化的清单可由 plan() 读出",
                svc.plan() != null && svc.plan().count() == planA.count(),
                "实际 " + (svc.plan() == null ? "null" : svc.plan().count()));
        check("issue #57：第一个文件已进入传输", g.firstEntered.await(5, TimeUnit.SECONDS), "等待超时");
        svc.pause();
        check("issue #57：暂停轮次可用同一清单恢复", svc.canResumeWith(planA), "实际 false");
        check("issue #57：暂停轮次不可用不同清单恢复", !svc.canResumeWith(planB), "实际 true");
        check("issue #57：next=null 不可恢复", !svc.canResumeWith(null), "实际 true");
        g.releaseFirst.countDown();
        svc.cancel();
        check("issue #57：取消后 worker 在 5 秒内退出", svc.awaitWorker(5000), "等待超时");
        check("issue #57：轮次结束后 canResumeWith 为 false",
                !svc.canResumeWith(planA), "实际 true");
        check("issue #57：取消轮次收尾回调到达", r.done.await(5, TimeUnit.SECONDS), "等待超时");
        deleteTree(out);
    }

    // ---------------- 生产路径的暂停时钟接线（issue #58 一审 B-1） ----------------

    /**
     * 可手动拨动的时钟。用例先 {@link #set(long)} 到暂停时刻，worker 读到该值即放行
     * {@link #pauseRead}，让用例确认「worker 已停在文件边界并开始记账」。
     */
    static final class ManualClock implements ImportService.Clock {
        private volatile long now;
        private final long initial;
        private final AtomicInteger reads = new AtomicInteger();
        final CountDownLatch pauseRead = new CountDownLatch(1);

        ManualClock(long initial) {
            this.now = initial;
            this.initial = initial;
        }

        void set(long value) {
            now = value;
        }

        /**
         * 读到「非初始值」即视为 worker 已走到暂停记账点（issue #63）。
         *
         * 旧实现用「第 2 次读」当哨兵，本单在 `transfer.transfer(...)` 前新增了一次读钟
         * （刷新最后活动时刻），次数就错位了。按值判定与读次数无关，新增读钟点不会再让它失效。
         */
        @Override
        public long nanoTime() {
            long value = now;
            reads.incrementAndGet();
            if (value != initial) pauseRead.countDown();
            return value;
        }
    }

    /**
     * 两个文件各一道闸门：第一个放行后 worker 才能推进进度，第二个必须等用例断言完才放行。
     * 不能直接用 {@link FakeTransfer}（GATED 模式）——它的第二个文件不阻塞，进度会在断言时继续涨。
     */
    static final class TwoGateTransfer implements ResumableTransfer {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch firstEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final CountDownLatch secondEntered = new CountDownLatch(1);
        final CountDownLatch releaseSecond = new CountDownLatch(1);

        @Override
        public Result transfer(ImportPlan.Item item, String partPath, long resumeFrom,
                               CancelToken token, LongConsumer progress) {
            calls.add(item.relPath);
            int n = calls.size();
            CountDownLatch entered = n == 1 ? firstEntered : secondEntered;
            CountDownLatch release = n == 1 ? releaseFirst : releaseSecond;
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    return new Result(Kind.FAILED, "测试未放行第 " + n + " 个文件");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(Kind.FAILED, "测试线程被中断");
            }
            return writeFakePart(item, partPath);
        }
    }

    /**
     * issue #58 一审 B-1：暂停必须真的从生产路径（{@link ImportService}）接到
     * {@link TransferProgress}，而不是只在自检里对 TransferProgress 直接注入时钟。
     *
     * 时间线（注入时钟，全程不真等）：
     *   t0     —— start；
     *   +1 s   —— 第一个文件传完（300 B），worker 停在边界并调用 pause；
     *   +101 s —— resume（也就是暂停了 100 s）；
     *   +102 s —— 第二个文件开始，此处断言速率与 ETA；
     *   +103 s —— 第二个文件传完（再 1 s 的实际传输）。
     *
     * 健康实现：+102 s 时 300 B / 实际 1 s = 300 B/s、ETA 1 s；+103 s 时 400 B / 实际 2 s = 200 B/s。
     * 删掉 pause(...)：{@code pauseRead} 等不到，且速率按 101 s 墙钟掉到约 2.97 B/s；
     * 删掉 resume(...)：暂停永不结束，+103 s 仍把后 1 s 扣掉，速率算出 400 B/s（期望 200 B/s）。
     */
    static void importServicePauseClockCases() throws Exception {
        // 暂停时钟只需要「两个文件依次传」，与照片单元聚合无关：
        // 单元 A 是 ARW 300 B（无同名 JPG，JPG_FIRST 档回退 ARW），单元 B 是 JPG 100 B。
        Catalog c = new Catalog();
        c.add(new Catalog.Entry(1, 0, Catalog.FMT_FOLDER, 0, "DCIM"));
        c.add(new Catalog.Entry(2, 1, Catalog.FMT_FOLDER, 0, "100MSDCF"));
        c.add(new Catalog.Entry(3, 2, Catalog.FMT_ARW, 300, "DSC02183.ARW"));
        c.add(new Catalog.Entry(4, 2, Catalog.FMT_JPEG, 100, "DSC02184.JPG"));
        c.build();
        Selection s = new Selection(c);
        s.setChecked(3, true);      // 单元 A：DSC02183.ARW（300 B，先）
        s.setChecked(4, true);      // 单元 B：DSC02184.JPG（100 B，后）
        ImportPlan plan = ImportPlan.of(c, s, PhotoWall.of(c).units(), PhotoWall.Export.JPG_FIRST);
        check("B-1 暂停时钟：用例清单恰为 2 个文件", plan.count() == 2, "实际 " + plan.count());

        long t0 = 1_000_000_000L;
        long pausedAt = t0 + 1_000_000_000L;          // 第一个文件实际传了 1 s
        long resumedAt = pausedAt + 100_000_000_000L; // 暂停 100 s
        long finalAt = resumedAt + 1_000_000_000L;    // 第二个文件又传了 1 s

        File out = Files.createTempDirectory("wiredraw-58-clock").toFile();
        ManualClock clock = new ManualClock(t0);
        TwoGateTransfer g = new TwoGateTransfer();
        FinishRecorder r = new FinishRecorder();
        ImportService svc = new ImportService(Log.NOOP, g, out, clock);

        svc.start(plan, r);
        check("B-1 暂停时钟：第一个文件已进入传输",
                g.firstEntered.await(5, TimeUnit.SECONDS), "等待超时");
        clock.set(pausedAt);
        svc.pause();
        g.releaseFirst.countDown();
        boolean pauseRecorded = clock.pauseRead.await(5, TimeUnit.SECONDS);
        check("B-1 暂停时钟：worker 停在文件边界后调用了 progress.pause(...)",
                pauseRecorded, "5 秒内没看到暂停记账（删掉 pause(...) 会在这里红）");

        clock.set(resumedAt);
        svc.resume();
        check("B-1 暂停时钟：resume 后第二个文件开始",
                g.secondEntered.await(5, TimeUnit.SECONDS), "等待超时");

        TransferProgress p = r.lastProgress;
        boolean firstDone = p != null && p.bytesDone() == 300 && p.filesDone() == 1;
        check("B-1 暂停时钟：暂停前完成的 300 B 已计入进度", firstDone,
                p == null ? "无进度" : p.bytesDone() + " B / " + p.filesDone() + " 个");
        check("B-1 暂停时钟：速率分母扣掉了暂停的 100 s（300 B / 实际 1 s = 300 B/s）",
                firstDone && Math.abs(p.bytesPerSec(resumedAt) - 300) < 0.001,
                p == null ? "无进度" : "实际 " + p.bytesPerSec(resumedAt));
        check("B-1 暂停时钟：ETA 用扣掉暂停后的速率（剩余 100 B / 300 B/s = 1 s）",
                firstDone && p.etaSeconds(resumedAt) == 1,
                p == null ? "无进度" : "实际 " + p.etaSeconds(resumedAt));

        clock.set(finalAt);
        g.releaseSecond.countDown();
        check("B-1 暂停时钟：本轮 5 秒内结束", r.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("B-1 暂停时钟：两个文件都完成",
                r.ok == 2 && r.failed == 0, "ok=" + r.ok + " failed=" + r.failed);
        TransferProgress end = r.lastProgress;
        boolean allDone = end != null && end.bytesDone() == 400;
        check("B-1 暂停时钟：resume 后恢复计时（400 B / 实际 2 s = 200 B/s）",
                allDone && Math.abs(end.bytesPerSec(finalAt) - 200) < 0.001,
                end == null ? "无进度" : "实际 " + end.bytesPerSec(finalAt));
        check("B-1 暂停时钟：全部传完 ETA 为 0",
                allDone && end.etaSeconds(finalAt) == 0,
                end == null ? "无进度" : "实际 " + end.etaSeconds(finalAt));
        deleteTree(out);
    }

    // ---------------- 续传停用：旧 `.part` 一律作废（#177） ----------------

    /**
     * 复刻 `MtpTransfer` 的「目标同尺寸」分支：整文件已经下完，发现目标同尺寸 → 删 `.part`、
     * 返回 SKIPPED（仅尺寸核对，内容未校验）。用来钉住「SKIPPED 必须落账」，否则下一轮会再整文件重下。
     */
    static final class SameSizeSkipTransfer implements ResumableTransfer {
        int calls;

        @Override
        public Result transfer(ImportPlan.Item item, String partPath, long resumeFrom,
                               CancelToken token, LongConsumer progress) {
            calls++;
            File part = new File(partPath);
            if (part.exists()) part.delete();
            return new Result(Kind.SKIPPED, "已存在同尺寸文件，未覆盖（仅尺寸核对，内容未校验）");
        }
    }

    static void resumeDisabledCases() throws Exception {
        Catalog c = sample();
        Selection s = new Selection(c);
        s.setChecked(3, true);      // DSC02183 单元；用 RAW 档载荷钉住 ARW（300 字节）
        ImportPlan plan = ImportPlan.of(c, s, unitsWithArw(PhotoWall.of(c)),
                PhotoWall.Export.RAW);
        check("续传停用：用例清单恰为 1 个文件", plan.count() == 1, "实际 " + plan.count());
        String rel = "DCIM/100MSDCF/DSC02183.ARW";

        // 1) 部分残留（40 字节错误前缀）→ 必须从 0 重传，成品字节与基准完全一致。
        File out = Files.createTempDirectory("wiredraw-177-residue").toFile();
        Journal j = new Journal();
        File residue = new File(j.partPath(out.getAbsolutePath(), rel, 300));
        writeSizedBytes(residue, 40, (byte) 0x55);
        FakeTransfer t1 = new FakeTransfer(300);
        FinishRecorder r1 = new FinishRecorder();
        new ImportService(Log.NOOP, t1, out).start(plan, r1);
        check("续传停用：部分残留一轮在 5 秒内结束",
                r1.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("续传停用：编排层传给传输层的起点恒为 0（#177）",
                t1.resumeFroms.size() == 1 && t1.resumeFroms.get(0) == 0L,
                "实际 " + t1.resumeFroms);
        check("续传停用：部分残留不会产出「尺寸对、内容错」的坏文件",
                r1.ok == 1 && r1.failed == 0 && payloadMatches(new File(out, rel), 300),
                "ok=" + r1.ok + " failed=" + r1.failed + " 字节对不上=" + !payloadMatches(new File(out, rel), 300));
        check("续传停用：部分残留被删除，不留在 outDir",
                !residue.exists(), "仍存在：" + residue);
        deleteTree(out);

        // 2) 满尺寸残留 → 不得走旧的 `from == size` 直通；必须删掉重写。
        File fullOut = Files.createTempDirectory("wiredraw-177-full").toFile();
        File fullResidue = new File(j.partPath(fullOut.getAbsolutePath(), rel, 300));
        writeSizedBytes(fullResidue, 300, (byte) 0x5A);
        FakeTransfer t2 = new FakeTransfer(300);
        FinishRecorder r2 = new FinishRecorder();
        new ImportService(Log.NOOP, t2, fullOut).start(plan, r2);
        check("续传停用：满尺寸残留一轮在 5 秒内结束",
                r2.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("续传停用：满尺寸残留同样从 0 重传（起点不为 size）",
                t2.resumeFroms.size() == 1 && t2.resumeFroms.get(0) == 0L,
                "实际 " + t2.resumeFroms);
        check("续传停用：满尺寸残渣不会被改名当成品（尺寸凑够不算完成）",
                r2.ok == 1 && r2.failed == 0 && payloadMatches(new File(fullOut, rel), 300),
                "ok=" + r2.ok + " failed=" + r2.failed);
        deleteTree(fullOut);

        // 3) 成品 + 账本都在 → 跳过；删掉成品后不得只看账本就跳过（#177）。
        File doneOut = Files.createTempDirectory("wiredraw-177-done").toFile();
        FakeTransfer t3 = new FakeTransfer(doneOut);
        FinishRecorder r3 = new FinishRecorder();
        new ImportService(Log.NOOP, t3, doneOut).start(plan, r3);
        check("续传停用：正常一轮产出一个成品",
                r3.done.await(5, TimeUnit.SECONDS) && r3.ok == 1,
                "ok=" + r3.ok + " failed=" + r3.failed);
        FakeTransfer t4 = new FakeTransfer();
        FinishRecorder r4 = new FinishRecorder();
        new ImportService(Log.NOOP, t4, doneOut).start(plan, r4);
        check("续传停用：成品与账本都在时按 Skipped 跳过",
                r4.done.await(5, TimeUnit.SECONDS) && r4.skipped == 1 && t4.calls.isEmpty(),
                "skipped=" + r4.skipped + " calls=" + t4.calls.size());
        File dest = new File(doneOut, rel);
        boolean removed = dest.delete();
        check("续传停用：能删掉成品以模拟文件丢失", removed, "delete 失败");
        FakeTransfer t5 = new FakeTransfer();
        FinishRecorder r5 = new FinishRecorder();
        new ImportService(Log.NOOP, t5, doneOut).start(plan, r5);
        check("续传停用：账本记完成但成品缺失 → 重新导入，不静默跳过",
                r5.done.await(5, TimeUnit.SECONDS)
                        && r5.skipped == 0 && t5.calls.size() == 1,
                "skipped=" + r5.skipped + " calls=" + t5.calls.size());

        // 4) 账本完成 + 成品在但尺寸不符 → 也不得跳过（尺寸对账必须对到实际文件）。
        FinishRecorder r6 = new FinishRecorder();
        new ImportService(Log.NOOP, new FakeTransfer(doneOut), doneOut).start(plan, r6);
        check("续传停用：重建成品以便覆盖成错误尺寸",
                r6.done.await(5, TimeUnit.SECONDS) && r6.ok == 1 && r6.skipped == 0,
                "ok=" + r6.ok + " skipped=" + r6.skipped);
        writeSizedBytes(dest, 299, (byte) 0x11);
        FakeTransfer t7 = new FakeTransfer();
        FinishRecorder r7 = new FinishRecorder();
        new ImportService(Log.NOOP, t7, doneOut).start(plan, r7);
        check("续传停用：成品尺寸不符 → 重新导入，不静默跳过",
                r7.done.await(5, TimeUnit.SECONDS)
                        && r7.skipped == 0 && t7.calls.size() == 1,
                "skipped=" + r7.skipped + " calls=" + t7.calls.size());
        deleteTree(doneOut);

        // 5) 同尺寸成品已在、但没有账本 → 首轮的 SKIPPED 必须落账，否则下一轮继续整文件重下再丢弃
        //    （#177 复审：账本只在 OK 分支写，SKIPPED 漏写会让该文件永不收敛）。
        File sameOut = Files.createTempDirectory("wiredraw-177-samesize").toFile();
        writeSizedBytes(new File(sameOut, rel), 300, (byte) 0x33);
        SameSizeSkipTransfer t8 = new SameSizeSkipTransfer();
        FinishRecorder r8 = new FinishRecorder();
        new ImportService(Log.NOOP, t8, sameOut).start(plan, r8);
        check("续传停用：目标同尺寸命中时记 SKIPPED",
                r8.done.await(5, TimeUnit.SECONDS) && r8.skipped == 1 && t8.calls == 1,
                "skipped=" + r8.skipped + " calls=" + t8.calls);
        FakeTransfer t9 = new FakeTransfer();
        FinishRecorder r9 = new FinishRecorder();
        new ImportService(Log.NOOP, t9, sameOut).start(plan, r9);
        check("续传停用：SKIPPED 必须落账，第二轮不再重下同尺寸成品（#177 复审）",
                r9.done.await(5, TimeUnit.SECONDS) && r9.skipped == 1 && t9.calls.isEmpty(),
                "skipped=" + r9.skipped + " calls=" + t9.calls.size());
        deleteTree(sameOut);
    }

    static boolean payloadMatches(File file, int size) throws IOException {
        if (!file.isFile() || file.length() != size) return false;
        byte[] actual = Files.readAllBytes(file.toPath());
        for (int i = 0; i < size; i++) {
            if (actual[i] != (byte) (i % 251)) return false;
        }
        return true;
    }

    static void writeSizedBytes(File f, int size, byte value) throws IOException {
        indexMkdirs(f.getParentFile());
        byte[] buf = new byte[size];
        Arrays.fill(buf, value);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(buf);
        }
    }

    // ---------------- 文件内进度（issue #70） ----------------

    /**
     * issue #70 的两条集成用例（#177 续传停用后按「从 0 重传」改口径）：
     *
     * 1) **旧半成品不计入进度起点**：磁盘上虽有 40 字节 `.part`，首个进度事件必须是 0，
     *    重传期间能看到文件内中间值——只有「文件结束才一次加满」或「bytesDone() 漏算当前文件」
     *    都会让这条红。
     * 2) **同一文件失败后重试不得双计**：重试从 0 开始时总字节必须仍是 0（失败时已回滚）；
     *    `beginFile` 把失败前落盘/已计字节再加一遍会变成 40，这条立刻红。
     */
    static void resumeProgressCases() throws Exception {
        // 1) 旧半成品 40 B：单个 ARW 300 B，本轮仍须从 0 重传。
        Catalog c = sample();
        Selection s = new Selection(c);
        s.setChecked(3, true);
        ImportPlan resumePlan = ImportPlan.of(c, s, unitsWithArw(PhotoWall.of(c)),
                PhotoWall.Export.RAW);
        check("文件内进度：续传用例恰为 1 个文件", resumePlan.count() == 1,
                "实际 " + resumePlan.count());

        File out = Files.createTempDirectory("wiredraw-70-resume").toFile();
        Journal j = new Journal();
        writeSizedFile(new File(j.partPath(out.getAbsolutePath(),
                "DCIM/100MSDCF/DSC02183.ARW", 300)), 40);

        ResumeReportingTransfer t1 = new ResumeReportingTransfer(false, 60);
        FinishRecorder r1 = new FinishRecorder();
        new ImportService(Log.NOOP, t1, out).start(resumePlan, r1);
        check("文件内进度：续传一轮在 5 秒内结束", r1.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("文件内进度：旧半成品不抬起点，首个进度事件是 0（#177）",
                !r1.progressBytes.isEmpty() && r1.progressBytes.get(0) == 0L,
                "实际 " + r1.progressBytes);
        boolean sawMid = false;
        for (long b : r1.progressBytes) {
            if (b > 0L && b < 300L) sawMid = true;
        }
        check("文件内进度：重传期间能看到文件内中间值（0 < x < 300）", sawMid,
                "实际 " + r1.progressBytes);
        check("文件内进度：重传完成后总字节与文件计数正确",
                r1.lastProgress != null && r1.lastProgress.bytesDone() == 300
                        && r1.lastProgress.filesDone() == 1,
                r1.lastProgress == null ? "无进度"
                        : r1.lastProgress.bytesDone() + " / " + r1.lastProgress.filesDone());
        deleteTree(out);

        // 2) 同一文件失败后重试：重试起点不得把已落盘字节再计一遍。
        Catalog c2 = sample();
        Selection s2 = new Selection(c2);
        s2.setChecked(1, true);
        ImportPlan retryPlan = ImportPlan.of(c2, s2, PhotoWall.of(c2).units(),
                PhotoWall.Export.JPG_FIRST);
        check("文件内进度：重试用例恰为 2 个文件", retryPlan.count() == 2,
                "实际 " + retryPlan.count());

        File out2 = Files.createTempDirectory("wiredraw-70-retry").toFile();
        ResumeReportingTransfer t2 = new ResumeReportingTransfer(true, 60);
        FinishRecorder r2 = new FinishRecorder();
        new ImportService(Log.NOOP, t2, out2).start(retryPlan, r2);
        check("文件内进度：重试的文件刚进入第二次尝试",
                t2.retryEntered.await(5, TimeUnit.SECONDS), "等待超时");
        long atRetry = r2.lastProgress == null ? -1L : r2.lastProgress.bytesDone();
        check("文件内进度：重试不得双计（第二次尝试刚上报起点 0 时总字节仍是 0）",
                atRetry == 0L, "实际 " + atRetry);
        t2.releaseRetry.countDown();
        check("文件内进度：重试一轮在 5 秒内结束", r2.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("文件内进度：首次失败后重试仍算完成",
                r2.ok == 2 && r2.failed == 0, "ok=" + r2.ok + " failed=" + r2.failed);
        check("文件内进度：重试的第二次尝试从 0 重传（续传停用，#177）",
                t2.resumeFroms.size() >= 2 && t2.resumeFroms.get(1) == 0L,
                "实际 " + t2.resumeFroms);
        check("文件内进度：每个文件完成点的总字节等于累计大小（100、150）",
                r2.bytesAtFileFinish.size() == 2
                        && r2.bytesAtFileFinish.get(0) == 100L
                        && r2.bytesAtFileFinish.get(1) == 150L,
                "实际 " + r2.bytesAtFileFinish);
        check("文件内进度：全部完成后总字节等于 plan 总量",
                r2.lastProgress != null && r2.lastProgress.bytesDone() == 150,
                r2.lastProgress == null ? "无进度" : "实际 " + r2.lastProgress.bytesDone());
        deleteTree(out2);
    }

    // ---------------- 无主半成品清理（issue #52） ----------------

    /**
     * 钉住清理判据（#177 续传停用后改口径）：保留集恒空。
     *
     * 续传停用后不存在「合法半成品」：
     * - 同 `relPath`、同 size 的旧 `.part` → 不可信，必须删（不再当作可续传前缀）；
     * - 同 `relPath`、旧 size → 必须删（#52 主症状）；
     * - `relPath` 已完全不在目录 → 必须删（对象已不存在，接不上）。
     *
     * 干扰项覆盖账本、索引、`.tmp`、没有 size 段的 `.part`、子目录里的 `.part`；
     * 另钉住 `keepNames == null` 的安全语义：一个都不删。
     *
     * 清理开关另钉住两个方向：枚举完整 → 照常清；枚举不完整（丢弃对象 / 存储无句柄 / 取消）
     * → 一个都不清，并留一行含计数的原因。完整性标志与计数由 {@link ScanResult} 一次算出，
     * {@link CatalogScanner} 只负责填数（Android 侧，JVM 单测覆盖不到的那部分靠代码走查 + 真机）。
     */
    static void partResidueCases() throws Exception {
        File out = Files.createTempDirectory("wiredraw-part-residue").toFile();
        Catalog c = sample();
        Set<String> keep = PartResidue.keepNames(c.all());
        Journal j = new Journal();

        String keepSize = j.partPath(out.getAbsolutePath(), "DCIM/100MSDCF/DSC02183.ARW", 300);
        writeSizedFile(new File(keepSize), 40);

        // 关键方向 1：同 relPath、旧 size —— 目录里已没有这个 size 的对象，必须删。
        String staleSize = j.partPath(out.getAbsolutePath(), "DCIM/100MSDCF/DSC02183.ARW", 299);
        writeSizedFile(new File(staleSize), 40);

        // 关键方向 2：relPath 已完全不在目录 —— 无主，必须删。
        String gone = j.partPath(out.getAbsolutePath(), "DCIM/100MSDCF/DSC99999.ARW", 300);
        writeSizedFile(new File(gone), 40);

        writeSizedFile(new File(out, "import-journal.txt"), 5);
        // 存量 `imported-index.txt`（#20 停写前留下的普通文件）：它不是半成品，必须原样保留。
        writeSizedFile(new File(out, "imported-index.txt"), 5);
        writeSizedFile(new File(out, "imported-index.txt.tmp"), 5);
        writeSizedFile(new File(out, "plain.part"), 5);
        writeSizedFile(new File(out, "DCIM/stray.300.part"), 5);

        check("无主半成品：续传停用后保留集恒空（#177）",
                keep.isEmpty(), "keep=" + keep);
        check("无主半成品：keepNames(null) 返回空集，不退化成全删",
                PartResidue.keepNames(null).isEmpty(), "返回非空");
        check("无主半成品：keep 集为 null 时 orphans 为空",
                PartResidue.orphans(out, null).isEmpty(), "返回非空");

        // issue #52：完整性开关。丢弃过对象 / 存储取句柄失败 / 取消都算不完整。
        ScanResult ok = ScanResult.of(c, 3, 0, 0, false);
        ScanResult dropped = ScanResult.of(c, 3, 1, 0, false);
        ScanResult noHandles = ScanResult.of(c, 3, 0, 1, false);
        ScanResult cancelled = ScanResult.of(c, 3, 0, 0, true);
        check("扫描完整性：无丢弃无失败 → complete",
                ok.complete && ok.scanned == 3 && ok.dropped == 0, "未判为完整或计数不对");
        check("扫描完整性：丢弃对象 → 不完整，且计数对得上",
                !dropped.complete && dropped.scanned == 3 && dropped.dropped == 1,
                "complete=" + dropped.complete + " scanned=" + dropped.scanned + " dropped=" + dropped.dropped);
        check("扫描完整性：存储取句柄失败 → 不完整",
                !noHandles.complete && noHandles.missingHandles == 1, "未判为不完整");
        check("扫描完整性：取消 → 不完整", !cancelled.complete, "取消仍判为完整");

        // 关键方向：枚举不完整 → 一个都不清，并留一行给用户看的原因。
        RecordingLog skipLog = new RecordingLog();
        int skipped = PartResidue.cleanIfComplete(out, keep, dropped, skipLog);
        check("无主半成品：枚举不完整 → 一个都不清",
                skipped == 0 && new File(staleSize).exists() && new File(gone).exists(),
                "删了 " + skipped + " 个或文件已不在");
        check("无主半成品：跳过清理要留原因（含丢弃计数）",
                skipLog.contains("枚举不完整") && skipLog.contains("丢弃 1 个"),
                "日志：" + skipLog.lines);
        check("无主半成品：没有扫描结果 → 不清",
                PartResidue.cleanIfComplete(out, keep, null, Log.NOOP) == 0
                        && new File(staleSize).exists(), "null 结果时删了文件");

        RecordingLog cleanLog = new RecordingLog();
        int cleaned = PartResidue.cleanIfComplete(out, keep, ok, cleanLog);
        check("无主半成品：枚举完整 → 清理全部 .part", cleaned == 3 && cleanLog.contains("已清理无主半成品"),
                "实际 " + cleaned);
        check("无主半成品：同 relPath、旧 size → 被删（#52 主症状）",
                !new File(staleSize).exists(), "仍存在：" + staleSize);
        check("无主半成品：relPath 已不在目录 → 被删",
                !new File(gone).exists(), "仍存在：" + gone);
        check("无主半成品：relPath + size 都能对上也不保留（不再续传，#177）",
                !new File(keepSize).exists(), "仍存在：" + keepSize);
        check("无主半成品：账本 / 存量索引 / .tmp / 无 size 段的 .part 不被动",
                new File(out, "import-journal.txt").isFile()
                        && new File(out, "imported-index.txt").isFile()
                        && new File(out, "imported-index.txt.tmp").isFile()
                        && new File(out, "plain.part").isFile(),
                "干扰项被删");
        check("无主半成品：子目录里的 .part 不被动（半成品只平铺在 outDir）",
                new File(out, "DCIM/stray.300.part").isFile(), "子目录项被删");

        // 命名口径（#210）：生成侧与识别侧必须同一口径；用户自己的 `.part` 不算半成品。
        String generated = new File(j.partPath(out.getAbsolutePath(),
                "DCIM/100MSDCF/DSC02183.ARW", 24389632)).getName();
        check("半成品命名：partPath 生成的名字必被 isPartName 认出（#210）",
                PartResidue.isPartName(generated), "生成=" + generated);
        check("半成品命名：不是本工具命名的 .part 一律不算半成品（#210）",
                !PartResidue.isPartName("plain.part")
                        && !PartResidue.isPartName("x.12a.part")
                        && !PartResidue.isPartName("x..part")
                        && !PartResidue.isPartName("x.300.part.bak"),
                "判据放宽了");

        deleteTree(out);
    }

    // ---------------- 探活判定（#268） ----------------

    /**
     * 探活结论的唯一归属：`alive(hasSession, lockBusy, finished, error, gotInfo)`。
     *
     * #268 的根因是「等锁时间被算进 1200 ms 窗口」：锁被活跃 IO 持有时按存活处理，
     * 窗口只约束 native 调用本身；否则单张 RAW（真机约 1.14 s）就会假超时，
     * 暂停后立刻 Resume 把本来能续的一轮取消成 ERROR。
     */
    static void probeVerdictCases() {
        check("探活：没有会话 → 判死（#268）",
                !ProbeVerdict.alive(false, false, true, false, false), "误判为活");
        check("探活：ioLock 被活跃 IO 持锁 → 判活，不等锁（#268）",
                ProbeVerdict.alive(true, true, true, false, false), "误判为死");
        check("探活：拿到锁、getObjectInfo 有返回 → 判活（#268）",
                ProbeVerdict.alive(true, false, true, false, true), "误判为死");
        check("探活：拿到锁但 getObjectInfo 返回 null → 判死（#268）",
                !ProbeVerdict.alive(true, false, true, false, false), "误判为活");
        check("探活：拿到锁但 native 调用抛异常 → 判死（#268）",
                !ProbeVerdict.alive(true, false, true, true, false), "误判为活");
        check("探活：窗口内没结束（真超时）→ 判死（#268）",
                !ProbeVerdict.alive(true, false, false, false, false), "误判为活");

        // 源码守卫：探活不得再用阻塞 lock()，否则等锁时间又会进超时窗口。
        File main = new File(System.getProperty("user.dir"), "src/com/rea/mtpimport/Main.java");
        String src;
        try {
            src = Files.readString(main.toPath());
        } catch (IOException e) {
            src = null;
        }
        check("探活：Main 的探活用 tryLock，不再阻塞取锁（#268）",
                src != null && src.contains("s.ioLock().tryLock()")
                        && !src.contains("s.ioLock().lock();"),
                src == null ? "读不到 " + main : "仍在阻塞取锁");
    }

    // ---------------- 无进展挂起判定（issue #63） ----------------

    /**
     * 一条挂起判定用例：跑断言的同时登记 6 个入参，供末尾的去重断言使用（issue #116）。
     *
     * 旧实现有两条标签传了同一组参数（`true/false/false/t0/t0+g/g`），7 个标签只钉住 6 个条件；
     * 把入参集中登记后，「标签不同、条件相同」会直接被去重断言抓住，而不用逐条人工比对。
     */
    static void stalledCase(List<String> keys, String label, boolean expected,
                            boolean running, boolean cancelled, boolean paused,
                            long lastActivityNanos, long nowNanos, long graceNanos,
                            String detail) {
        keys.add(running + "|" + cancelled + "|" + paused + "|"
                + lastActivityNanos + "|" + nowNanos + "|" + graceNanos);
        check(label, ImportService.stalled(running, cancelled, paused,
                lastActivityNanos, nowNanos, graceNanos) == expected, detail);
    }

    static void stalledDetectionCases() {
        long g = ImportService.NO_ACTIVITY_GRACE_NANOS;
        long t0 = 10_000_000_000L;
        List<String> keys = new ArrayList<>();

        stalledCase(keys, "挂起判定：未取消 + 未暂停 + 距最后活动超过 60 s（60.5 s）→ 成立",
                true, true, false, false, t0, t0 + g + 500_000_000L, g, "不成立");
        stalledCase(keys, "挂起判定：距最后活动 59.5 s 不成立",
                false, true, false, false, t0, t0 + g - 500_000_000L, g, "误报");
        stalledCase(keys, "挂起判定：恰好 60 s 成立（边界含等号）",
                true, true, false, false, t0, t0 + g, g, "边界不成立");
        stalledCase(keys, "挂起判定：已取消不成立（交给 isCancelStuck 的 3 s 口径）",
                false, true, true, false, t0, t0 + 10 * g, g, "取消态误报");
        stalledCase(keys, "挂起判定：暂停中不成立（Pause & exit 可停任意久）",
                false, true, false, true, t0, t0 + 10 * g, g, "暂停态误报");
        stalledCase(keys, "挂起判定：worker 已退出不成立",
                false, false, false, false, t0, t0 + 10 * g, g, "未运行误报");
        stalledCase(keys, "挂起判定：还没进过文件（lastActivity=0）不成立",
                false, true, false, false, 0L, t0 + 10 * g, g, "0 值误报");

        check("挂起判定：7 条用例对应 7 个不同条件（issue #116）",
                keys.size() == 7 && new HashSet<>(keys).size() == 7,
                "入参组合 " + new HashSet<>(keys).size() + " / " + keys.size() + "：" + keys);
    }

    /**
     * 端到端：钉住「服务真的会在没取消时刷新/判出挂起」。
     *
     * 只测纯判定不够——删掉 run() 里刷新 lastActivityNanos 那一行，纯判定照样全绿，
     * 而线上仍然死键。这里让 worker 真卡在 importFile 里，用例拨动注入时钟。
     */
    static void stalledServiceCases() throws Exception {
        Catalog c = sample();
        Selection s = new Selection(c);
        s.setChecked(3, true);
        ImportPlan plan = ImportPlan.of(c, s, PhotoWall.of(c).units(), PhotoWall.Export.JPG_FIRST);
        check("挂起用例清单恰为 1 个文件", plan.count() == 1, "实际 " + plan.count());

        File out = Files.createTempDirectory("wiredraw-63-stall").toFile();
        long t0 = 10_000_000_000L;
        ManualClock clock = new ManualClock(t0);
        FakeTransfer h = new FakeTransfer(FakeTransfer.Mode.HANGING);
        FinishRecorder r = new FinishRecorder();
        ImportService svc = new ImportService(Log.NOOP, h, out, clock);
        svc.start(plan, r);
        check("挂起：worker 已进入 importFile", h.entered.await(5, TimeUnit.SECONDS), "等待超时");

        check("挂起：一次传输进行 59.5 s 仍不判挂起",
                !svc.isStalledAt(t0 + ImportService.NO_ACTIVITY_GRACE_NANOS - 500_000_000L),
                "实际 true");
        check("挂起：一次传输满 60 s 判挂起（未点取消也有出路）",
                svc.isStalledAt(t0 + ImportService.NO_ACTIVITY_GRACE_NANOS), "实际 false");

        svc.pause();
        check("挂起：暂停中不判挂起",
                !svc.isStalledAt(t0 + 10 * ImportService.NO_ACTIVITY_GRACE_NANOS), "实际 true");
        svc.resume();

        svc.cancel();
        check("挂起：取消后交给 isCancelStuck，不判挂起",
                !svc.isStalledAt(t0 + 10 * ImportService.NO_ACTIVITY_GRACE_NANOS), "实际 true");

        h.release.countDown();
        check("挂起：放行后本轮 5 s 内收尾", r.done.await(5, TimeUnit.SECONDS), "等待超时");
        deleteTree(out);
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        if (!f.delete()) {
            System.err.println("清理临时文件失败：" + f);
        }
    }

    // ---------------- 界面状态映射（UiState） ----------------

    static void uiStateCases() {
        check("10 个界面状态齐全（§1.2）", UiState.State.values().length == 10,
                "实际 " + UiState.State.values().length);
        check("parse 认任务书短名 PERMISSION", UiState.parse("PERMISSION") == UiState.State.PERMISSION_PENDING,
                "实际 " + UiState.parse("PERMISSION"));
        check("parse 认短名 READY", UiState.parse("READY") == UiState.State.CATALOG_READY,
                "实际 " + UiState.parse("READY"));
        check("parse 认短名 EMPTY", UiState.parse("EMPTY") == UiState.State.EMPTY_CATALOG,
                "实际 " + UiState.parse("EMPTY"));
        check("parse 大小写与连字符不敏感", UiState.parse(" importing ") == UiState.State.IMPORTING
                        && UiState.parse("permission-pending") == UiState.State.PERMISSION_PENDING,
                "实际 " + UiState.parse(" importing "));
        check("parse 未知值返回 null（不能悄悄给默认态）", UiState.parse("NOPE") == null
                        && UiState.parse(null) == null,
                "实际 " + UiState.parse("NOPE"));
        check("isTransfer 只认 IMPORTING / PAUSED",
                UiState.isTransfer(UiState.State.IMPORTING) && UiState.isTransfer(UiState.State.PAUSED)
                        && !UiState.isTransfer(UiState.State.CATALOG_READY),
                "isTransfer 判定不符");
        check("showsLibrary 只认 CATALOG_READY",
                UiState.showsLibrary(UiState.State.CATALOG_READY)
                        && !UiState.showsLibrary(UiState.State.SCANNING),
                "showsLibrary 判定不符");
        // 顶栏常驻由 TopBarSpec.hasBar 单点保证（K-06 / N-1 单一事实源）；
        // 这里不再用 UiState.hasTopBar 的恒真包装重复断言（issue #212 / A13）。
    }

    // ---------------- 界面状态迁移表（UiTransition，#213） ----------------

    /**
     * 墓碑：#213 —— 跨状态字段（backgroundScanning / transferViewClosed / exitConfirmOpen）
     * 曾经散在 AppModel 的十个方法里，改一个状态容易漏复位另一个。迁移表把「进入某动作时
     * 三个字段该变成什么」集中到一处，这里逐条钉住；AppModel 只按表赋值。
     *
     * 注意 DONE 这条：`showDone` 历史上不写 backgroundScanning，`DONE` 是否应复位它
     * 规格未定（#213「待裁决」），本表按现状保留，不在这里顺手拍。
     */
    static void uiTransitionCases() {
        UiTransition.Result cold = UiTransition.resolve(UiTransition.Action.COLD, true, true, true);
        UiTransition.Result permission = UiTransition.resolve(UiTransition.Action.PERMISSION, true, true, true);
        UiTransition.Result empty = UiTransition.resolve(UiTransition.Action.EMPTY, true, true, true);
        UiTransition.Result unavailable = UiTransition.resolve(UiTransition.Action.UNAVAILABLE, true, true, true);
        UiTransition.Result error = UiTransition.resolve(UiTransition.Action.ERROR, true, true, true);
        check("迁移 COLD/PERMISSION/EMPTY/UNAVAILABLE/ERROR：状态正确、复位扫描标志、覆盖层保持",
                cold.state == UiState.State.COLD && permission.state == UiState.State.PERMISSION_PENDING
                        && empty.state == UiState.State.EMPTY_CATALOG
                        && unavailable.state == UiState.State.UNAVAILABLE
                        && error.state == UiState.State.ERROR
                        && !cold.backgroundScanning && !permission.backgroundScanning
                        && !empty.backgroundScanning && !unavailable.backgroundScanning
                        && !error.backgroundScanning
                        && cold.transferViewClosed && cold.exitConfirmOpen,
                "初始/错误态迁移口径不符");

        UiTransition.Result full = UiTransition.resolve(UiTransition.Action.CATALOG_FULL, true, false, false);
        check("迁移 CATALOG_FULL：换目录态且复位扫描标志",
                full.state == UiState.State.CATALOG_READY && !full.backgroundScanning,
                "实际 " + full.state);

        UiTransition.Result partial = UiTransition.resolve(UiTransition.Action.CATALOG_PARTIAL, false, true, true);
        check("迁移 CATALOG_PARTIAL：置渐进标志、覆盖层保持",
                partial.state == UiState.State.CATALOG_READY && partial.backgroundScanning
                        && partial.transferViewClosed && partial.exitConfirmOpen,
                "渐进迁移口径不符");

        UiTransition.Result scanning = UiTransition.resolve(UiTransition.Action.SCANNING, true, true, true);
        check("迁移 SCANNING：只换状态、不动扫描标志（渐进态早退由调用方保证）",
                scanning.state == UiState.State.SCANNING && scanning.backgroundScanning
                        && scanning.transferViewClosed && scanning.exitConfirmOpen,
                "扫描态迁移口径不符");

        UiTransition.Result importing = UiTransition.resolve(UiTransition.Action.IMPORT_START, true, true, true);
        check("迁移 IMPORT_START：复位视图关闭与确认框、保留扫描标志",
                importing.state == UiState.State.IMPORTING && !importing.transferViewClosed
                        && !importing.exitConfirmOpen && importing.backgroundScanning,
                "导入迁移口径不符");

        UiTransition.Result done = UiTransition.resolve(UiTransition.Action.DONE, true, true, true);
        check("迁移 DONE：复位覆盖层、保留扫描标志（#213 待裁决，未定不拍）",
                done.state == UiState.State.DONE && !done.transferViewClosed
                        && !done.exitConfirmOpen && done.backgroundScanning,
                "完成态迁移口径不符");

        UiTransition.Result progress = UiTransition.resolve(UiTransition.Action.PROGRESS, true, true, true);
        UiTransition.Result paused = UiTransition.resolve(UiTransition.Action.PAUSED, true, true, true);
        UiTransition.Result backReady = UiTransition.resolve(UiTransition.Action.BACK_READY, true, true, true);
        UiTransition.Result backCold = UiTransition.resolve(UiTransition.Action.BACK_COLD, true, true, true);
        check("迁移 PROGRESS/PAUSED/BACK_*：只换状态、三个字段全保持",
                progress.state == UiState.State.IMPORTING && paused.state == UiState.State.PAUSED
                        && backReady.state == UiState.State.CATALOG_READY && backCold.state == UiState.State.COLD
                        && progress.backgroundScanning && progress.transferViewClosed && progress.exitConfirmOpen
                        && paused.backgroundScanning && paused.transferViewClosed && paused.exitConfirmOpen
                        && backReady.backgroundScanning && backReady.transferViewClosed && backReady.exitConfirmOpen
                        && backCold.backgroundScanning && backCold.transferViewClosed && backCold.exitConfirmOpen,
                "保持型迁移口径不符");

        UiTransition.Result discard = UiTransition.resolve(UiTransition.Action.DISCARD_PARTIAL, true, true, true);
        UiTransition.Result demoReset = UiTransition.resolve(UiTransition.Action.DEMO_RESET, true, true, true);
        UiTransition.Result leave = UiTransition.resolve(UiTransition.Action.LEAVE_VIEW, true, false, true);
        check("迁移 DISCARD_PARTIAL/DEMO_RESET/LEAVE_VIEW：state 不变、各自字段终值正确",
                discard.state == null && !discard.backgroundScanning
                        && discard.transferViewClosed && discard.exitConfirmOpen
                        && demoReset.state == null && demoReset.backgroundScanning
                        && !demoReset.transferViewClosed && !demoReset.exitConfirmOpen
                        && leave.state == null && leave.backgroundScanning && leave.transferViewClosed
                        && !leave.exitConfirmOpen,
                "非状态迁移的字段处置不符");
    }

    // ---------------- 整轮结束去向（FinishOutcome，issue #64） ----------------

    static void finishOutcomeCases() {
        // 核心：关闭视图 + 整轮全失败 —— 旧顺序（closed 在前）会静默回照片墙。
        check("FinishOutcome：关闭视图 + 整轮全失败仍落 ERROR",
                FinishOutcome.of(false, true, 0, 0, 3) == FinishOutcome.Action.SHOW_ERROR,
                "实际 " + FinishOutcome.of(false, true, 0, 0, 3));

        check("FinishOutcome：取消优先于全失败（只关视图、不落 ERROR）",
                FinishOutcome.of(true, false, 0, 0, 3) == FinishOutcome.Action.LEAVE_TRANSFER_VIEW,
                "实际 " + FinishOutcome.of(true, false, 0, 0, 3));

        check("FinishOutcome：取消 + 关闭视图 + 全失败仍是 LEAVE_TRANSFER_VIEW",
                FinishOutcome.of(true, true, 0, 0, 2) == FinishOutcome.Action.LEAVE_TRANSFER_VIEW,
                "实际 " + FinishOutcome.of(true, true, 0, 0, 2));

        check("FinishOutcome：关闭视图 + 有成功 → 保持当前视图（不跳 DONE）",
                FinishOutcome.of(false, true, 5, 0, 1) == FinishOutcome.Action.STAY_CURRENT_VIEW,
                "实际 " + FinishOutcome.of(false, true, 5, 0, 1));

        check("FinishOutcome：关闭视图 + 全部已存在跳过 → 保持当前视图（不是 ERROR）",
                FinishOutcome.of(false, true, 0, 4, 0) == FinishOutcome.Action.STAY_CURRENT_VIEW,
                "实际 " + FinishOutcome.of(false, true, 0, 4, 0));

        check("FinishOutcome：未关闭 + 全失败 → ERROR",
                FinishOutcome.of(false, false, 0, 0, 1) == FinishOutcome.Action.SHOW_ERROR,
                "实际 " + FinishOutcome.of(false, false, 0, 0, 1));

        check("FinishOutcome：未关闭 + 有成功 → DONE",
                FinishOutcome.of(false, false, 3, 0, 0) == FinishOutcome.Action.SHOW_DONE,
                "实际 " + FinishOutcome.of(false, false, 3, 0, 0));

        check("FinishOutcome：未关闭 + 全部跳过 → DONE（跳过计入完成数）",
                FinishOutcome.of(false, false, 0, 2, 0) == FinishOutcome.Action.SHOW_DONE,
                "实际 " + FinishOutcome.of(false, false, 0, 2, 0));

        check("FinishOutcome：空轮次 → 回照片墙",
                FinishOutcome.of(false, false, 0, 0, 0) == FinishOutcome.Action.BACK_TO_CATALOG,
                "实际 " + FinishOutcome.of(false, false, 0, 0, 0));

        check("FinishOutcome：部分成功部分失败（未关闭）→ DONE 而不是 ERROR",
                FinishOutcome.of(false, false, 2, 1, 3) == FinishOutcome.Action.SHOW_DONE,
                "实际 " + FinishOutcome.of(false, false, 2, 1, 3));
    }

    // ---------------- 主 CTA 准入与顶栏形态（N-1：Compose 与自检共用同一判定） ----------------

    /**
     * `ImportGate` 是 `LibraryScreen` 里 `enabled = ...` 的唯一出处。
     * 两个条件都必须有用例守着：变异掉任意一个都必须让本段变红（review N-1 的变异实验）。
     */
    static void importGateCases() {
        check("ImportGate：空闲且有空间时可点", ImportGate.enabled(false, false),
                "enabled=false");
        check("ImportGate：渐进扫描中禁用（不能丢 !backgroundScanning）",
                !ImportGate.enabled(true, false), "enabled=true");
        check("ImportGate：空间不足时禁用（不能丢 !lowSpace）",
                !ImportGate.enabled(false, true), "enabled=true");
        check("ImportGate：两个条件同时成立也禁用", !ImportGate.enabled(true, true),
                "enabled=true");
    }

    /**
     * `TopBarSpec` 是「哪些状态有顶栏 / 哪个状态出计数胶囊」的唯一出处，
     * Compose 页面（LibraryScreen / StatusScreen / TransferScreen / PhotoScreen / LibraryTopBar）共用。
     */
    static void topBarSpecCases() {
        check("TopBarSpec：10 态都有顶栏",
                java.util.Arrays.stream(UiState.State.values()).allMatch(TopBarSpec::hasBar),
                "任何状态都不得去掉顶栏");
        check("TopBarSpec：计数胶囊在 CATALOG_READY 且计数非零时出现",
                TopBarSpec.showsCountPill(UiState.State.CATALOG_READY, 511),
                "READY / 511 未出胶囊");
        check("TopBarSpec：CATALOG_READY 计数为 0 时不出胶囊",
                !TopBarSpec.showsCountPill(UiState.State.CATALOG_READY, 0),
                "空目录仍出胶囊");
        check("TopBarSpec：除 CATALOG_READY 外的状态一律不出计数胶囊",
                java.util.Arrays.stream(UiState.State.values())
                        .filter(s -> s != UiState.State.CATALOG_READY)
                        .noneMatch(s -> TopBarSpec.showsCountPill(s, 511)),
                "非 READY 状态出现了计数胶囊");
        // P1-1（#44 复审）：传输态不再让位给环，必须有连接指示。只钉这两个状态——
        // `showsConnectionIndicator` 对所有 `UiState` 为真，扫全量等于恒真断言。
        check("TopBarSpec：传输态（IMPORTING / PAUSED）渲染连接指示（P1-1）",
                TopBarSpec.showsConnectionIndicator(UiState.State.IMPORTING)
                        && TopBarSpec.showsConnectionIndicator(UiState.State.PAUSED),
                "传输态漏了连接指示");
        check("TopBarSpec：在线时连接指示取设备短名",
                "a7C".equals(TopBarSpec.connectionLabel(false, "a7C")),
                "实际 " + TopBarSpec.connectionLabel(false, "a7C"));
        check("TopBarSpec：断线时连接指示切 Disconnected",
                "Disconnected".equals(TopBarSpec.connectionLabel(true, "a7C")),
                "实际 " + TopBarSpec.connectionLabel(true, "a7C"));
        // 页面必须真的**调用**，否则纯 JDK 用例再绿也钉不住 Compose。
        // N1（第三轮评审）：只钉「符号存在」会被「调用外套 if (false)」骗过。这里钉执行条件：
        // 守卫行 `if (TopBarSpec.showsConnectionIndicator(model.state)) {` 与其后 ≤2 行的调用，
        // 两行都必须在 TransferScreen.kt（声明行 `(model: AppModel)` 不含右括号，不会误命中）。
        // 守卫被换成 `if (false)` → 守卫 0 命中；守卫与调用间再套一层 `if (false)` → 行距 2 变 3。
        File uiDir = new File(new File(System.getProperty("user.dir"), "src"), "com/rea/mtpimport/ui");
        List<String> indicatorGuards = new ArrayList<>();
        List<String> indicatorCalls = new ArrayList<>();
        scanSourceText(uiDir, "if (TopBarSpec.showsConnectionIndicator(model.state)) {", null, indicatorGuards);
        scanSourceText(uiDir, "TransferConnectionIndicator(model)", null, indicatorCalls);
        int guardLine = -1;
        int callLine = -1;
        for (String h : indicatorGuards) if (h.contains("TransferScreen.kt")) guardLine = hitLineOf(h);
        for (String h : indicatorCalls) if (h.contains("TransferScreen.kt")) callLine = hitLineOf(h);
        check("传输页在 showsConnectionIndicator 守卫内调用 TransferConnectionIndicator(model)（P1-1 / N1）",
                guardLine > 0 && callLine > guardLine && callLine - guardLine <= 2,
                "守卫行 " + guardLine + "，调用行 " + callLine + "\n守卫：\n"
                        + String.join("\n", indicatorGuards) + "\n调用：\n" + String.join("\n", indicatorCalls));
    }

    /**
     * P1-3（#44 复审）：进度环轨道从 `outlineSoft`（白底 1.7:1）改用 `outline`（4.47:1 ≥ 3:1）。
     *
     * 注意：下面两条源码扫描是**弱契约**——只防「把轨道改回 `outlineSoft`」和「删掉
     * `drawCircle(UiColors.outline,`」这两种具体回退；换行重排、改写表达式都会假红。
     * token 值本身由 `colorTokensCases` 与最后一条对比度断言守，别把源码扫描当强契约。
     */
    static void ringTrackCases() {
        File srcRoot = new File(System.getProperty("user.dir"), "src");
        List<String> softTrack = new ArrayList<>();
        int scanned = scanSourceText(srcRoot, "drawCircle(UiColors.outlineSoft", null, softTrack);
        check("进度环轨道不再用 outlineSoft（白底 1.7:1，P1-3）",
                scanned > 0 && softTrack.isEmpty(),
                "扫描 " + scanned + " 个文件，命中：\n" + String.join("\n", softTrack));
        List<String> outlineTrack = new ArrayList<>();
        scanSourceText(srcRoot, "drawCircle(UiColors.outline,", null, outlineTrack);
        check("进度环轨道改用 outline（P1-3）", !outlineTrack.isEmpty(),
                "环轨道没找到 drawCircle(UiColors.outline,");
        check("outline 于白底 ≥3:1（V-07 图标/描边下限）",
                ColorTokens.contrastRatio(ColorTokens.OUTLINE, ColorTokens.SURFACE_WHITE) >= 3.0,
                "实际 " + number(ColorTokens.contrastRatio(ColorTokens.OUTLINE, ColorTokens.SURFACE_WHITE)));
    }

    /**
     * N3（#265）：扫描细线底与进度环轨道同属「用长度表达进度」的图形，按 `V-07` ≥3:1 判。
     * 旧底色 `outlineSoft`（白底 1.70:1）不达标，改 `outline`（白底 4.47:1）。
     */
    static void scanLineCases() {
        File lib = new File(System.getProperty("user.dir"),
                "src/com/rea/mtpimport/ui/LibraryScreen.kt");
        String src = null;
        try {
            src = Files.readString(lib.toPath());
        } catch (IOException e) {
            src = null;
        }
        check("扫描细线底不再用 outlineSoft（白底 1.70:1，#265）",
                src != null && !src.contains(".background(UiColors.outlineSoft)"),
                src == null ? "读不到 " + lib : "仍在用 outlineSoft");
        check("扫描细线底改用 outline（#265）",
                src != null && src.contains(".background(UiColors.outline)"),
                src == null ? "读不到 " + lib : "没找到 .background(UiColors.outline)");
        check("outline 于白底 ≥3:1（V-07 图形下限，#265）",
                ColorTokens.contrastRatio(ColorTokens.OUTLINE, ColorTokens.SURFACE_WHITE) >= 3.0,
                "实际 " + number(ColorTokens.contrastRatio(ColorTokens.OUTLINE, ColorTokens.SURFACE_WHITE)));
    }

    /**
     * P1-1（#44 复审）：USB 拔出只在传输态把顶栏连接指示置为断线，保留进度屏；
     * 新导入 / 完成等落点必须复位，进度与暂停回调不得把断线擦掉。
     */
    static void detachCases() {
        check("USB 拔出：传输态判定为需要标断线",
                UiState.detachMarksTransfer(UiState.State.IMPORTING)
                        && UiState.detachMarksTransfer(UiState.State.PAUSED),
                "传输态未判为断线目标");
        check("USB 拔出：非传输态不标断线",
                java.util.Arrays.stream(UiState.State.values())
                        .filter(s -> !UiState.isTransfer(s))
                        .noneMatch(UiState::detachMarksTransfer),
                "非传输态被判为断线目标");
        UiTransition.Result detach = UiTransition.resolve(
                UiTransition.Action.USB_DETACHED, false, false, false, false);
        check("USB 拔出：置 connectionLost", detach.connectionLost, "未置位");
        check("USB 拔出：不换状态（保留进度屏）", detach.state == null,
                "实际 " + detach.state);
        check("USB 拔出后进度回调不清断线",
                UiTransition.resolve(UiTransition.Action.PROGRESS, false, false, false, true)
                        .connectionLost,
                "PROGRESS 把 connectionLost 复位了");
        check("USB 拔出后暂停回调不清断线",
                UiTransition.resolve(UiTransition.Action.PAUSED, false, false, false, true)
                        .connectionLost,
                "PAUSED 把 connectionLost 复位了");
        // B3（2026-11-08 产品方追认）：Resume 是继续同一轮、不是新导入，不得清断线；
        // 新导入仍走 IMPORT_START 复位（下一条）。
        UiTransition.Result resumed = UiTransition.resolve(
                UiTransition.Action.RESUME, false, false, false, true);
        check("暂停后 Resume：保留 connectionLost（B3）", resumed.connectionLost,
                "RESUME 把 connectionLost 复位了");
        check("暂停后 Resume：回到 IMPORTING 且重开传输视图",
                resumed.state == UiState.State.IMPORTING && !resumed.transferViewClosed,
                "实际 " + resumed.state + " / closed=" + resumed.transferViewClosed);
        check("暂停后 Resume：收起退出确认框", !resumed.exitConfirmOpen,
                "RESUME 未收起确认框");
        check("在线时 Resume 不凭空置断线",
                !UiTransition.resolve(UiTransition.Action.RESUME, false, false, false, false)
                        .connectionLost,
                "RESUME 在未断线时置了 connectionLost");
        check("新导入复位断线",
                !UiTransition.resolve(UiTransition.Action.IMPORT_START, false, false, false, true)
                        .connectionLost,
                "IMPORT_START 未复位 connectionLost");
        check("完成复位断线",
                !UiTransition.resolve(UiTransition.Action.DONE, false, false, false, true)
                        .connectionLost,
                "DONE 未复位 connectionLost");
        // B1（第三轮评审）：确认插线到索尼设备才清断线；状态与其余跨状态字段不变。
        UiTransition.Result attached = UiTransition.resolve(
                UiTransition.Action.USB_ATTACHED, false, true, true, true);
        check("插线确认：传输态复位 connectionLost 且状态不变",
                !attached.connectionLost && attached.state == null,
                "lost=" + attached.connectionLost + " state=" + attached.state);
        check("插线确认：不动视图关闭 / 确认框 / 扫描标志",
                attached.transferViewClosed && attached.exitConfirmOpen && !attached.backgroundScanning,
                "closed=" + attached.transferViewClosed + " confirm=" + attached.exitConfirmOpen
                        + " scan=" + attached.backgroundScanning);
        // B2（第三轮评审）：断线跨视图保留，只有插线 / 新导入 / 完成三处复位。
        check("回照片墙（BACK_READY）保留断线",
                UiTransition.resolve(UiTransition.Action.BACK_READY, true, false, false, true)
                        .connectionLost,
                "BACK_READY 把 connectionLost 复位了");
        check("离开传输视图（LEAVE_VIEW）保留断线",
                UiTransition.resolve(UiTransition.Action.LEAVE_VIEW, true, false, false, true)
                        .connectionLost,
                "LEAVE_VIEW 把 connectionLost 复位了");
        check("回启动页（BACK_COLD）保留断线",
                UiTransition.resolve(UiTransition.Action.BACK_COLD, true, false, false, true)
                        .connectionLost,
                "BACK_COLD 把 connectionLost 复位了");
    }

    // ---------------- 传输中扫描准入的日志文案（#269） ----------------

    /**
     * #269：`requestScan` 的 `NOTHING_TRANSFERRING` 分支只打日志，**不取消、不重扫**。
     * 旧文案「先取消再重扫」会让真机诊断把「只打日志」误读成真做了取消与重扫；
     * 行为保持不变，只把文案改成如实描述。
     */
    static void requestScanLogCases() {
        File main = new File(System.getProperty("user.dir"), "src/com/rea/mtpimport/Main.java");
        String src;
        try {
            src = Files.readString(main.toPath());
        } catch (IOException e) {
            src = null;
        }
        check("传输中扫描准入：日志不再声称取消与重扫（#269）",
                src != null && !src.contains("先取消再重扫"),
                src == null ? "读不到 " + main : "旧文案仍在");
        check("传输中扫描准入：日志如实写明本轮不打断（#269）",
                src != null && src.contains("本轮不打断"),
                src == null ? "读不到 " + main : "新文案缺失");
    }

    // ---------------- 颜色 token 与对比度（PR-2 / issue #78） ----------------

    /**
     * 守住 `ColorTokens` 的数值与派生关系。UI-SPEC §3.3 / V-07 的实算值，
     * 任何一条改坏都会让下面至少一个用例红（变异实验见 issue #78 的 plan）。
     */
    static void colorTokensCases() {
        check("ColorTokens：白色页面底是 #ffffff（不再是 canvas）",
                ColorTokens.SURFACE_WHITE == 0xFFFFFFFF, "实际 " + hex(ColorTokens.SURFACE_WHITE));
        check("ColorTokens：canvas 专指画布黑 #1d1d1f",
                ColorTokens.CANVAS == 0xFF1D1D1F, "实际 " + hex(ColorTokens.CANVAS));
        check("ColorTokens：ink 是 #111111（只作主 CTA 底）",
                ColorTokens.INK == 0xFF111111, "实际 " + hex(ColorTokens.INK));
        checkContrast("#ffffff on ink（主 CTA 文字）", ColorTokens.SURFACE_WHITE, ColorTokens.INK, 18.88);
        checkContrast("#ffffff on canvas（photo 页胶囊/浅色图标）", ColorTokens.SURFACE_WHITE, ColorTokens.CANVAS, 16.83);
        checkContrast("on-surface on #ffffff（正文）", ColorTokens.ON_SURFACE, ColorTokens.SURFACE_WHITE, 17.12);
        checkContrast("on-surface on surface-container（计数胶囊）",
                ColorTokens.ON_SURFACE, ColorTokens.SURFACE_CONTAINER, 14.75);
        checkContrast("on-surface-variant on #ffffff（次级文字）",
                ColorTokens.ON_SURFACE_VARIANT, ColorTokens.SURFACE_WHITE, 9.37);
        checkContrast("outline on #ffffff（图标/描边下限）", ColorTokens.OUTLINE, ColorTokens.SURFACE_WHITE, 4.47);
        double inkOnCanvas = ColorTokens.contrastRatio(ColorTokens.INK, ColorTokens.CANVAS);
        check("ink on canvas 实算 1.12:1", Math.abs(inkOnCanvas - 1.12) <= 0.05,
                "实际 " + number(inkOnCanvas));
        check("ink on canvas 低于 3:1（深色面禁用文字近黑）", inkOnCanvas < 3.0,
                "实际 " + number(inkOnCanvas));

        check("Pill 深色底 = canvas 实底（不再是白色 alpha）",
                ColorTokens.pillBackground(true) == ColorTokens.CANVAS,
                "实际 " + hex(ColorTokens.pillBackground(true)));
        check("Pill 深色前景 = #ffffff",
                ColorTokens.pillForeground(true) == ColorTokens.SURFACE_WHITE,
                "实际 " + hex(ColorTokens.pillForeground(true)));
        checkContrast("Pill 深色：白字压 canvas", ColorTokens.pillForeground(true), ColorTokens.pillBackground(true), 16.83);
        check("Pill 浅色底 = surface-container #eeeeee",
                ColorTokens.pillBackground(false) == ColorTokens.SURFACE_CONTAINER,
                "实际 " + hex(ColorTokens.pillBackground(false)));
        check("Pill 浅色前景 = on-surface #1a1c1c",
                ColorTokens.pillForeground(false) == ColorTokens.ON_SURFACE,
                "实际 " + hex(ColorTokens.pillForeground(false)));
        checkContrast("Pill 浅色：on-surface 压 surface-container",
                ColorTokens.pillForeground(false), ColorTokens.pillBackground(false), 14.75);

        // alpha 面按当前 token 重算：遮罩 40% canvas 压白 = #a5a5a5（装饰，不承载正文）；
        // 未选格灰块 60% surface-container 压白 = #f5f5f5（文件名 secondary 仍有 8.6:1）。
        int scrimOnWhite = ColorTokens.compositeOver(ColorTokens.SCRIM, ColorTokens.SURFACE_WHITE);
        check("遮罩 40% canvas 合成 = #a5a5a5（装饰面）", scrimOnWhite == 0xFFA5A5A5, "实际 " + hex(scrimOnWhite));
        int cellOnWhite = ColorTokens.compositeOver(0x99EEEEEE, ColorTokens.SURFACE_WHITE);
        check("未选格 60% surface-container 合成 = #f5f5f5", cellOnWhite == 0xFFF5F5F5, "实际 " + hex(cellOnWhite));
        check("次级文字压未选格灰块 ≥4.5:1",
                ColorTokens.contrastRatio(ColorTokens.ON_SURFACE_VARIANT, cellOnWhite) >= 4.5,
                "实际 " + number(ColorTokens.contrastRatio(ColorTokens.ON_SURFACE_VARIANT, cellOnWhite)));
    }

    static void checkContrast(String name, int fg, int bg, double expected) {
        double actual = ColorTokens.contrastRatio(fg, bg);
        check(name + " 实算 " + number(expected) + ":1",
                Math.abs(actual - expected) <= 0.05, "实际 " + number(actual));
    }

    static String number(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    static String hex(int argb) {
        return String.format(java.util.Locale.ROOT, "#%08X", argb);
    }

    // ---------------- 系统返回键路由（BackNav，issue #48） ----------------

    static void backNavCases() {
        check("IMPORTING 返回键弹 Pause & exit 确认框",
                BackNav.decide(UiState.State.IMPORTING, false, false, false, false)
                        == BackNav.Action.OPEN_EXIT_CONFIRM,
                "实际 " + BackNav.decide(UiState.State.IMPORTING, false, false, false, false));
        check("PAUSED 返回键一按回照片墙",
                BackNav.decide(UiState.State.PAUSED, false, false, false, false)
                        == BackNav.Action.BACK_TO_CATALOG,
                "实际 " + BackNav.decide(UiState.State.PAUSED, false, false, false, false));
        check("DONE 返回键回照片墙而不是退出应用（issue #48 主症状）",
                BackNav.decide(UiState.State.DONE, false, false, false, false)
                        == BackNav.Action.BACK_TO_CATALOG,
                "实际 " + BackNav.decide(UiState.State.DONE, false, false, false, false));
        check("ERROR 返回键回照片墙（§1.3 不丢选择）",
                BackNav.decide(UiState.State.ERROR, false, false, false, false)
                        == BackNav.Action.BACK_TO_CATALOG,
                "实际 " + BackNav.decide(UiState.State.ERROR, false, false, false, false));
        check("UNAVAILABLE 返回键回照片墙",
                BackNav.decide(UiState.State.UNAVAILABLE, false, false, false, false)
                        == BackNav.Action.BACK_TO_CATALOG,
                "实际 " + BackNav.decide(UiState.State.UNAVAILABLE, false, false, false, false));
        check("CATALOG_READY 是主页，返回键退出应用",
                BackNav.decide(UiState.State.CATALOG_READY, false, false, false, false)
                        == BackNav.Action.EXIT,
                "实际 " + BackNav.decide(UiState.State.CATALOG_READY, false, false, false, false));
        check("启动态（COLD/PERMISSION/SCANNING/EMPTY）仍返回退出",
                BackNav.decide(UiState.State.COLD, false, false, false, false) == BackNav.Action.EXIT
                        && BackNav.decide(UiState.State.PERMISSION_PENDING, false, false, false, false)
                        == BackNav.Action.EXIT
                        && BackNav.decide(UiState.State.SCANNING, false, false, false, false)
                        == BackNav.Action.EXIT
                        && BackNav.decide(UiState.State.EMPTY_CATALOG, false, false, false, false)
                        == BackNav.Action.EXIT,
                "启动态返回口径不符");
        check("浮层优先：确认框 > 过滤 > 详情 > 照片",
                BackNav.decide(UiState.State.DONE, true, true, true, true)
                        == BackNav.Action.CLOSE_EXIT_CONFIRM
                        && BackNav.decide(UiState.State.DONE, false, true, true, true)
                        == BackNav.Action.CLOSE_FILTER
                        && BackNav.decide(UiState.State.DONE, false, false, true, true)
                        == BackNav.Action.CLOSE_DETAILS
                        && BackNav.decide(UiState.State.DONE, false, false, false, true)
                        == BackNav.Action.CLOSE_PHOTO,
                "浮层优先级不符");
        check("所有非主页终态都不走 EXIT（issue #48 防复发）",
                nonExitStates(),
                "出现 EXIT 的状态：" + nonExitStatesDetail());
    }

    /** 非主页终态集合：这些状态下系统返回键不得退出应用。 */
    static boolean nonExitStates() {
        for (UiState.State s : new UiState.State[]{
                UiState.State.PAUSED, UiState.State.DONE,
                UiState.State.ERROR, UiState.State.UNAVAILABLE}) {
            if (BackNav.decide(s, false, false, false, false) == BackNav.Action.EXIT) return false;
        }
        return true;
    }

    static String nonExitStatesDetail() {
        StringBuilder out = new StringBuilder();
        for (UiState.State s : new UiState.State[]{
                UiState.State.PAUSED, UiState.State.DONE,
                UiState.State.ERROR, UiState.State.UNAVAILABLE}) {
            if (BackNav.decide(s, false, false, false, false) == BackNav.Action.EXIT) {
                if (out.length() > 0) out.append(", ");
                out.append(s.name());
            }
        }
        return out.toString();
    }

    // ---------------- 数字与短文案（UiText，C-05 / C-08 / U-45） ----------------

    static void uiTextCases() {
        long mb = 1024L * 1024L;
        long gb = mb * 1024L;

        check("capacity: 0 → 0 MB", "0 MB".equals(UiText.capacity(0)), "实际 " + UiText.capacity(0));
        check("capacity: 1 MB 整数不带小数", "1 MB".equals(UiText.capacity(mb)), "实际 " + UiText.capacity(mb));
        check("capacity: 1.5 MB 保留 1 位", "1.5 MB".equals(UiText.capacity(mb + mb / 2)),
                "实际 " + UiText.capacity(mb + mb / 2));
        check("capacity: 284 MB（组头口径）", "284 MB".equals(UiText.capacity(284L * mb)),
                "实际 " + UiText.capacity(284L * mb));
        check("capacity: ≥100 MB 取整", "858 MB".equals(UiText.capacity(858L * mb)),
                "实际 " + UiText.capacity(858L * mb));
        check("capacity: 999 MB 仍是 MB", UiText.capacity(999L * mb).endsWith(" MB"),
                "实际 " + UiText.capacity(999L * mb));
        check("capacity: 1 GB → 2 位小数", "1.00 GB".equals(UiText.capacity(gb)),
                "实际 " + UiText.capacity(gb));
        long twelvePoint75Gb = 13_690_208_256L;
        check("capacity: 12.75 GB → 2 位小数", "12.75 GB".equals(UiText.capacity(twelvePoint75Gb)),
                "实际 " + UiText.capacity(twelvePoint75Gb));
        check("capacity: 小容量不出现 0.00 GB", !UiText.capacity(50L * mb).contains("GB"),
                "实际 " + UiText.capacity(50L * mb));
        check("capacity: 负数按 0 处理", "0 MB".equals(UiText.capacity(-1)), "实际 " + UiText.capacity(-1));

        check("groupHeader 是组内聚合且用中点分隔",
                "Jun 7 \u00B7 12 \u00B7 284 MB".equals(UiText.groupHeader("Jun 7", 12, 284L * mb)),
                "实际 " + UiText.groupHeader("Jun 7", 12, 284L * mb));

        check("importCta 未选 → Import all N（All 档 512，U-53/#83）",
                "Import all 512".equals(UiText.importCta(0, 512)),
                "实际 " + UiText.importCta(0, 512));
        check("importCta 已选 → Import N（分母仍是可见总量，C-08）",
                "Import 2".equals(UiText.importCta(2, 512)),
                "实际 " + UiText.importCta(2, 512));
        check("importCta RAW 档未选 → Import all 511（U-53/#83）",
                "Import all 511".equals(UiText.importCta(0, 511)),
                "实际 " + UiText.importCta(0, 511));

        check("percent 12/36 → 33 且与 a/b 自洽", UiText.percent(12, 36) == 33,
                "实际 " + UiText.percent(12, 36));
        check("percent 边界 0 与满", UiText.percent(0, 36) == 0 && UiText.percent(36, 36) == 100,
                "实际 " + UiText.percent(0, 36) + "/" + UiText.percent(36, 36));
        check("percent 分母为 0 不除零", UiText.percent(5, 0) == 0, "实际 " + UiText.percent(5, 0));
        // #266：未传完不得显示 100%。文件计数口径下 511/512 会 round 成 100，
        // 最后一个 RAW 还在传时环内就报「已完成」，误导可持续数秒（U-45 追加断言）。
        check("percent 511/512 未传完钉 99，不显示 100%", UiText.percent(511, 512) == 99,
                "实际 " + UiText.percent(511, 512));
        check("percentText 511/512 → `99%`", "99%".equals(UiText.percentText(511, 512)),
                "实际 " + UiText.percentText(511, 512));
        check("percent 199/200（99.5%）未传完也钉 99", UiText.percent(199, 200) == 99,
                "实际 " + UiText.percent(199, 200));
        check("percent 200/200 传完才 100", UiText.percent(200, 200) == 100,
                "实际 " + UiText.percent(200, 200));
        check("percentText 带百分号", "33%".equals(UiText.percentText(12, 36)),
                "实际 " + UiText.percentText(12, 36));
        check("fraction 格式 a/b", "12/36".equals(UiText.fraction(12, 36)),
                "实际 " + UiText.fraction(12, 36));
        check("scanCounter 只给进度分母", "320/1526".equals(UiText.scanCounter(320, 1526)),
                "实际 " + UiText.scanCounter(320, 1526));

        // issue #53：P-07 / U-21 要求续传跳过数进 Details 抽屉；字面量在这里钉住。
        check("skippedLine 形如 `Skipped N existing`（P-07 / U-21）",
                "Skipped 12 existing".equals(UiText.skippedLine(12)),
                "实际 " + UiText.skippedLine(12));
        check("skippedLine 0 不显示（不得伪装「跳过 0」）",
                "".equals(UiText.skippedLine(0)),
                "实际 " + UiText.skippedLine(0));
        check("skippedLine 负数按 0 处理", "".equals(UiText.skippedLine(-3)),
                "实际 " + UiText.skippedLine(-3));

        // issue #211：逐文件跳过 / 取消 / 字节对账 / 同尺寸跳过 / 本轮摘要都收进 UiText。
        check("fileSkippedNote 与 skippedLine 同一词表（P-07 / U-21）",
                "Skipped existing".equals(UiText.fileSkippedNote()),
                "实际 " + UiText.fileSkippedNote());
        check("transferCancelled 句写明「从 0 重来」",
                "已取消，整个半成品已删除（从 0 重来）".equals(UiText.transferCancelled()),
                "实际 " + UiText.transferCancelled());
        check("sizeMismatch 带期望与实际",
                "字节数不符：期望 1024，实际 1000".equals(UiText.sizeMismatch(1024, 1000)),
                "实际 " + UiText.sizeMismatch(1024, 1000));
        check("sameSizeExists 必须写明「内容未校验」（#177）",
                UiText.sameSizeExists().contains("内容未校验")
                        && UiText.sameSizeExists().contains("未覆盖"),
                "实际 " + UiText.sameSizeExists());
        check("runSummary 形如 `完成 7 / 跳过 2 / 失败 1（共 10 个）`",
                "完成 7 / 跳过 2 / 失败 1（共 10 个）".equals(UiText.runSummary(7, 2, 1, 10, "")),
                "实际 " + UiText.runSummary(7, 2, 1, 10, ""));
        check("runSummary 带停止原因与中点分隔",
                "完成 1 / 跳过 0 / 失败 0（共 1 个） · 已取消".equals(UiText.runSummary(1, 0, 0, 1, "已取消")),
                "实际 " + UiText.runSummary(1, 0, 0, 1, "已取消"));

        String summary = UiText.doneSummary(36, 858L * mb);
        check("doneSummary 形如 `36 · 858 MB`（U-47）", summary.matches("\\d+ \u00B7 \\d+ MB"),
                "实际 " + summary);
        check("doneSummary GB 口径也合法",
                UiText.doneSummary(511, (long) (11.89 * gb)).matches("\\d+ \u00B7 \\d+\\.\\d{2} GB"),
                "实际 " + UiText.doneSummary(511, (long) (11.89 * gb)));

        check("rate 3.4 MB/s", "3.4 MB/s".equals(UiText.rate(3_567_104L)),
                "实际 " + UiText.rate(3_567_104L));
        check("rate 0 不除零", "0 MB/s".equals(UiText.rate(0)), "实际 " + UiText.rate(0));
        check("eta 有值且带 ~ 与 min", !UiText.eta(1000L * mb, 3_567_104L).isEmpty()
                        && UiText.eta(1000L * mb, 3_567_104L).startsWith("~"),
                "实际 " + UiText.eta(1000L * mb, 3_567_104L));

        check("monthDay 六月七日", "Jun 7".equals(UiText.monthDay(2026, 6, 7)),
                "实际 " + UiText.monthDay(2026, 6, 7));
        check("monthDay 月份越界返回 null", UiText.monthDay(2026, 13, 1) == null,
                "实际 " + UiText.monthDay(2026, 13, 1));

        check("deviceName 空值退回 Camera",
                "Camera".equals(UiText.deviceName(null)) && "Camera".equals(UiText.deviceName("  "))
                        && "a7C".equals(UiText.deviceName("a7C")),
                "实际 " + UiText.deviceName(null));
    }

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
        File srcRoot = new File(System.getProperty("user.dir"), "src");
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
        File srcRoot = new File(System.getProperty("user.dir"), "src");
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
        File srcRoot = new File(System.getProperty("user.dir"), "src");
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

    // ---------------- 假数据模式（DemoTree，U-54） ----------------

    static int countWithArw(PhotoWall wall) {
        int n = 0;
        for (PhotoWall.Unit u : wall.units()) if (u.hasArw()) n++;
        return n;
    }

    static int countWithJpg(PhotoWall wall) {
        int n = 0;
        for (PhotoWall.Unit u : wall.units()) if (u.hasJpg()) n++;
        return n;
    }

    static List<PhotoWall.Unit> unitsWithArw(PhotoWall wall) {
        List<PhotoWall.Unit> out = new ArrayList<>();
        for (PhotoWall.Unit u : wall.units()) if (u.hasArw()) out.add(u);
        return out;
    }

    static void demoTreesCases() {
        Catalog demo = DemoTree.catalog();
        int folders = 0;
        int arw = 0;
        int jpeg = 0;
        long bytes = 0;
        long rawBytes = 0;
        for (Catalog.Entry e : demo.all()) {
            if (e.isFolder()) folders++;
            else if (e.isRaw()) {
                arw++;
                rawBytes += e.size;
            } else jpeg++;
            bytes += e.size;
        }
        check("假树 1526 个对象", demo.all().size() == DemoTree.OBJECT_COUNT,
                "实际 " + demo.all().size());
        check("假树 503 个文件夹", folders == DemoTree.FOLDER_COUNT, "实际 " + folders);
        check("假树 511 张 ARW", arw == DemoTree.ARW_COUNT, "实际 " + arw);
        check("假树 512 张 JPEG", jpeg == DemoTree.JPEG_COUNT, "实际 " + jpeg);
        check("假树合计 12.75 GB", bytes == DemoTree.TOTAL_BYTES,
                "实际 " + bytes);
        check("ARW 合计 11.89 GB", rawBytes == DemoTree.RAW_BYTES,
                "实际 " + rawBytes);

        PhotoWall wall = PhotoWall.of(demo);
        // issue #83：照片墙按照片单元计，512 个单元 = 511 双格式 + 1 仅 JPG。
        check("假树 512 个照片单元、511 个含 ARW（#83）",
                wall.photoCount() == 512 && countWithArw(wall) == 511
                        && countWithJpg(wall) == 512,
                "units=" + wall.photoCount() + " arw=" + countWithArw(wall)
                        + " jpg=" + countWithJpg(wall));
        check("All 档空勾选 CTA = Import all 512（#83）",
                "Import all 512".equals(UiText.importCta(0, wall.photoCount())),
                "实际 " + UiText.importCta(0, wall.photoCount()));

        List<PhotoWall.Unit> rawUnits = unitsWithArw(wall);
        ImportPlan allPlan = ImportPlan.of(demo, new Selection(demo), wall.units(),
                PhotoWall.Export.JPG_FIRST);
        ImportPlan rawPlan = ImportPlan.of(demo, new Selection(demo), rawUnits,
                PhotoWall.Export.RAW);
        ImportPlan jpgPlan = ImportPlan.of(demo, new Selection(demo), wall.units(),
                PhotoWall.Export.JPG);
        check("All 档计划 512 个文件 / 923,408,256 字节（#83）",
                allPlan.count() == DemoTree.JPEG_COUNT
                        && allPlan.totalBytes() == DemoTree.JPEG_BYTES,
                "count=" + allPlan.count() + " bytes=" + allPlan.totalBytes());
        check("RAW 档计划 511 个 ARW / 12,766,800,000 字节（#83）",
                rawPlan.count() == DemoTree.ARW_COUNT
                        && rawPlan.totalBytes() == DemoTree.RAW_BYTES,
                "count=" + rawPlan.count() + " bytes=" + rawPlan.totalBytes());
        check("JPG 档计划 512 个 JPG / 923,408,256 字节（#83）",
                jpgPlan.count() == DemoTree.JPEG_COUNT
                        && jpgPlan.totalBytes() == DemoTree.JPEG_BYTES,
                "count=" + jpgPlan.count() + " bytes=" + jpgPlan.totalBytes());

        check("假树 12 个日期组", wall.groups().size() == 12, "实际 " + wall.groups().size());
        PhotoWall.Group jun7 = null;
        for (PhotoWall.Group g : wall.groups()) {
            if ("Jun 7".equals(g.label)) jun7 = g;
        }
        check("Jun 7 组 43 个单元（U-53）",
                jun7 != null && jun7.count() == 43, "实际 " + (jun7 == null ? "null" : jun7.count()));
        check("Jun 7 组头 43 个单元 / 74.0 MB（U-53）",
                jun7 != null && ("Jun 7 \u00B7 43 \u00B7 74.0 MB")
                        .equals(jun7.header(PhotoWall.Export.JPG_FIRST)),
                "实际 " + (jun7 == null ? "null" : jun7.header(PhotoWall.Export.JPG_FIRST)));

        for (UiState.State s : UiState.State.values()) {
            check("每个状态都有假载荷：" + s.name(), DemoTree.payload(s) != null, "载荷为 null");
        }

        DemoPayload importing = DemoTree.payload(UiState.State.IMPORTING);
        check("IMPORTING 环内 33% 与环下 12/36 自洽",
                "33%".equals(importing.percentText()) && "12/36".equals(importing.fractionText())
                        && UiText.percent(12, 36) == 33,
                "实际 " + importing.percentText() + " / " + importing.fractionText());
        check("IMPORTING 还有未传字节", importing.remainingBytes() > 0,
                "实际 " + importing.remainingBytes());

        DemoPayload paused = DemoTree.payload(UiState.State.PAUSED);
        check("PAUSED 数值冻结（与 IMPORTING 相同）",
                "33%".equals(paused.percentText()) && "12/36".equals(paused.fractionText()),
                "实际 " + paused.percentText() + " / " + paused.fractionText());

        DemoPayload done = DemoTree.payload(UiState.State.DONE);
        check("DONE 是 36 张 + 容量", done.donePhotos == 36
                        && UiText.doneSummary(done.donePhotos, done.resultBytes).matches("\\d+ \u00B7 \\d+(.\\d)? MB"),
                "实际 " + UiText.doneSummary(done.donePhotos, done.resultBytes));

        DemoPayload scanning = DemoTree.payload(UiState.State.SCANNING);
        check("SCANNING 分母是 1526（C-09）", scanning.scanTotal == 1526
                        && "320/1526".equals(UiText.scanCounter(scanning.scanned, scanning.scanTotal)),
                "实际 " + scanning.scanned + "/" + scanning.scanTotal);

        DemoPayload error = DemoTree.payload(UiState.State.ERROR);
        check("ERROR 原因 ≤20 字且不提 Retry", error.errorReason.length() <= 20
                        && !error.errorReason.contains("Retry") && !error.errorReason.contains("重试"),
                "实际「" + error.errorReason + "」长度 " + error.errorReason.length());
        // ERROR 必须带假树：否则 `--es demo_state ERROR` 冷启动后按返回只能落到 COLD，
        // 「回照片墙、选择还在」（§1.3）在唯一可用载体上不成立（PR #41 第三轮评审 P2）。
        check("ERROR 载荷带假树（返回能回照片墙）", error.catalog != null,
                "catalog=" + error.catalog);

        // issue #46：demo 照片预览必须按 handle 轮转映射，没有素材时给出 -1
        check("demo 预览按 handle 轮转映射",
                DemoTree.previewIndex(600, 5) == 0
                        && DemoTree.previewIndex(601, 5) == 1
                        && DemoTree.previewIndex(605, 5) == 0,
                "实际 " + DemoTree.previewIndex(600, 5) + "/" + DemoTree.previewIndex(601, 5)
                        + "/" + DemoTree.previewIndex(605, 5));
        check("无素材时预览下标为 -1", DemoTree.previewIndex(600, 0) == -1,
                "实际 " + DemoTree.previewIndex(600, 0));
    }

    // ---------------- demo 传输模拟（issue #46） ----------------

    static void demoTransferCases() {
        long batchBytes = DemoTree.arwSize() * DemoTree.BATCH_PHOTOS;
        DemoTransfer transfer = new DemoTransfer(DemoTree.BATCH_PHOTOS, batchBytes);

        check("demo 传输从 0 开始", transfer.doneFiles() == 0 && transfer.doneBytes() == 0,
                "实际 " + transfer.doneFiles() + "/" + transfer.doneBytes());
        check("demo 传输第一次推进 1 张且字节 > 0",
                !transfer.advance() && transfer.doneFiles() == 1
                        && transfer.doneBytes() > 0 && transfer.doneBytes() < batchBytes,
                "实际 " + transfer.doneFiles() + "/" + transfer.doneBytes());

        long previous = transfer.doneBytes();
        int ticks = 1;
        boolean finished = false;
        while (!finished && ticks < 100) {
            finished = transfer.advance();
            ticks++;
            if (transfer.doneBytes() <= previous) break;
            previous = transfer.doneBytes();
        }
        check("demo 传输逐 tick 推进到 36 张", transfer.doneFiles() == DemoTree.BATCH_PHOTOS && finished,
                "实际 " + transfer.doneFiles() + " 张，finished=" + finished);
        check("demo 传输完成时字节等于总字节", transfer.doneBytes() == batchBytes,
                "实际 " + transfer.doneBytes() + " / " + batchBytes);
        check("demo 传输到顶后再推进不变",
                transfer.advance() && transfer.doneFiles() == DemoTree.BATCH_PHOTOS
                        && transfer.doneBytes() == batchBytes,
                "实际 " + transfer.doneFiles() + "/" + transfer.doneBytes());
    }

    // ---------------- 样本与工具 ----------------

    /**
     * <pre>
     * 0 (root)
     * ├─ 1 DCIM/                    folder
     * │   ├─ 2 100MSDCF/            folder
     * │   │   ├─ 3 DSC02183.ARW     300
     * │   │   └─ 4 DSC02183.JPG     100
     * │   └─ 6 DSC02999.ARW         50    （直接在 DCIM 下，测非两层的路径）
     * └─ 5 C0001.MP4                60    （顶层文件）
     * </pre>
     */
    static Catalog sample() {
        Catalog c = new Catalog();
        c.add(new Catalog.Entry(1, 0, Catalog.FMT_FOLDER, 0, "DCIM"));
        c.add(new Catalog.Entry(2, 1, Catalog.FMT_FOLDER, 0, "100MSDCF"));
        c.add(new Catalog.Entry(3, 2, Catalog.FMT_ARW, 300, "DSC02183.ARW"));
        c.add(new Catalog.Entry(4, 2, Catalog.FMT_JPEG, 100, "DSC02183.JPG"));
        c.add(new Catalog.Entry(6, 1, Catalog.FMT_ARW, 50, "DSC02999.ARW"));
        c.add(new Catalog.Entry(5, 0, Catalog.FMT_JPEG, 60, "C0001.JPG"));
        return c.build();
    }

    /**
     * #62 组选用例：两个日期目录，一个 2 张 ARW（另含 1 张同名 JPG），一个 1 张 ARW。
     */
    static Catalog groupSample() {
        Catalog c = new Catalog();
        c.add(new Catalog.Entry(1, 0, Catalog.FMT_FOLDER, 0, "20260607"));
        c.add(new Catalog.Entry(3, 1, Catalog.FMT_ARW, 300, "DSC02183.ARW"));
        c.add(new Catalog.Entry(4, 1, Catalog.FMT_ARW, 100, "DSC02184.ARW"));
        c.add(new Catalog.Entry(5, 1, Catalog.FMT_JPEG, 50, "DSC02183.JPG"));
        c.add(new Catalog.Entry(6, 0, Catalog.FMT_FOLDER, 0, "20260608"));
        c.add(new Catalog.Entry(8, 6, Catalog.FMT_ARW, 60, "DSC02190.ARW"));
        return c.build();
    }

    /**
     * 造一棵「每个文件夹下挂若干个文件」的大树，用于截断计数用例。
     * 所有对象直接挂在 0 号根或根文件夹下，深度优先顺序稳定。
     */
    static Catalog flatCatalog(int folders, int files) {
        Catalog c = new Catalog();
        for (int i = 0; i < folders; i++) {
            c.add(new Catalog.Entry(1000 + i, 0, Catalog.FMT_FOLDER, 0, String.format("D%04d", i)));
        }
        for (int i = 0; i < files; i++) {
            c.add(new Catalog.Entry(2000 + i, 1000 + (i % folders), Catalog.FMT_ARW, 24_000_000L,
                    String.format("P%04d.ARW", i)));
        }
        return c.build();
    }

    // ---------------- 注释里的「文件:行号」引用（issue #66） ----------------

    /** 源码注释里指向别的文件的 `文件:行号` 形态；行号会随改动漂移，只认内容坐标。 */
    private static final Pattern FILE_LINE_REF = Pattern.compile("\\.(java|kt):\\d+");

    /**
     * issue #66：三处注释用 `文件:行号` 指向别处，行号会随代码改动失效。
     *
     * 只锁 issue 点名的三个文件；全局扫描要先定豁免口径，另开单。
     * 文件缺失也判红——文件改名后断言不能静默失效。
     */
    static void commentRefCases() {
        List<String> files = List.of(
                "src/com/rea/mtpimport/Catalog.java",
                "src/com/rea/mtpimport/MtpSession.java",
                "src/com/rea/mtpimport/ui/AppModel.kt");
        StringBuilder hits = new StringBuilder();
        for (String rel : files) {
            File f = new File(System.getProperty("user.dir"), rel);
            if (!f.isFile()) {
                hits.append(rel).append("（文件不存在）\n");
                continue;
            }
            try {
                for (String line : Files.readAllLines(f.toPath())) {
                    if (FILE_LINE_REF.matcher(line).find()) {
                        hits.append(rel).append(": ").append(line.trim()).append('\n');
                    }
                }
            } catch (IOException e) {
                hits.append(rel).append("（读取失败: ").append(e).append("）\n");
            }
        }
        check("注释不用 文件:行号 引用（issue #66）", hits.length() == 0, "命中：\n" + hits);
    }

    /**
     * issue #177 的源码级护栏：`MtpTransfer` 依赖 Android `MtpDevice`，JVM 用例覆盖不到
     * 它的读取循环，因此把「不许再按 offset 读、不许保留 append / `from == size` 直通、
     * 成功文案必须说明内容未校验」钉在源码上，避免后续改动悄悄把续传加回来。
     *
     * 只锁 `MtpTransfer` 这一个文件：它不在 G9 的拆分范围，文件级契约不会随重构误红。
     * 「不许按 offset 读」只约束**传输层**；预览读是另一条只读链路
     * （`ThumbnailStore.fetchFull` → `RangeRead`），不受这条断言影响。
     * `ImportService` 的对应行为（起点恒 0、账本完成还要对磁盘）改由
     * {@link #resumeDisabledCases()} 与 `SelfTest` 的「账本完成 + 磁盘截断」用例守着（issue #212）。
     */
    static void noResumeSourceCases() {
        File mtp = new File(System.getProperty("user.dir"), "src/com/rea/mtpimport/MtpTransfer.java");
        String src;
        try {
            src = Files.readString(mtp.toPath());
        } catch (IOException e) {
            src = null;
        }
        check("续传停用：MtpTransfer 不再按 offset 读相机对象（#177）",
                src != null && !src.contains("getPartialObject("),
                src == null ? "读不到 " + mtp : "仍调用 getPartialObject");
        check("续传停用：MtpTransfer 不再保留 append 或 from == size 直通（#177）",
                src != null && !src.contains("private Result append(")
                        && !src.contains("from == item.size"),
                src == null ? "读不到 " + mtp : "append 或尺寸直通仍在");
        check("完成口径：成功文案写明「内容未校验」（#177）",
                src != null && src.contains("尺寸核对通过") && src.contains("内容未校验"),
                src == null ? "读不到 " + mtp : "文案仍在暗示校验通过");
        check("完成口径：同尺寸已存在文件记 SKIPPED，不冒充 OK（#177）",
                src != null && src.contains("Kind.SKIPPED, UiText.sameSizeExists()")
                        && !src.contains("Kind.OK, UiText.sameSizeExists()"),
                src == null ? "读不到 " + mtp : "同尺寸分支仍可能记 OK");

    }

    /**
     * 「Skipped N existing」只能由 {@link UiText#skippedLine} 产出（issue #127）。
     *
     * 不锁具体文件名/方法名：`src/` 下出现拼接式字面量即判红，`Main` 拆到别的类也不会误红。
     * `skippedLine` 的取值行为（0 / 负数 / 正数）由 {@link #uiTextCases()} 断言（issue #212）。
     */
    static void skippedSourceCases() {
        File srcRoot = new File(System.getProperty("user.dir"), "src");
        List<String> hits = new ArrayList<>();
        int scanned = scanSourceText(srcRoot, "\"Skipped \" +", "src/com/rea/mtpimport/UiText.java", hits);
        check("Skipped 文案只有 UiText.skippedLine 一处拼接（issue #127）",
                scanned > 0 && hits.isEmpty(),
                "扫描 " + scanned + " 个文件，命中：\n" + String.join("\n", hits));

        List<String> uses = new ArrayList<>();
        // 收紧到「日志接线」这一处：`ui/AppModel.kt` 只是把数值塞进状态，不能替这条守卫兜底。
        int scannedUses = scanSourceText(srcRoot, "log.line(UiText.skippedLine(", null, uses);
        check("日志仍接在 UiText.skippedLine 上，不是删掉字面量了事（issue #127）",
                scannedUses > 0 && !uses.isEmpty(),
                "扫描 " + scannedUses + " 个文件，0 处调用");

        // doStart 内部「先清零再早退」的语句顺序断言已随 G9a 迁到 ImportEntry；
        // 由 {@link #importEntryCases()} 直接驱动行为（清零早于所有早退分支），不再扫源码。
    }

    /**
     * 同一句用户可见文案只能由 {@link UiText} 产出（issue #211 / G3）。
     *
     * 只锁「整句重复」的三族：取消、字节不符、同尺寸跳过，外加逐文件跳过 note；
     * `Failed: ` / `[Skipped]` 等状态前缀不在此列。扫描面是 `src/` 全目录，
     * 拆类或改名不会让守卫误红。
     */
    static void duplicateTextCases() {
        File srcRoot = new File(System.getProperty("user.dir"), "src");
        String allow = "src/com/rea/mtpimport/UiText.java";
        checkOnlyFromUiText(srcRoot, allow, "已取消，整个半成品已删除");
        checkOnlyFromUiText(srcRoot, allow, "字节数不符：期望 ");
        checkOnlyFromUiText(srcRoot, allow, "已存在同尺寸文件，未覆盖");
        checkOnlyFromUiText(srcRoot, allow, "Skipped existing");
    }

    /**
     * 导入入口的语句顺序（G9a / issue #127、#212）。
     *
     * 原来由 `skippedSourceCases` 的源码位置守卫守着；`Main.doStart` 的前置判定拆到
     * {@link ImportEntry} 后改成语义断言：跳过数清零必须早于所有早退分支，且 demo
     * 判定优先于「没有照片墙」（issue #46：demo 下 `Main.catalog` 恒为 null）。
     */
    static void importEntryCases() {
        List<String> events = new ArrayList<>();
        ImportEntry.Sink sink = new ImportEntry.Sink() {
            @Override
            public void resetSkipped() {
                events.add("clear");
            }

            @Override
            public void logLine(String line) {
                events.add("log:" + line);
            }
        };

        ImportEntry.Stage demo = ImportEntry.begin(sink, true, false);
        check("ImportEntry：demo 优先于「没有照片墙」，且先清零跳过数（#46 / #127）",
                demo == ImportEntry.Stage.DEMO && events.equals(Arrays.asList("clear")),
                "stage=" + demo + " events=" + events);

        events.clear();
        ImportEntry.Stage needsScan = ImportEntry.begin(sink, false, false);
        check("ImportEntry：没有照片墙时提示先扫描，清零仍早于早退（#127）",
                needsScan == ImportEntry.Stage.NEEDS_SCAN
                        && events.equals(Arrays.asList("clear", "log:先扫描")),
                "stage=" + needsScan + " events=" + events);

        events.clear();
        ImportEntry.Stage ready = ImportEntry.begin(sink, false, true);
        check("ImportEntry：有照片墙进入编排，不多打日志",
                ready == ImportEntry.Stage.READY && events.equals(Arrays.asList("clear")),
                "stage=" + ready + " events=" + events);
    }

    /** 记录界面投递的假 {@link ImportUi}：`post` 只入队，测试自己 `flush()` 执行。 */
    static final class RecordingUi implements ImportUi {
        final List<Runnable> posted = new ArrayList<>();
        int progressDone = -1;
        int progressTotal = -1;
        final List<String> failed = new ArrayList<>();
        int errors;
        final List<String> finished = new ArrayList<>();

        @Override
        public void post(Runnable r) {
            posted.add(r);
        }

        @Override
        public void updateProgress(int done, int total) {
            progressDone = done;
            progressTotal = total;
        }

        @Override
        public void addFailedLine(String line) {
            failed.add(line);
        }

        @Override
        public void showImportError() {
            errors++;
        }

        @Override
        public void finishRound(boolean cancelled, int ok, int skipped, int failedCount, long bytesDone) {
            finished.add(cancelled + "/" + ok + "/" + skipped + "/" + failedCount + "/" + bytesDone);
        }

        void flush() {
            List<Runnable> queue = new ArrayList<>(posted);
            posted.clear();
            for (Runnable r : queue) r.run();
        }
    }

    /**
     * 回调转发与迟到回调丢弃（G9a，从 `Main.doStart` 的匿名 `ImportListener` 抽出）。
     *
     * 重点是三件会静默出错的事：被放弃/换轮的迟到回调不得动界面、进度合并闸只留一个
     * 待执行更新、逐文件日志用 UI 词而不是内部枚举名。
     */
    static void importRoundListenerCases() {
        Catalog c = sample();
        Selection s = new Selection(c);
        s.setChecked(1, true);
        ImportPlan plan = ImportPlan.of(c, s, PhotoWall.of(c).units(), PhotoWall.Export.JPG_FIRST);
        ImportPlan.Item item = plan.items().get(0);

        RecordingUi ui = new RecordingUi();
        List<String> logs = new ArrayList<>();
        AtomicBoolean current = new AtomicBoolean(true);
        ImportRoundListener listener = new ImportRoundListener(logs::add, ui, current::get);

        listener.onPlanReady(plan);
        check("ImportRoundListener：计划日志带文件数与字节数",
                logs.size() == 1 && logs.get(0).contains(plan.count() + " 个文件 / " + plan.totalBytes()),
                "实际 " + logs);

        TransferProgress p1 = new TransferProgress(plan.totalBytes(), plan.count());
        p1.commitFile(10);
        listener.onProgress(p1, item);
        check("ImportRoundListener：首次进度投递一个更新，且记住 lastProgress",
                listener.lastProgress() == p1 && ui.posted.size() == 1,
                "posted=" + ui.posted.size());

        TransferProgress p2 = new TransferProgress(plan.totalBytes(), plan.count());
        p2.commitFile(10);
        p2.commitFile(10);
        listener.onProgress(p2, item);
        check("ImportRoundListener：已有待执行更新时不重复投递（合并闸）",
                ui.posted.size() == 1 && listener.lastProgress() == p2,
                "posted=" + ui.posted.size());
        ui.flush();
        check("ImportRoundListener：合并后的更新读最新进度",
                ui.progressDone == p2.filesDone() && ui.progressTotal == p2.totalFiles(),
                "done=" + ui.progressDone + " total=" + ui.progressTotal);

        current.set(false);
        TransferProgress p3 = new TransferProgress(plan.totalBytes(), plan.count());
        p3.commitFile(10);
        listener.onProgress(p3, item);
        listener.onFileFinished(item, Kind.FAILED, "被放弃的轮次");
        listener.onFinished(false, 0, 0, 1, "被放弃");
        listener.onError("被放弃", null);
        check("ImportRoundListener：换轮后迟到进度不改 lastProgress、不投递",
                listener.lastProgress() == p2 && ui.posted.isEmpty(),
                "posted=" + ui.posted.size());
        // 逐文件日志不受 current 影响：它只写日志，不动界面（原来也是先打日志再判 current）。
        check("ImportRoundListener：迟到文件回调仍留日志，但不进失败抽屉",
                logs.get(logs.size() - 1).startsWith("[Failed] ") && ui.failed.isEmpty(),
                "logs=" + logs + " failed=" + ui.failed);

        current.set(true);
        listener.onFileFinished(item, Kind.OK, "");
        listener.onFileFinished(item, Kind.SKIPPED, "已存在同尺寸文件，未覆盖");
        check("ImportRoundListener：逐文件日志用 UI 词，不出现内部枚举名",
                logs.get(logs.size() - 2).startsWith("[Done] ")
                        && logs.get(logs.size() - 1).startsWith("[Skipped] ")
                        && !logs.get(logs.size() - 2).contains("Kind."),
                "实际 " + logs);

        listener.onFileFinished(item, Kind.FAILED, "读失败");
        listener.onFinished(false, 1, 1, 1, "摘要");
        // 界面动作都是投递式：先执行队列，再核对（否则只看到「已投递」而不是「做了什么」）。
        ui.flush();
        check("ImportRoundListener：失败文件投递 Failed: 行（PD / #50）",
                ui.failed.size() == 1 && ui.failed.get(0).startsWith("Failed: " + item.relPath),
                "实际 " + ui.failed);
        check("ImportRoundListener：跳过数 > 0 才打 Skipped 日志（#127）",
                logs.stream().anyMatch(l -> l.equals(UiText.skippedLine(1))),
                "实际 " + logs);
        check("ImportRoundListener：收尾带上当前进度字节数",
                ui.finished.size() == 1 && ui.finished.get(0).equals("false/1/1/1/" + p2.bytesDone()),
                "实际 " + ui.finished);

        RecordingUi ui2 = new RecordingUi();
        ImportRoundListener zero = new ImportRoundListener(Log.NOOP::line, ui2, () -> true);
        zero.onFinished(false, 1, 0, 0, "摘要");
        check("ImportRoundListener：0 跳过不打「跳过 0」（#127）",
                ui2.posted.size() == 1,
                "实际 " + ui2.posted.size());
        ui2.flush();
        check("ImportRoundListener：没有进度时收尾字节数为 0",
                ui2.finished.size() == 1 && ui2.finished.get(0).endsWith("/0"),
                "实际 " + ui2.finished);

        check("ImportRoundListener：kindLabel 不直出内部枚举名（#177）",
                ImportRoundListener.kindLabel(Kind.OK).equals("Done")
                        && ImportRoundListener.kindLabel(Kind.SKIPPED).equals("Skipped")
                        && ImportRoundListener.kindLabel(Kind.FAILED).equals("Failed")
                        && ImportRoundListener.kindLabel(Kind.CANCELLED).equals("Cancelled"),
                "映射走偏");
    }

    /**
     * 被放弃的轮次闸门（G9a，三审 B-3）。
     *
     * 旧 worker 未退出前必须拦住重建会话；退出后自动放行；被 `abandon()` 的轮次
     * 不再覆写导入日志（否则会冲掉新一轮的 `import-journal.txt`）。
     */
    static void orphanRoundsCases() throws Exception {
        OrphanRounds rounds = new OrphanRounds();
        List<String> logs = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        OrphanRounds.Sink sink = new OrphanRounds.Sink() {
            @Override
            public void logLine(String line) {
                logs.add(line);
            }

            @Override
            public void showError(String message) {
                errors.add(message);
            }
        };

        check("OrphanRounds：没有登记时不拦",
                !rounds.stillRunning(sink) && rounds.current() == null,
                "current=" + rounds.current());

        Catalog c = sample();
        Selection s = new Selection(c);
        s.setChecked(3, true);
        s.setChecked(6, true);
        ImportPlan plan = ImportPlan.of(c, s, PhotoWall.of(c).units(), PhotoWall.Export.JPG_FIRST);

        File abandonedOut = Files.createTempDirectory("wiredraw-orphan-abandon").toFile();
        FakeTransfer hanging = new FakeTransfer(FakeTransfer.Mode.HANGING);
        ImportService stuck = new ImportService(Log.NOOP, hanging, abandonedOut);
        stuck.start(plan, new FinishRecorder());
        check("OrphanRounds：卡住的 worker 已进入传输", hanging.entered.await(5, TimeUnit.SECONDS), "等待超时");

        rounds.abandon(stuck);
        check("OrphanRounds：abandon 之后登记为被放弃轮次",
                rounds.current() == stuck, "current=" + rounds.current());
        check("OrphanRounds：worker 未退出时拦下重建会话（三审 B-3）",
                rounds.stillRunning(sink) && errors.equals(Collections.singletonList(StuckError.SESSION_LOST)),
                "errors=" + errors);
        check("OrphanRounds：拦下时留下一条可复现的日志",
                logs.size() == 1 && logs.get(0).contains("上一轮传输线程仍未退出"),
                "实际 " + logs);

        hanging.release.countDown();
        check("OrphanRounds：worker 退出后自动放行并清掉登记",
                stuck.awaitWorker(5000) && !rounds.stillRunning(sink) && rounds.current() == null,
                "仍活着或未清空");
        check("OrphanRounds：被放弃的轮次不写导入日志（不冲掉新一轮记录）",
                !new File(abandonedOut, "import-journal.txt").isFile(),
                "日志被写了");

        File adoptedOut = Files.createTempDirectory("wiredraw-orphan-adopt").toFile();
        FakeTransfer hanging2 = new FakeTransfer(FakeTransfer.Mode.HANGING);
        ImportService adopted = new ImportService(Log.NOOP, hanging2, adoptedOut);
        adopted.start(plan, new FinishRecorder());
        check("OrphanRounds：adopt 用例 worker 已进入传输",
                hanging2.entered.await(5, TimeUnit.SECONDS), "等待超时");
        rounds.adopt(adopted);
        check("OrphanRounds：adopt 只登记，不调 abandon()",
                !new File(adoptedOut, "import-journal.txt").isFile(),
                "还没收尾就出现日志");
        hanging2.release.countDown();
        check("OrphanRounds：adopt 的轮次正常收尾仍保存日志",
                adopted.awaitWorker(5000) && new File(adoptedOut, "import-journal.txt").isFile(),
                "日志未保存");
    }

    /**
     * 空间预检（G9a，从 `Main.refreshSpaceCheck` / `precheckSpace` 抽出）。
     *
     * 两段共用 `SpaceCheck` 的判据，但语义不同：`refresh` 只刷禁用态，计划为空时清态且
     * **不取磁盘数字**（原实现在 `plan.isEmpty()` 早退之后才 `StatFs`）；`precheck` 不足时
     * 返回 false 并落一行日志，调用方据此早退。
     */
    static void spaceGateCases() {
        List<String> statuses = new ArrayList<>();
        List<String> logs = new ArrayList<>();
        SpaceGate.Sink sink = new SpaceGate.Sink() {
            @Override
            public void setSpaceStatus(boolean low, String detail) {
                statuses.add(low + "/" + detail);
            }

            @Override
            public void logLine(String line) {
                logs.add(line);
            }
        };

        Catalog c = sample();
        Selection s = new Selection(c);
        // 空勾选会兜底成「当前可见集合」（issue #80），所以空计划只能用可见集合为空来构造：
        // 例如筛选后一个可导出文件都不剩（issue #83）。
        ImportPlan emptyPlan = ImportPlan.of(c, s, Collections.emptyList(), PhotoWall.Export.JPG_FIRST);
        check("SpaceGate：可见集合为空时计划为空", emptyPlan.isEmpty(), "实际非空");

        boolean[] asked = {false};
        SpaceGate.refresh(sink, emptyPlan, () -> {
            asked[0] = true;
            return 0L;
        });
        check("SpaceGate.refresh：空计划清禁用态，且不取磁盘数字",
                statuses.equals(Collections.singletonList("false/")) && !asked[0],
                "statuses=" + statuses + " asked=" + asked[0]);

        s.setChecked(1, true);
        ImportPlan plan = ImportPlan.of(c, s, PhotoWall.of(c).units(), PhotoWall.Export.JPG_FIRST);
        statuses.clear();
        SpaceGate.refresh(sink, plan, () -> 10_000_000L);
        check("SpaceGate.refresh：空间足够时不留 Low space",
                statuses.size() == 1 && statuses.get(0).equals("false/"),
                "实际 " + statuses);

        statuses.clear();
        SpaceGate.refresh(sink, plan, () -> 0L);
        check("SpaceGate.refresh：空间不足时落同口径差额",
                statuses.size() == 1 && statuses.get(0).equals("true/" + SpaceCheck.describe(0, plan.totalBytes())),
                "实际 " + statuses);

        statuses.clear();
        check("SpaceGate.precheck：空间足够返回 true 并清禁用态",
                SpaceGate.precheck(sink, plan, 10_000_000L) && statuses.equals(Collections.singletonList("false/")),
                "实际 " + statuses);

        statuses.clear();
        logs.clear();
        check("SpaceGate.precheck：空间不足返回 false，落 Low space 并记一行日志",
                !SpaceGate.precheck(sink, plan, 0L)
                        && statuses.equals(Collections.singletonList("true/" + SpaceCheck.describe(0, plan.totalBytes())))
                        && logs.equals(Collections.singletonList("空间不足：计划 " + plan.totalBytes() + " 字节，可用 0 字节")),
                "statuses=" + statuses + " logs=" + logs);
    }

    /** 断言 `needle` 只在 `allow` 一个文件里出现；其它文件命中即红。 */
    static void checkOnlyFromUiText(File srcRoot, String allow, String needle) {
        List<String> hits = new ArrayList<>();
        int scanned = scanSourceText(srcRoot, needle, allow, hits);
        check("文案只由 UiText 产出：" + needle, scanned > 0 && hits.isEmpty(),
                "扫描 " + scanned + " 个文件，命中：\n" + String.join("\n", hits));
    }

    // 命中行格式为「路径:行号 行内容」，hitLineOf 与 scanSourceText 共用这一约定。
    /**
     * 从 {@link #scanSourceText} 的命中行（`路径:行号 行内容`）取行号；解析失败返回 -1。
     * 非贪婪匹配第一段「冒号 + 数字 + 空白」，避开 Windows 盘符里的冒号与行内容里的数字。
     */
    static int hitLineOf(String hit) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^.+?:(\\d+)\\s").matcher(hit);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /**
     * 递归扫描 `src/` 下所有 .java/.kt 里含 `needle` 的行；`allow` 是唯一允许命中的文件（null = 不豁免）。
     *
     * 返回实际扫描到的文件数：`src/` 缺失或为空时返回 0，调用方必须判红，不能 fail-open。
     */
    static int scanSourceText(File dir, String needle, String allow, List<String> hits) {
        if (!dir.isDirectory()) return 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        int scanned = 0;
        for (File k : kids) {
            if (k.isDirectory()) {
                scanned += scanSourceText(k, needle, allow, hits);
                continue;
            }
            if (!k.getName().endsWith(".java") && !k.getName().endsWith(".kt")) continue;
            scanned++;
            String rel = k.getPath().replace('\\', '/');
            if (allow != null && rel.contains(allow)) continue;
            try {
                List<String> lines = Files.readAllLines(k.toPath());
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains(needle)) {
                        hits.add(rel + ":" + (i + 1) + " " + lines.get(i).trim());
                    }
                }
            } catch (IOException e) {
                hits.add(rel + "（读取失败: " + e + "）");
            }
        }
        return scanned;
    }

    // ---------------- 清单变化时的暂停轮取舍（issue #124） ----------------

    /**
     * #124 的取舍：空间不足时保留旧暂停轮，够才取消并按新清单重开。
     * 判定抽在 {@link PauseSwitch}，不再依赖 `doStart()` 的语句顺序（issue #212）。
     */
    static void pauseSwitchCases() {
        check("清单变化：空间不足保留暂停轮",
                PauseSwitch.of(false) == PauseSwitch.Action.KEEP_PAUSED,
                "实际 " + PauseSwitch.of(false));
        check("清单变化：空间足够才取消旧轮并按新清单重开",
                PauseSwitch.of(true) == PauseSwitch.Action.CANCEL_AND_RESTART,
                "实际 " + PauseSwitch.of(true));
    }

    // ---------------- 挂起与取消卡住的 ERROR 文案（issue #116） ----------------

    /**
     * issue #116 第三项：两种成因的屏幕 `ERROR` 文案必须可区分。
     * 文案搬进 {@link StuckError} 后直接断言取值，不再匹配 `doStart()` 源码文本（issue #212）。
     */
    static void stuckErrorCases() {
        String stalled = StuckError.message(StuckError.Kind.STALLED);
        String cancelStuck = StuckError.message(StuckError.Kind.CANCEL_STUCK);
        check("挂起与取消卡住的 ERROR 文案不同（issue #116）",
                !stalled.equals(cancelStuck) && cancelStuck.equals(StuckError.SESSION_LOST),
                "stalled=" + stalled + " cancelStuck=" + cancelStuck);
        // UI-SPEC：T-ERROR / C-01 一行 ≤20 字中文原因；C-14 必须带可执行动作；
        // §0.10 禁用写法把「传输 / 搬运 / 拷贝 / 同步」列入用户可见文案禁止项，Retry 一律禁止。
        check("挂起 ERROR 文案符合 C-01 / C-14 / §0.10（issue #116）",
                stalled.length() <= 20
                        && stalled.contains("拔插数据线")
                        && !stalled.contains("Retry") && !stalled.contains("重试")
                        && !stalled.contains("传输") && !stalled.contains("搬运")
                        && !stalled.contains("拷贝") && !stalled.contains("同步"),
                "实际「" + stalled + "」长度 " + stalled.length());
    }

    // ---------------- 跳过判据单一入口（#209 / G1） ----------------

    /**
     * 「可跳过」= 账本完成 **且** 磁盘成品尺寸一致；两个条件只在这里拼。
     * 账本完成但成品缺失 / 截断 / 路径越界都归 STALE（重新导入），不许静默跳过。
     */
    static void skipRuleCases() throws Exception {
        File out = Files.createTempDirectory("wiredraw-skiprule").toFile();
        String rel = "DCIM/100MSDCF/DSC02183.ARW";
        File dest = new File(out, "DCIM/100MSDCF/DSC02183.ARW");
        writeSizedFile(dest, 300);
        Journal j = new Journal();

        check("SkipRule：账本无记录 → TODO（#209）",
                SkipRule.check(j, out, rel, 300) == SkipRule.Verdict.TODO,
                "实际 " + SkipRule.check(j, out, rel, 300));

        j.markDone(3, 300, 300, rel);
        check("SkipRule：账本完成 + 磁盘同尺寸 → SKIP（#209）",
                SkipRule.check(j, out, rel, 300) == SkipRule.Verdict.SKIP,
                "实际 " + SkipRule.check(j, out, rel, 300));

        // #177：账本说完成，但成品被截断 → 必须重新导入。
        writeSizedFile(dest, 299);
        check("SkipRule：账本完成 + 成品截断 → STALE（#177）",
                SkipRule.check(j, out, rel, 300) == SkipRule.Verdict.STALE,
                "实际 " + SkipRule.check(j, out, rel, 300));

        // 成品整个不在 → 也必须重新导入。
        deleteTree(dest.getParentFile());
        check("SkipRule：账本完成 + 成品缺失 → STALE（#209）",
                SkipRule.check(j, out, rel, 300) == SkipRule.Verdict.STALE,
                "实际 " + SkipRule.check(j, out, rel, 300));

        // relPath 越界时 resolve 返回 null，不许因为账本有记录就跳过。
        Journal jTrav = new Journal();
        jTrav.markDone(9, 300, 300, "../../etc/passwd");
        check("SkipRule：relPath 越界 → STALE（#209）",
                SkipRule.check(jTrav, out, "../../etc/passwd", 300) == SkipRule.Verdict.STALE,
                "实际 " + SkipRule.check(jTrav, out, "../../etc/passwd", 300));

        // 同名但大小变了：账本主键含 size，新尺寸查不到记录 → TODO。
        check("SkipRule：同名但大小变了 → TODO（账本键含 size）",
                SkipRule.check(j, out, rel, 301) == SkipRule.Verdict.TODO,
                "实际 " + SkipRule.check(j, out, rel, 301));

        deleteTree(out);
    }

    // ---------------- 目录创建收口（G9b / A14，#219） ----------------

    /**
     * `Dirs.ensure` 是五处 `mkdirs` 的唯一入口：失败必须抛带路径的 IOException，
     * 不许像原来那样「建目录失败只记一条日志继续跑」。
     */
    static void dirsCases() throws Exception {
        File base = Files.createTempDirectory("wiredraw-dirs").toFile();

        // 已存在的目录直接返回，不重复建。
        Dirs.ensure(base);
        check("Dirs.ensure：已存在目录直接返回（#219）", base.isDirectory(), "实际 " + base);

        // 多层目录一次建成（Journal / .part 都依赖它）。
        File nested = new File(base, "DCIM/100MSDCF");
        Dirs.ensure(nested);
        check("Dirs.ensure：多层目录一次建成（#219）", nested.isDirectory(), "实际 " + nested);

        // null 不许静默：调用方要以 IOException 转成 Result / 日志 / UI 文案。
        boolean nullThrew = false;
        try {
            Dirs.ensure(null);
        } catch (IOException e) {
            nullThrew = true;
        }
        check("Dirs.ensure：null 目录抛 IOException（#219）", nullThrew, "未抛异常");

        // 同名文件占位 → mkdirs 必然失败；异常文案必须带路径，否则五个调用点无法定位。
        File blocker = new File(base, "blocker");
        writeSizedFile(blocker, 1);
        File bad = new File(blocker, "child");
        boolean badThrew = false;
        String msg = "";
        try {
            Dirs.ensure(bad);
        } catch (IOException e) {
            badThrew = true;
            msg = e.getMessage();
        }
        check("Dirs.ensure：建目录失败抛带路径的 IOException（#219）",
                badThrew && msg != null && msg.contains(bad.getName()), "实际 " + msg);

        deleteTree(base);
    }

    // ---------------- 轮次持久化外移（G9b / A2，#219） ----------------

    /**
     * `RoundFiles` 是 journal 与已导入索引的唯一读写入口；`ImportService.run` 只调它，
     * 不再自己拼 `outDir/import-journal.txt`、自己处理 `mkdirs`。
     */
    static void roundFilesCases() throws Exception {
        File root = Files.createTempDirectory("wiredraw-roundfiles").toFile();
        // 故意让 outDir 还不存在：save 必须自己把目录建出来（原来由 ImportService.saveJournal 负责）。
        File out = new File(root, "nested/deep");
        List<String> logs = new ArrayList<>();
        Log sink = logs::add;

        check("RoundFiles.journalFile：固定落在 outDir/import-journal.txt（#219）",
                new File(out, "import-journal.txt").equals(RoundFiles.journalFile(out)),
                "实际 " + RoundFiles.journalFile(out));

        Journal empty = RoundFiles.load(out, sink);
        check("RoundFiles.load：目录不存在按空日志、不抛异常（#219）",
                empty.size() == 0 && !empty.isComplete("DCIM/x.ARW", 300),
                "size=" + empty.size());

        Journal j = new Journal();
        j.markDone(3, 300, 300, "DCIM/100MSDCF/DSC02183.ARW");
        RoundFiles.save(out, j, sink);
        check("RoundFiles.save：父目录不存在时自己建目录（#219）",
                RoundFiles.journalFile(out).isFile(), "文件不存在");

        Journal back = RoundFiles.load(out, sink);
        check("RoundFiles.save/load：往返后账本可判完成、异 size 不算（#219）",
                back.size() == 1 && back.isComplete("DCIM/100MSDCF/DSC02183.ARW", 300)
                        && !back.isComplete("DCIM/100MSDCF/DSC02183.ARW", 301),
                "size=" + back.size());

        // 坏文件按空日志继续（坏行/坏头一律跳过，不让导片起不来）。
        writeSizedFile(RoundFiles.journalFile(out), 5);
        check("RoundFiles.load：损坏文件按空日志继续（#219）",
                RoundFiles.load(out, sink).size() == 0, "实际未按空日志");

        deleteTree(root);
    }

    // ---------------- relPath 兜底收口（G9b / A12，#219） ----------------

    /** `Entry.path()` 是 `relPath == null ? name : relPath` 的唯一出处，替代七处散落表达式。 */
    static void entryPathCases() {
        Catalog.Entry withPath = new Catalog.Entry(3, 2, Catalog.FMT_ARW, 300, "DSC02183.ARW");
        withPath.relPath = "DCIM/100MSDCF/DSC02183.ARW";
        Catalog.Entry noPath = new Catalog.Entry(5, 0, Catalog.FMT_JPEG, 60, "C0001.JPG");

        check("Entry.path：relPath 非空时用它（#219）",
                "DCIM/100MSDCF/DSC02183.ARW".equals(withPath.path()), "实际 " + withPath.path());
        check("Entry.path：relPath 为空时回退 name（#219）",
                "C0001.JPG".equals(noPath.path()), "实际 " + noPath.path());

        Catalog c = sample();
        PhotoWall.Unit u = PhotoWall.of(c).units().get(0);
        Catalog.Entry base = u.hasArw() ? u.arw : u.jpg;
        check("PhotoWall.Unit 的 relPath 与 Entry.path 同源（#219）",
                u.relPath.equals(base.path()), "unit=" + u.relPath + " path=" + base.path());
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name + "  —— " + detail);
            failures.append("  - ").append(name).append("：").append(detail).append('\n');
        }
    }

    static List<Integer> ints(int... v) {
        Integer[] boxed = Arrays.stream(v).boxed().toArray(Integer[]::new);
        return Arrays.asList(boxed);
    }
}
