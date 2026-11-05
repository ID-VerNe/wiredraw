package com.rea.mtpimport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JVM 纯逻辑自检（不需要 Android SDK、不需要真机）。
 *
 * 覆盖 check.sh 的 `PURE` 清单里那些纯 JDK 类（Catalog / Selection / ImportPlan /
 * TransferProgress / Journal / AttachGate 等，清单本身是唯一事实来源）；
 * 它们不 import android.*，所以能在普通 JVM 里直接跑，这也是骨架里唯一
 * 「能拿门禁守住」的部分。真机相关的 MtpSession / CatalogScanner / ImportService
 * 不在本自检的覆盖范围（需要设备），见 README 的真机待验清单。
 *
 * 运行：bash tools/android-mtp-import/check.sh
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
        selectionCases();
        planCases();
        progressCases();
        journalCases();

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
