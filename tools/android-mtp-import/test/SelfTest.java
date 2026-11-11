package com.rea.mtpimport;

import static com.rea.mtpimport.TestSupport.*;
import static com.rea.mtpimport.CatalogCases.*;
import static com.rea.mtpimport.DeviceCases.*;
import static com.rea.mtpimport.SelectionCases.*;
import static com.rea.mtpimport.ProgressCases.*;
import static com.rea.mtpimport.ImportServiceCases.*;
import static com.rea.mtpimport.ConflictCases.*;
import static com.rea.mtpimport.ResumeCases.*;
import static com.rea.mtpimport.ProbeCases.*;
import static com.rea.mtpimport.UiStateCases.*;
import static com.rea.mtpimport.UiTextCases.*;
import static com.rea.mtpimport.WallCases.*;
import static com.rea.mtpimport.DemoCases.*;
import static com.rea.mtpimport.SourceGuardCases.*;
import static com.rea.mtpimport.RepoCases.*;
import static com.rea.mtpimport.TempDirCases.*;
import static com.rea.mtpimport.DestCases.*;

/**
 * JVM 纯逻辑自检入口（不需要真机；Gradle 配置阶段仍要能定位 Android SDK）。
 *
 * #318 起按子系统拆成 `test/*Cases.java`：本文件只保留 `main` 注册表与退出码，
 * 断言框架见 `TestSupport`，样本见 `Fixtures`，假实现见 `TestFakes`，
 * 源码文本护栏见 `SourceGuard`，临时目录清理见 `TempDirs`。
 * 注册顺序与拆前逐行一致，项数以运行输出末行为准。
 *
 * 运行：bash tools/android-mtp-import/gradlew -p tools/android-mtp-import selfTest
 */
public final class SelfTest {

    public static void main(String[] args) throws Exception {
        catalogCases();
        progressiveCases();
        progressiveGateCases();
        truncationCases();
        attachCases();
        sessionVisibilityCases();
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
        noCameraStatusCases();
        uiTransitionCases();
        finishOutcomeCases();
        probeVerdictCases();
        probeBusyRecheckCases();
        stalledDetectionCases();
        stalledServiceCases();
        importGateCases();
        topBarSpecCases();
        ringTrackCases();
        scanLineCases();
        detachCases();
        requestScanLogCases();
        noDeviceColdGuardCases();
        usbIdentityCases();
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
        locationChoiceCases();
        productDestinationCases();
        tempDirCases();

        System.out.println();
        if (failed == 0) {
            System.out.println("== 自检全绿：" + passed + " 项 ==");
            System.exit(0);
        }
        System.out.println("== 自检红 " + failed + " 项（绿 " + passed + " 项）==");
        System.out.print(failures);
        System.exit(1);
    }
}
