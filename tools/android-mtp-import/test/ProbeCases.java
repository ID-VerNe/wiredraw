package com.rea.mtpimport;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.rea.mtpimport.TestSupport.*;
import static com.rea.mtpimport.Fixtures.*;
import static com.rea.mtpimport.TestFakes.*;
import static com.rea.mtpimport.TempDirs.*;

/** 探活判定、探活复核与无进展挂起的断言组（#318 从 SelfTest.java 拆出）。 */
final class ProbeCases {

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

        // 源码守卫（#281 结构化，不再钉死 receiver 字符串）：正向匹配任意 receiver 的
        // ioLock() 后紧跟无参 tryLock()；负向拦带参超时形式 tryLock(n, ...) 与任意
        // receiver 的阻塞 lock()——等锁时间重新进超时窗口或退回阻塞取锁都判红。
        File main = new File(System.getProperty("user.dir"), "src/com/rea/mtpimport/Main.java");
        String src;
        try {
            src = Files.readString(main.toPath());
        } catch (IOException e) {
            src = null;
        }
        boolean tryLockNoArg = false;
        boolean badLockForm = false;
        if (src != null) {
            tryLockNoArg = Pattern.compile("ioLock\\(\\)\\s*\\.\\s*tryLock\\(\\)").matcher(src).find();
            badLockForm = Pattern.compile("tryLock\\s*\\(\\s*[^)\\s]").matcher(src).find()
                    || Pattern.compile("\\.\\s*lock\\(\\)").matcher(src).find();
        }
        check("探活：Main 的探活用 ioLock().tryLock()，不用带参超时形式或阻塞 lock()（#268/#281）",
                tryLockNoArg && !badLockForm,
                src == null ? "读不到 " + main : "探活取锁形式不对（应为无参 tryLock()）");
    }

    // ---------------- 探活 busy 分支会话复核（issue #281） ----------------

    /**
     * #281（#273 评审跟进）三条各钉一个能红的断言：
     * 1. busy 分支持锁 IO 属旧会话时不得按活判 → 守卫 Main 的 busy 分支切片含 {@code session == s}；
     * 2. 生产调用 hasSession 不得恒真 → 守卫调用点切片由会话身份派生且不含 {@code alive(true,} 字面量；
     * 3. 源码守卫结构化（见 probeVerdictCases 的 regex 守卫），带参超时形式会被负向拦住。
     * 逻辑侧：无会话但锁被占用必须判死（hasSession 假优先于 lockBusy 真）。
     */
    static void probeBusyRecheckCases() {
        check("探活：无会话但 ioLock 被活跃 IO 占用 → 判死（#281）",
                !ProbeVerdict.alive(false, true, true, false, false), "误判为活");

        File main = new File(System.getProperty("user.dir"), "src/com/rea/mtpimport/Main.java");
        String src;
        try {
            src = Files.readString(main.toPath());
        } catch (IOException e) {
            src = null;
        }
        if (src == null) {
            check("探活：能读到 Main.java 源码做 #281 守卫", false, "读不到 " + main);
            return;
        }
        int busyIdx = src.indexOf("s.ioLock().tryLock()");
        String busySlice = busyIdx < 0 ? "" : src.substring(busyIdx, Math.min(src.length(), busyIdx + 300));
        check("探活：busy 分支持锁时复核会话身份 session == s（#281）",
                busySlice.contains("session == s"),
                "busy 分支未复核 session == s，旧会话在飞 IO 会被判活");

        int aliveIdx = src.indexOf("ProbeVerdict.alive(");
        String callSlice = aliveIdx < 0 ? ""
                : src.substring(Math.max(0, aliveIdx - 300), Math.min(src.length(), aliveIdx + 300));
        check("探活：hasSession 由调用点派生，不写字面 true（#281）",
                callSlice.contains("session == s && s.device() != null")
                        && !callSlice.contains("alive(true,"),
                "调用点 hasSession 未由会话身份派生，或仍写死 alive(true, ...)");
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
}
