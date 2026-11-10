package com.rea.mtpimport;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import com.rea.mtpimport.ResumableTransfer.Kind;

import static com.rea.mtpimport.TestSupport.*;
import static com.rea.mtpimport.Fixtures.*;
import static com.rea.mtpimport.SourceGuard.*;
import static com.rea.mtpimport.TempDirs.*;

/** 清单变化取舍、错误文案、跳过判据与目录收口等仓库约束的断言组（#318 从 SelfTest.java 拆出）。 */
final class RepoCases {

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
        withTempDir("wiredraw-skiprule", out -> {
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

            // 成品整个不在（文件与所在目录都删掉）→ 也必须重新导入。
            // 这里不用 deleteTree：它是临时目录的清理入口，用例里「删一个已有成品」是另一回事。
            Files.deleteIfExists(dest.toPath());
            Files.deleteIfExists(dest.getParentFile().toPath());
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
        });

    }

    // ---------------- 目录创建收口（G9b / A14，#219） ----------------

    /**
     * `Dirs.ensure` 是五处 `mkdirs` 的唯一入口：失败必须抛带路径的 IOException，
     * 不许像原来那样「建目录失败只记一条日志继续跑」。
     */
    static void dirsCases() throws Exception {
        withTempDir("wiredraw-dirs", base -> {

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
        });

    }

    // ---------------- 轮次持久化外移（G9b / A2，#219） ----------------

    /**
     * `RoundFiles` 是 journal 与已导入索引的唯一读写入口；`ImportService.run` 只调它，
     * 不再自己拼 `outDir/import-journal.txt`、自己处理 `mkdirs`。
     */
    static void roundFilesCases() throws Exception {
        withTempDir("wiredraw-roundfiles", root -> {
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
        });

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

    // ---------------- session 可见性（issue #280） ----------------

    /**
     * issue #280：`Main.session` 主线程读（DETACH 广播里的 `activeCameraName()` 身份比对）、
     * 扫描线程写（`session(...)` setter 与 `closeCancelledSession()` 的置 null），
     * 非 volatile 时主线程可能读到旧值 `null`，把无关设备的拔出误置断线。
     * 真并发可见性在 JVM 用例里无法可靠复现，这里钉住字段声明本身
     * （handoff #298 §三第 1 步批准的口径：源码守卫）。
     */
    static void sessionVisibilityCases() {
        File main = source("com/rea/mtpimport/Main.java");
        String src = readSourceOrNull(main);
        check("会话字段可见性：Main.session 声明为 volatile（#280）",
                src != null && Pattern.compile("private\\s+volatile\\s+MtpSession\\s+session;")
                        .matcher(src).find(),
                src == null ? "读不到 " + main : "session 声明缺 volatile");
    }
}
