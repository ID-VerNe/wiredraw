package com.rea.mtpimport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JVM 纯逻辑自检（不需要真机；Gradle 配置阶段仍要能定位 Android SDK）。
 *
 * 覆盖 build.gradle.kts 的 `pureJdkClasses` 清单里那些纯 JDK 类（Catalog / Selection / ImportPlan /
 * TransferProgress / Journal / AttachGate 等，清单本身是唯一事实来源）；
 * 它们不 import android.*，所以能在普通 JVM 里直接跑，这也是骨架里唯一
 * 「能拿门禁守住」的部分。真机相关的 MtpSession / CatalogScanner / ImportService
 * 不在本自检的覆盖范围（需要设备），见 README 的真机待验清单。
 *
 * 运行：bash tools/android-mtp-import/gradlew -p tools/android-mtp-import selfTest
 */
public final class SelfTest {

    static int passed = 0;
    static int failed = 0;
    static final StringBuilder failures = new StringBuilder();

    public static void main(String[] args) throws Exception {
        catalogCases();
        truncationCases();
        attachCases();
        permissionCases();
        allowCases();
        resolveCases();
        scanCancelCases();
        scanReleaseCases();
        selectionCases();
        planCases();
        progressCases();
        journalCases();
        uiStateCases();
        uiTextCases();
        photoWallCases();
        demoTreesCases();

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

        ImportPlan empty = ImportPlan.of(c, new Selection(c));
        check("空勾选的计划为空且总量为 0",
                empty.isEmpty() && empty.totalBytes() == 0,
                "isEmpty=" + empty.isEmpty() + " total=" + empty.totalBytes());
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
        check("partPath 以句柄命名、不抢最终文件名",
                j.partPath("/sdcard/out", 12345).replace('\\', '/')
                        .equals("/sdcard/out/12345.part"),
                "实际 " + j.partPath("/sdcard/out", 12345));

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
        check("扫描态不渲染顶栏（U-43：1526 只作分母）",
                !UiState.hasTopBar(UiState.State.SCANNING)
                        && !UiState.hasTopBar(UiState.State.DONE)
                        && UiState.hasTopBar(UiState.State.CATALOG_READY),
                "hasTopBar 判定不符");
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
        check("徽标 511 与 Import all 511 同分母",
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
