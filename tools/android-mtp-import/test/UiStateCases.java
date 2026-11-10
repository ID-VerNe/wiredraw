package com.rea.mtpimport;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.rea.mtpimport.TestSupport.*;
import static com.rea.mtpimport.SourceGuard.*;

/** 界面状态、迁移、CTA 准入与顶栏形态的断言组（#318 从 SelfTest.java 拆出）。 */
final class UiStateCases {

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
        // #285：NOTHING_NO_DEVICE 分支在传输态（IMPORTING / PAUSED）不得 showCold()。
        // 取「case NOTHING_NO_DEVICE:」到「case NOTHING_TRANSFERRING:」的切片，
        // 钉住守卫（UiState.isTransfer）与动作（showCold()）同时在场：缺守卫 = 暂停屏被打回，
        // 缺 showCold() = 非传输态失去回冷启动页的原有行为。
        if (src != null) {
            int noDevice = src.indexOf("case NOTHING_NO_DEVICE:");
            int transferring = src.indexOf("case NOTHING_TRANSFERRING:");
            String slice = noDevice < 0 || transferring < 0 || transferring < noDevice
                    ? "" : src.substring(noDevice, transferring);
            check("无设备回落：NOTHING_NO_DEVICE 分支含传输态守卫（#285）",
                    slice.contains("UiState.isTransfer"), "切片缺 UiState.isTransfer 守卫");
            check("无设备回落：NOTHING_NO_DEVICE 分支仍保留 showCold()（#285）",
                    slice.contains("showCold()"), "切片缺 showCold() 调用");
        } else {
            check("无设备回落：能读到 Main.java 源码（#285）", false, "读不到 " + main);
        }
    }

    // ---------------- 无关设备在场时 NOTHING_NO_DEVICE 的调用点守卫（issue #285） ----------------

    /**
     * 墓碑（#285）：钉住「危险在调用点」——{@code UiTransition} 表本身无状态守卫，
     * {@code Action.COLD} 从任何态（包括 PAUSED）解析都到 COLD。所以「PAUSED 不得回冷启动页」
     * 的守卫只能放在 Main 的 {@code case NOTHING_NO_DEVICE:} 调用点（见 requestScanLogCases
     * 的切片断言）；若有人把守卫挪进迁移表或删掉调用点守卫，迁移表语义不变、险情照旧。
     */
    static void noDeviceColdGuardCases() {
        UiTransition.Result r = UiTransition.resolve(UiTransition.Action.COLD, false, false, false);
        check("无设备回落：UiTransition 表无状态守卫（Action.COLD 恒到 COLD，故守卫必须在 Main 调用点，#285）",
                r.state == UiState.State.COLD, "实际 " + r.state);
    }
}
