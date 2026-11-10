package com.rea.mtpimport;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.rea.mtpimport.TestSupport.*;
import static com.rea.mtpimport.SourceGuard.*;

/** USB 身份、接入/权限门与实际会话取消的断言组（#318 从 SelfTest.java 拆出）。 */
final class DeviceCases {

    // ---------------- UsbIdentity（#267 的墓碑） ----------------

    /**
     * 墓碑：#267 —— 只认 VID 会把同厂商的 U 盘 / 读卡器当相机；DETACH 不比对身份会误置断线。
     *
     * 两条规则都必须能单独转红：把接口判定退化成「只看 VID」→ 第 3 条红；
     * 把身份比对改成恒 true → 第 7 条红。
     */
    static void usbIdentityCases() {
        int sony = UsbIdentity.SONY_VID;
        check("USB 身份：索尼 VID + Still Image 接口算相机",
                UsbIdentity.isSonyCamera(sony, new int[]{UsbIdentity.CLASS_STILL_IMAGE}), "被拒");
        check("USB 身份：索尼 VID + Vendor Specific 接口算相机",
                UsbIdentity.isSonyCamera(sony, new int[]{UsbIdentity.CLASS_VENDOR_SPECIFIC}), "被拒");
        check("USB 身份：索尼 VID + 只有 Mass Storage 接口不算相机（U 盘 / 读卡器）",
                !UsbIdentity.isSonyCamera(sony, new int[]{8}), "误判成相机");
        check("USB 身份：非索尼 VID 即便接口像相机也不算",
                !UsbIdentity.isSonyCamera(0x1234, new int[]{UsbIdentity.CLASS_STILL_IMAGE}),
                "误判成相机");
        check("USB 身份：拿不到接口信息时回退为只看 VID（不能把相机挡掉）",
                UsbIdentity.isSonyCamera(sony, new int[0]), "回退失效");
        check("USB 身份：拔掉的正是当前相机 → 置断线",
                UsbIdentity.shouldMarkDetached("1-1", "1-1"), "被忽略");
        check("USB 身份：拔掉的是无关设备 → 不得置断线",
                !UsbIdentity.shouldMarkDetached("1-2", "1-1"), "误置断线");
        check("USB 身份：广播未带 EXTRA_DEVICE → 按旧行为置断线",
                UsbIdentity.shouldMarkDetached(null, "1-1"), "被忽略");
        check("USB 身份：没有活动会话 → 交给状态守卫（本层不拦）",
                UsbIdentity.shouldMarkDetached("1-2", null), "被拦下");

        // #267 复审：纯函数再绿也钉不住「调用点被摘掉 / 被短路」。补两条源码守卫：
        // 1) DETACH 分支必须先过 `shouldMarkDetached(...)` 再调 `model.onUsbDetached()`；
        // 2) `MtpSession.findSony` 必须走 `isSonyCamera(...)`，且不得退回只比 VID 的旧写法。
        // 这两条是弱契约：只防上面两种具体回退，换行重排或改写表达式都会假红。
        File srcRoot = new File(System.getProperty("user.dir"), "src");
        List<String> detachCalls = new ArrayList<>();
        List<String> detachGuards = new ArrayList<>();
        scanSourceText(srcRoot, "model.onUsbDetached(", null, detachCalls);
        scanSourceText(srcRoot, "UsbIdentity.shouldMarkDetached(", null, detachGuards);
        int callLine = -1;
        int guardLine = -1;
        for (String h : detachCalls) if (h.contains("Main.java")) callLine = hitLineOf(h);
        for (String h : detachGuards) if (h.contains("Main.java")) guardLine = hitLineOf(h);
        check("USB 身份：DETACH 分支调用 model.onUsbDetached() 恰好一处（Main.java）",
                detachCalls.size() == 1 && callLine > 0,
                "命中 " + detachCalls.size() + " 处：\n" + String.join("\n", detachCalls));
        check("USB 身份：DETACH 分支先过 UsbIdentity.shouldMarkDetached(...) 再置断线（#267 复审）",
                guardLine > 0 && callLine > guardLine && callLine - guardLine <= 3,
                "守卫行 " + guardLine + "，调用行 " + callLine);

        List<String> findSonyCalls = new ArrayList<>();
        scanSourceText(srcRoot, "UsbIdentity.isSonyCamera(", null, findSonyCalls);
        List<String> vidOnly = new ArrayList<>();
        scanSourceText(srcRoot, "getVendorId() == SONY_VID) found", null, vidOnly);
        int findSonyInSession = 0;
        for (String h : findSonyCalls) if (h.contains("MtpSession.java")) findSonyInSession++;
        check("USB 身份：MtpSession.findSony 走 UsbIdentity.isSonyCamera(...)（#267 复审）",
                findSonyCalls.size() == 1 && findSonyInSession == 1,
                "命中 " + findSonyCalls.size() + " 处（MtpSession.java " + findSonyInSession + " 处）：\n"
                        + String.join("\n", findSonyCalls));
        check("USB 身份：MtpSession.findSony 不得退回只看 VID 的旧写法（#267 复审）",
                vidOnly.isEmpty(), "命中：\n" + String.join("\n", vidOnly));
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
}
