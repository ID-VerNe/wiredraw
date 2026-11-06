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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
        siblingJpegCases();
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
        journalCases();
        cancelTokenCases();
        importServiceCases();
        pauseCancelCases();
        partialResumeCases();
        uiStateCases();
        importGateCases();
        topBarSpecCases();
        colorTokensCases();
        backNavCases();
        uiTextCases();
        photoWallCases();
        demoTreesCases();
        demoTransferCases();

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

    // ---------------- 同名 JPG 回退（#59） ----------------

    /**
     * #59：ARW 的缩略图拿不到时回退到同目录同名 JPG。只做内存匹配，
     * 必须忽略大小写与扩展名、不得跨目录、不得把文件夹当成 JPG。
     */
    static void siblingJpegCases() {
        Catalog c = new Catalog();
        c.add(new Catalog.Entry(1, 0, Catalog.FMT_FOLDER, 0, "DCIM"));
        c.add(new Catalog.Entry(2, 1, Catalog.FMT_FOLDER, 0, "100MSDCF"));
        c.add(new Catalog.Entry(3, 2, Catalog.FMT_FOLDER, 0, "101MSDCF"));
        c.add(new Catalog.Entry(4, 2, Catalog.FMT_ARW, 300, "DSC02183.ARW"));
        c.add(new Catalog.Entry(5, 2, Catalog.FMT_JPEG, 100, "DSC02183.JPG"));
        c.add(new Catalog.Entry(6, 3, Catalog.FMT_JPEG, 100, "DSC02183.JPG"));
        c.add(new Catalog.Entry(7, 2, Catalog.FMT_ARW, 300, "dsc02190.arw"));
        c.add(new Catalog.Entry(8, 2, Catalog.FMT_JPEG, 100, "DSC02190.jpg"));
        // 同 stem 的 JPG 只存在于另一个目录：findSiblingJpeg 若遍历全库就会误命中，
        // 该用例是目录作用域的变异哨兵。
        c.add(new Catalog.Entry(9, 2, Catalog.FMT_ARW, 300, "DSC02199.ARW"));
        c.add(new Catalog.Entry(10, 3, Catalog.FMT_JPEG, 100, "DSC02199.JPG"));
        c.build();

        check("同名 JPG：同目录同 stem 命中",
                c.findSiblingJpeg(c.get(4)) != null && c.findSiblingJpeg(c.get(4)).handle == 5,
                "实际 " + (c.findSiblingJpeg(c.get(4)) == null ? "null" : c.findSiblingJpeg(c.get(4)).handle));
        check("同名 JPG：大小写与扩展名不敏感",
                c.findSiblingJpeg(c.get(7)) != null && c.findSiblingJpeg(c.get(7)).handle == 8,
                "实际 " + (c.findSiblingJpeg(c.get(7)) == null ? "null" : c.findSiblingJpeg(c.get(7)).handle));
        check("同名 JPG：不跨目录串号",
                c.findSiblingJpeg(c.get(9)) == null,
                "不该命中 101MSDCF 下的同名文件，实际 "
                        + (c.findSiblingJpeg(c.get(9)) == null ? "null" : c.findSiblingJpeg(c.get(9)).handle));
        check("同名 JPG：文件夹条目直接返回 null",
                c.findSiblingJpeg(c.get(2)) == null,
                "文件夹不应有同名 JPG 回退");
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
        check("组选：用例造出 2 张组与 1 张组", many != null && one != null,
                "groups=" + groups.size());

        check("组选：空组不算已全选", !s.allCheckedOf(List.of()),
                "空组返回 true");

        s.setChecked(many.photos.get(0).handle, true);
        check("组选：部分选中时 allCheckedOf 为 false", !s.allCheckedOf(many.photos),
                "实际 " + s.fileHandles());

        s.setCheckedAllOf(many.photos, true);
        check("组选：补齐后组内全部已勾", s.allCheckedOf(many.photos),
                "实际 " + s.fileHandles());
        check("组选：只作用于本组 ARW，不跨组", !s.isChecked(one.photos.get(0).handle),
                "1 张组被顺带勾上");
        check("组选：组内 JPG 不在组选范围", !s.isChecked(5),
                "实际 " + s.fileHandles());
        check("组选：重复全选不产生重复文件", s.fileHandles().size() == 2,
                "实际 " + s.fileHandles());

        s.setCheckedAllOf(many.photos, false);
        check("组选：已全选后取消清空本组",
                !s.allCheckedOf(many.photos) && s.fileHandles().isEmpty(),
                "实际 " + s.fileHandles());
    }

    // ---------------- ImportPlan ----------------

    static void planCases() {
        Catalog c = sample();
        Selection s = new Selection(c);
        s.setChecked(1, true);
        ImportPlan p = ImportPlan.of(c, s);

        check("计划条数与勾选文件数一致", p.count() == 3, "实际 " + p.count());
        check("计划总量按勾选累加", p.totalBytes() == 300 + 100 + 50, "实际 " + p.totalBytes());
        check("计划带 relPath 供落盘用",
                p.items().get(0).relPath.endsWith(".ARW"), "实际 " + p.items().get(0).relPath);

        // issue #80：空勾选必须等价于全选（UI-SPEC `T-01`「已选 0 → 应用内点一次即开始」/ `P-10`），
        // 而且只收 ARW —— 主 CTA 的分母是照片数（`C-08` / `U-53`），不能把同名 JPG / 顶层 JPG
        // 一起吞进计划（真机 511 ARW + 512 JPEG，收 JPG 会变成 1023 个文件）。
        // 旧断言「空勾选的计划为空且总量为 0」正是本缺陷的墓碑，改坏 `ImportPlan.of` 的兜底这里必须红。
        ImportPlan all = ImportPlan.of(c, new Selection(c));
        check("空勾选 = 全部 ARW（#80）",
                all.count() == c.rawCount() && all.totalBytes() == c.rawBytes(),
                "count=" + all.count() + " total=" + all.totalBytes()
                        + "，期望 count=" + c.rawCount() + " total=" + c.rawBytes());
        List<String> allPaths = new ArrayList<>();
        for (ImportPlan.Item item : all.items()) allPaths.add(item.relPath);
        check("空勾选只收 ARW，不含 JPG（#80）",
                allPaths.equals(List.of("DCIM/100MSDCF/DSC02183.ARW", "DCIM/DSC02999.ARW")),
                "实际 " + allPaths);
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

        check("humanBytes 用 1024 进制",
                "1.0 MB".equals(p.humanBytes(1048576)), "实际 " + p.humanBytes(1048576));

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

    // ---------------- Journal ----------------

    static void journalCases() throws RuntimeException {
        Journal j = new Journal();
        j.markDone(12345, 7340032, 24389632, "DCIM/100MSDCF/DSC02183.ARW");
        j.markDone(22222, 1024, 1024, "DCIM/100MSDCF/DSC02184.ARW");

        check("doneBytes 记住已落盘字节",
                j.doneBytes(12345) == 7340032, "实际 " + j.doneBytes(12345));
        check("未知句柄的 doneBytes 为 0", j.doneBytes(999) == 0, "实际 " + j.doneBytes(999));
        check("isComplete：已传满为 true",
                j.isComplete(22222, 1024), "实际 " + j.isComplete(22222, 1024));
        check("isComplete：未传满为 false",
                !j.isComplete(12345, 24389632), "实际 " + j.isComplete(12345, 24389632));
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

        check("resumeFrom：没有半成品 → 0", Journal.resumeFrom(0, 300) == 0,
                "实际 " + Journal.resumeFrom(0, 300));
        check("resumeFrom：部分落盘原样返回", Journal.resumeFrom(40, 300) == 40,
                "实际 " + Journal.resumeFrom(40, 300));
        check("resumeFrom：已满返回 size（可直接落盘）", Journal.resumeFrom(300, 300) == 300,
                "实际 " + Journal.resumeFrom(300, 300));
        check("resumeFrom：超过 size 视为损坏，回 0 重传", Journal.resumeFrom(301, 300) == 0,
                "实际 " + Journal.resumeFrom(301, 300));
        check("resumeFrom：size 未知 → 0", Journal.resumeFrom(10, 0) == 0,
                "实际 " + Journal.resumeFrom(10, 0));

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
                reloaded.doneBytes(12345) == 7340032 && reloaded.isComplete(22222, 1024),
                "done=" + reloaded.doneBytes(12345));
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
     * 假传输：只验证编排层（跳过、失败计数、进度、日志），不碰 android。
     * 真实 `MtpTransfer` 用 `MtpDevice.importFile`，只能在 ILCE-7C 真机上验。
     */
    static final class RecordingTransfer implements ResumableTransfer {
        final List<String> calls = new ArrayList<>();
        /** PB / PC：记录每次调用收到的 resumeFrom 与半成品路径。 */
        final List<Long> resumeFroms = new ArrayList<>();
        final List<String> parts = new ArrayList<>();
        boolean failAll;

        @Override
        public Result transfer(ImportPlan.Item item, String partPath, long resumeFrom, CancelToken token) {
            calls.add(item.relPath);
            resumeFroms.add(resumeFrom);
            parts.add(partPath);
            if (failAll) return new Result(Kind.FAILED, "fake failure");
            try {
                File part = new File(partPath);
                File parent = part.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    return new Result(Kind.FAILED, "mkdir failed");
                }
                try (FileOutputStream out = new FileOutputStream(part)) {
                    out.write(new byte[(int) Math.min(item.size, 16)]);
                }
                return Result.of(Kind.OK);
            } catch (IOException e) {
                return new Result(Kind.FAILED, e.toString());
            }
        }
    }

    /** 每个文件的第一次调用失败、第二次成功；用来钉住单文件同步重试（PD / issue #50）。 */
    static final class FlakyTransfer implements ResumableTransfer {
        final List<String> calls = new ArrayList<>();
        /** 每次调用收到的 resumeFrom，用来钉住「第二次按 `.part` 实际长度续传」。 */
        final List<Long> resumeFroms = new ArrayList<>();
        private final java.util.Set<String> seen = new java.util.HashSet<>();
        /** 首次失败前落盘的字节数；模拟失败时已经写进部分字节。0 表示不落盘。 */
        long firstFailureBytes;

        @Override
        public Result transfer(ImportPlan.Item item, String partPath, long resumeFrom, CancelToken token) {
            calls.add(item.relPath);
            resumeFroms.add(resumeFrom);
            // 同一个 relPath 首次失败，第二次成功（模拟一次偶发读失败）。
            if (seen.add(item.relPath)) {
                if (firstFailureBytes > 0) {
                    try {
                        File part = new File(partPath);
                        File parent = part.getParentFile();
                        if (parent != null && !parent.exists() && !parent.mkdirs()) {
                            return new Result(Kind.FAILED, "mkdir failed");
                        }
                        try (FileOutputStream out = new FileOutputStream(part)) {
                            out.write(new byte[(int) firstFailureBytes]);
                        }
                    } catch (IOException e) {
                        return new Result(Kind.FAILED, e.toString());
                    }
                }
                return new Result(Kind.FAILED, "fake first failure");
            }
            return writeFakePart(item, partPath);
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
        public Result transfer(ImportPlan.Item item, String partPath, long resumeFrom, CancelToken token) {
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
        boolean cancelled;
        int ok;
        int skipped;
        int failed;
        String summary;
        TransferProgress lastProgress;

        @Override
        public void onProgress(TransferProgress progress, ImportPlan.Item current) {
            lastProgress = progress;
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
        s.setChecked(1, true);          // DCIM → 3 / 4 / 6 三个文件
        ImportPlan plan = ImportPlan.of(c, s);

        File out = Files.createTempDirectory("wiredraw-import").toFile();
        RecordingTransfer t1 = new RecordingTransfer();
        FinishRecorder r1 = new FinishRecorder();
        new ImportService(Log.NOOP, t1, out).start(plan, r1);
        check("ImportService：一轮在 5 秒内结束", r1.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("ImportService：3 个文件全部 OK（不再有「未实现」）",
                r1.ok == 3 && r1.skipped == 0 && r1.failed == 0,
                "ok=" + r1.ok + " skipped=" + r1.skipped + " failed=" + r1.failed);
        check("ImportService：summary 不再出现「未实现」",
                r1.summary != null && !r1.summary.contains("未实现"), "实际 " + r1.summary);
        check("ImportService：进度按成功文件累加字节",
                r1.lastProgress != null && r1.lastProgress.bytesDone() == 450
                        && r1.lastProgress.filesDone() == 3,
                r1.lastProgress == null ? "无进度"
                        : r1.lastProgress.bytesDone() + " / " + r1.lastProgress.filesDone());
        Journal j = new Journal();
        check("ImportService：写出的 .part 落在 outDir 且按 relPath + size 命名（PC / #50）",
                new File(j.partPath(out.getAbsolutePath(), "DCIM/100MSDCF/DSC02183.ARW", 300)).isFile()
                        && new File(j.partPath(out.getAbsolutePath(), "DCIM/100MSDCF/DSC02183.JPG", 100)).isFile()
                        && new File(j.partPath(out.getAbsolutePath(), "DCIM/DSC02999.ARW", 50)).isFile(),
                "实际 " + Arrays.toString(out.list()));
        check("ImportService：日志文件写出 import-journal.txt",
                new File(out, "import-journal.txt").isFile(),
                "实际 " + Arrays.toString(out.list()));

        // 第二轮：同一目录 + 同一 plan，应该全部按 relPath + size 跳过，不再调 transfer
        RecordingTransfer t2 = new RecordingTransfer();
        FinishRecorder r2 = new FinishRecorder();
        new ImportService(Log.NOOP, t2, out).start(plan, r2);
        check("ImportService：第二轮 5 秒内结束", r2.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("ImportService：第二轮全部 SKIPPED 且不再调 transfer",
                r2.ok == 0 && r2.skipped == 3 && r2.failed == 0 && t2.calls.isEmpty(),
                "ok=" + r2.ok + " skipped=" + r2.skipped + " calls=" + t2.calls.size());

        // 全失败：新目录，不能把失败冒充成完成
        File badOut = Files.createTempDirectory("wiredraw-import-bad").toFile();
        RecordingTransfer t3 = new RecordingTransfer();
        t3.failAll = true;
        FinishRecorder r3 = new FinishRecorder();
        new ImportService(Log.NOOP, t3, badOut).start(plan, r3);
        check("ImportService：全失败一轮结束", r3.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("ImportService：全失败时 ok=0 / failed=3，不冒充完成",
                r3.ok == 0 && r3.failed == 3 && r3.skipped == 0,
                "ok=" + r3.ok + " failed=" + r3.failed + " skipped=" + r3.skipped);
        // PD / issue #50：重试必须有上限。全失败时每个文件恰好尝试 2 次（硬编码，
        // 不引用 MAX_FILE_ATTEMPTS，避免「断言跟着实现走」的自指）。
        check("PD 重试：全失败时每个文件恰好尝试 2 次（有上限）",
                t3.calls.size() == plan.count() * 2,
                "calls=" + t3.calls.size() + " 期望=" + (plan.count() * 2));

        // PD：首次失败、第二次成功的文件仍算完成，且尝试次数有上限。
        File flakyOut = Files.createTempDirectory("wiredraw-import-flaky").toFile();
        FlakyTransfer t4 = new FlakyTransfer();
        FinishRecorder r4 = new FinishRecorder();
        new ImportService(Log.NOOP, t4, flakyOut).start(plan, r4);
        check("PD 重试：偶发失败一轮在 5 秒内结束", r4.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("PD 重试：首次失败、第二次成功仍算完成",
                r4.ok == 3 && r4.failed == 0 && r4.skipped == 0,
                "ok=" + r4.ok + " failed=" + r4.failed);
        check("PD 重试：每个文件恰好尝试 2 次（首次 + 1 次同步重试）",
                t4.calls.size() == plan.count() * 2,
                "calls=" + t4.calls.size());

        // PD / B-2：首次失败已落 40 字节，第二次 attempt 必须按 `.part` 实际长度重算
        // resumeFrom（40），不能沿用 0 或只算一次。
        File partialOut = Files.createTempDirectory("wiredraw-import-partial").toFile();
        FlakyTransfer t5 = new FlakyTransfer();
        t5.firstFailureBytes = 40L;
        FinishRecorder r5 = new FinishRecorder();
        new ImportService(Log.NOOP, t5, partialOut).start(plan, r5);
        check("PD 重试：落部分字节的一轮在 5 秒内结束", r5.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("PD 重试：落部分字节后第二次仍算完成",
                r5.ok == 3 && r5.failed == 0 && r5.skipped == 0,
                "ok=" + r5.ok + " failed=" + r5.failed + " skipped=" + r5.skipped);
        boolean resumesOk = t5.resumeFroms.size() == plan.count() * 2;
        for (int i = 0; resumesOk && i < t5.resumeFroms.size(); i += 2) {
            resumesOk = t5.resumeFroms.get(i) == 0L && t5.resumeFroms.get(i + 1) == 40L;
        }
        check("PD 重试：第二次 attempt 按 .part 实际长度重算 resumeFrom（40 字节）",
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
     * 进入 transfer 后卡住，直到测试放行；用来钉住「暂停只落在文件边界」：
     * 暂停请求不能打断正在传的那个文件，但第二个文件必须等到 resume 才启动。
     */
    static final class GatedTransfer implements ResumableTransfer {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final List<Long> resumeFroms = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch firstEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final CountDownLatch secondEntered = new CountDownLatch(1);

        @Override
        public Result transfer(ImportPlan.Item item, String partPath, long resumeFrom, CancelToken token) {
            calls.add(item.relPath);
            resumeFroms.add(resumeFrom);
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
            return writeFakePart(item, partPath);
        }
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
        public Result transfer(ImportPlan.Item item, String partPath, long resumeFrom, CancelToken token) {
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

    /**
     * 模拟「取消动作打不断传输」：完全忽略取消令牌，直到测试放行才返回。
     * 用来钉住二审 B-1 的死路判定：worker 不退出时界面必须能判出「卡住」并给 ERROR 出路。
     */
    static final class StuckTransfer implements ResumableTransfer {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public Result transfer(ImportPlan.Item item, String partPath, long resumeFrom, CancelToken token) {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Result(Kind.FAILED, "测试放行");
        }
    }

    /** 和 RecordingTransfer 一样落一个假 .part，供 GatedTransfer 复用。 */
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
        s.setChecked(3, true);      // DCIM/100MSDCF/DSC02183.ARW（先）
        s.setChecked(4, true);      // DCIM/100MSDCF/DSC02183.JPG（后）
        ImportPlan plan = ImportPlan.of(c, s);
        check("PB 用例清单恰为 2 个文件", plan.count() == 2, "实际 " + plan.count());

        // 1) 暂停落在文件边界：在传的完成，下一个等 resume
        File out = Files.createTempDirectory("wiredraw-pb-pause").toFile();
        GatedTransfer g = new GatedTransfer();
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
        RecordingTransfer t2 = new RecordingTransfer();
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
        ImportService svc2 = new ImportService(Log.NOOP, ct, out2);
        svc2.start(plan, r3);
        check("PB 取消：第一个文件已进入传输",
                ct.entered.await(5, TimeUnit.SECONDS), "等待超时");
        svc2.cancel();
        check("PB 取消：取消动作在传输中被触发（不是只停界面）",
                ct.tokenCancelled.await(5, TimeUnit.SECONDS), "取消动作未触发");
        check("PB 取消：本轮 5 秒内结束", r3.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("PB 取消：cancelled=true 且取消的文件不计失败",
                r3.cancelled && r3.ok == 0 && r3.skipped == 0 && r3.failed == 0,
                "cancelled=" + r3.cancelled + " ok=" + r3.ok + " failed=" + r3.failed);
        check("PB 取消：只尝试了当前文件，后续文件未启动",
                ct.calls.size() == 1, "实际调用 " + ct.calls.size() + " 个文件");
        check("PB 取消：摘要标明已取消",
                r3.summary != null && r3.summary.contains("已取消"), "实际 " + r3.summary);

        RecordingTransfer t4 = new RecordingTransfer();
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
        check("PB 放弃：被放弃的轮次跳过续传日志保存（不覆盖磁盘上的现有记录）",
                !new File(out3, "import-journal.txt").exists(), "日志仍被写出");
        check("PB 卡死：worker 退出后不再判定卡死", !svc3.isCancelStuck(), "实际 true");
        check("PD onDestroy：worker 退出后 awaitWorker 返回 true",
                svc3.awaitWorker(5000), "实际返回 false");
        deleteTree(out3);
    }

    // ---------------- 断点续传起点（PC / issue #50） ----------------

    /**
     * 钉住编排层把「磁盘上的 `.part` 实际长度」作为 resumeFrom 传给传输层。
     *
     * 之前 `ImportService` 取 `journal.doneBytes(relPath,size)`：Journal 只在成功时写
     * `done = size`，失败轮次从不写部分进度，所以 resumeFrom 恒为 0，真续传不可能发生。
     */
    static void partialResumeCases() throws Exception {
        Catalog c = sample();
        Selection s = new Selection(c);
        s.setChecked(3, true);      // DCIM/100MSDCF/DSC02183.ARW（300 字节）
        ImportPlan plan = ImportPlan.of(c, s);
        check("PC 续传：用例清单恰为 1 个文件", plan.count() == 1, "实际 " + plan.count());

        // 1) 上一轮失败留下 40 字节半成品 → 本轮应从 40 续传，且半成品名按 relPath 稳定生成
        File out = Files.createTempDirectory("wiredraw-pc-resume").toFile();
        Journal j = new Journal();
        String expectedPart = j.partPath(out.getAbsolutePath(), "DCIM/100MSDCF/DSC02183.ARW", 300);
        try (FileOutputStream o = new FileOutputStream(expectedPart)) {
            o.write(new byte[40]);
        }
        RecordingTransfer t1 = new RecordingTransfer();
        FinishRecorder r1 = new FinishRecorder();
        new ImportService(Log.NOOP, t1, out).start(plan, r1);
        check("PC 续传：一轮在 5 秒内结束", r1.done.await(5, TimeUnit.SECONDS), "等待超时");
        check("PC 续传：resumeFrom 取半成品实际长度 40（不再恒为 0）",
                t1.resumeFroms.size() == 1 && t1.resumeFroms.get(0) == 40L,
                "实际 " + t1.resumeFroms);
        check("PC 续传：传给传输层的是按 relPath + size 命名的半成品",
                t1.parts.size() == 1
                        && t1.parts.get(0).replace('\\', '/').equals(expectedPart.replace('\\', '/')),
                "实际 " + t1.parts);
        check("PC 续传：传完后按完成记账（第二轮应跳过）",
                r1.ok == 1 && r1.skipped == 0 && r1.failed == 0,
                "ok=" + r1.ok + " skipped=" + r1.skipped + " failed=" + r1.failed);
        // 第二轮：完成记录已落盘，按 relPath + size 跳过，不再调 transfer
        RecordingTransfer t2 = new RecordingTransfer();
        FinishRecorder r2 = new FinishRecorder();
        new ImportService(Log.NOOP, t2, out).start(plan, r2);
        check("PC 续传：完成后的第二轮全部 SKIPPED",
                r2.done.await(5, TimeUnit.SECONDS) && r2.skipped == 1 && t2.calls.isEmpty(),
                "skipped=" + r2.skipped + " calls=" + t2.calls.size());
        deleteTree(out);

        // 2) 半成品已满（== size）→ resumeFrom = size，由传输层直接落盘
        File fullOut = Files.createTempDirectory("wiredraw-pc-full").toFile();
        String fullPart = j.partPath(fullOut.getAbsolutePath(), "DCIM/100MSDCF/DSC02183.ARW", 300);
        try (FileOutputStream o = new FileOutputStream(fullPart)) {
            o.write(new byte[300]);
        }
        RecordingTransfer t3 = new RecordingTransfer();
        FinishRecorder r3 = new FinishRecorder();
        new ImportService(Log.NOOP, t3, fullOut).start(plan, r3);
        check("PC 续传：半成品已满时 resumeFrom == size（交给传输层落盘）",
                r3.done.await(5, TimeUnit.SECONDS)
                        && t3.resumeFroms.size() == 1 && t3.resumeFroms.get(0) == 300L,
                "实际 " + t3.resumeFroms);
        deleteTree(fullOut);

        // 3) 半成品比目标大（换了文件 / 写坏）→ 视为损坏，从 0 重传
        File badOut = Files.createTempDirectory("wiredraw-pc-bad").toFile();
        String badPart = j.partPath(badOut.getAbsolutePath(), "DCIM/100MSDCF/DSC02183.ARW", 300);
        try (FileOutputStream o = new FileOutputStream(badPart)) {
            o.write(new byte[301]);
        }
        RecordingTransfer t4 = new RecordingTransfer();
        FinishRecorder r4 = new FinishRecorder();
        new ImportService(Log.NOOP, t4, badOut).start(plan, r4);
        check("PC 续传：半成品超过目标大小 → resumeFrom 回 0（不拿坏文件续传）",
                r4.done.await(5, TimeUnit.SECONDS)
                        && t4.resumeFroms.size() == 1 && t4.resumeFroms.get(0) == 0L,
                "实际 " + t4.resumeFroms);
        deleteTree(badOut);

        // 4) 同一 relPath、不同 size 的旧半成品（B-1）：不能被当成本对象的前缀。
        //    旧前缀 L < 新 size S 时若不区分大小，会续写成「旧前缀 + 新尾部」且长度恰好对账通过。
        File staleOut = Files.createTempDirectory("wiredraw-pc-stale").toFile();
        String stalePart = j.partPath(staleOut.getAbsolutePath(), "DCIM/100MSDCF/DSC02183.ARW", 299);
        try (FileOutputStream o = new FileOutputStream(stalePart)) {
            o.write(new byte[40]);
        }
        RecordingTransfer t5 = new RecordingTransfer();
        FinishRecorder r5 = new FinishRecorder();
        new ImportService(Log.NOOP, t5, staleOut).start(plan, r5);
        check("PC 续传：异 size 的旧半成品不被认作本对象前缀（resumeFrom 仍为 0）",
                r5.done.await(5, TimeUnit.SECONDS)
                        && t5.resumeFroms.size() == 1 && t5.resumeFroms.get(0) == 0L,
                "实际 " + t5.resumeFroms);
        deleteTree(staleOut);
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
        check("顶栏在全部 10 态常驻（#44 决策 2）",
                java.util.Arrays.stream(UiState.State.values()).allMatch(UiState::hasTopBar),
                "任何状态都不得去掉顶栏；内容按 UI-SPEC §2.2 的方案 A 逐态表");
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
        check("capacity: ≥100 MB 取整", "871 MB".equals(UiText.capacity(871L * mb)),
                "实际 " + UiText.capacity(871L * mb));
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

        check("importCta 未选 → Import all N", "Import all 511".equals(UiText.importCta(0, 511)),
                "实际 " + UiText.importCta(0, 511));
        check("importCta 已选 → Import N", "Import 2".equals(UiText.importCta(2, 511)),
                "实际 " + UiText.importCta(2, 511));

        check("percent 12/36 → 33 且与 a/b 自洽", UiText.percent(12, 36) == 33,
                "实际 " + UiText.percent(12, 36));
        check("percent 边界 0 与满", UiText.percent(0, 36) == 0 && UiText.percent(36, 36) == 100,
                "实际 " + UiText.percent(0, 36) + "/" + UiText.percent(36, 36));
        check("percent 分母为 0 不除零", UiText.percent(5, 0) == 0, "实际 " + UiText.percent(5, 0));
        check("percentText 带百分号", "33%".equals(UiText.percentText(12, 36)),
                "实际 " + UiText.percentText(12, 36));
        check("fraction 格式 a/b", "12/36".equals(UiText.fraction(12, 36)),
                "实际 " + UiText.fraction(12, 36));
        check("scanCounter 只给进度分母", "320/1526".equals(UiText.scanCounter(320, 1526)),
                "实际 " + UiText.scanCounter(320, 1526));

        String summary = UiText.doneSummary(36, 871L * mb);
        check("doneSummary 形如 `36 · 871 MB`（U-47）", summary.matches("\\d+ \u00B7 \\d+ MB"),
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
        // 样本只有 2 张 ARW（100MSDCF 下 300 字节、DCIM 下 50 字节），JPEG 一律不进照片墙。
        check("照片墙只收 ARW：2 张", wall.photoCount() == 2, "实际 " + wall.photoCount());
        check("照片墙容量只算 ARW：350 B", wall.photoBytes() == 350, "实际 " + wall.photoBytes());
        check("按目录名分出 2 组", wall.groups().size() == 2, "实际 " + wall.groups().size());
        boolean jpegExcluded = true;
        for (PhotoWall.Group g : wall.groups()) {
            for (Catalog.Entry e : g.photos) {
                if (!e.isRaw()) jpegExcluded = false;
            }
        }
        check("组内不含 JPEG", jpegExcluded, "组内混入了非 ARW");

        long groupBytes = 0;
        int groupCount = 0;
        for (PhotoWall.Group g : wall.groups()) {
            groupBytes += g.bytes;
            groupCount += g.count();
        }
        check("组内聚合之和等于总量", groupBytes == wall.photoBytes() && groupCount == wall.photoCount(),
                "组和 " + groupBytes + "/" + groupCount);
        check("组头是组内聚合格式",
                wall.groups().get(0).header().matches(".+ \u00B7 \\d+ \u00B7 \\d+(.\\d)? MB"),
                "实际 " + wall.groups().get(0).header());
        check("目录名日期渲染成 Jun 7", "Jun 7".equals(PhotoWall.labelOf("20260607")),
                "实际 " + PhotoWall.labelOf("20260607"));
        check("非日期目录名原样保留", "100MSDCF".equals(PhotoWall.labelOf("100MSDCF")),
                "实际 " + PhotoWall.labelOf("100MSDCF"));
    }

    // ---------------- 假数据模式（DemoTree，U-54） ----------------

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
        check("假树 511 与 Import all 511 同分母",
                wall.photoCount() == 511 && "Import all 511".equals(UiText.importCta(0, wall.photoCount())),
                "实际 " + wall.photoCount());
        check("假树 12 个日期组", wall.groups().size() == 12, "实际 " + wall.groups().size());

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
