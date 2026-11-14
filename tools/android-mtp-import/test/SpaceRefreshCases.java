package com.rea.mtpimport;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import static com.rea.mtpimport.TestSupport.*;
import static com.rea.mtpimport.Fixtures.*;
import static com.rea.mtpimport.SourceGuard.*;

/**
 * 空间预检搬出 UI 线程的行为与结构护栏（#374 / IA-7）。
 *
 * 自检是纯 JDK，起不了 Activity，也**没有真相机**：demo 路径在 `refreshSpaceCheck`
 * 首行就 return，真实 USB 路径每点一次省下的 UI 线程时间只能明天在真机上量。
 * 所以这里分两条腿：
 * ① `SpaceRefresh` 的序号 / TTL / 销毁作废与 `Selection.snapshot()` 的隔离性，直接跑；
 * ② `Main` 的线程边界用源码护栏钉住——快照在投递前复制、重活在后台、结果按序号回主线程、
 *    三条回调都走节流、`precheckSpace` 仍取实时读盘。
 *
 * 挡的是四种真实回退：把 `ImportPlan.of` 搬回 UI 线程、后台直接改界面状态、
 * 为了「简单」去掉序号让迟到结果覆盖新勾选、把 CTA 前的实时防线也套上缓存。
 */
final class SpaceRefreshCases {

    private static final String REL_MAIN = "com/rea/mtpimport/Main.java";
    private static final String REL_SELECTION = "com/rea/mtpimport/Selection.java";
    private static final String REL_REFRESH = "com/rea/mtpimport/SpaceRefresh.java";
    private static final String ALLOW_MAIN = "src/" + REL_MAIN;

    private static String src(String rel) {
        return readSourceOrNull(source(rel));
    }

    private static boolean has(String s, String needle) {
        return s != null && s.contains(needle);
    }

    private static int lines(String s, String needle) {
        return s == null ? -1 : countLines(s, needle);
    }

    // ---------------- 可直接跑的行为 ----------------

    static void spaceRefreshCases() {
        check("IA-7：可用空间缓存 TTL 落在 1–2 秒",
                SpaceRefresh.FREE_BYTES_TTL_MS >= 1000L && SpaceRefresh.FREE_BYTES_TTL_MS <= 2000L,
                "实际 " + SpaceRefresh.FREE_BYTES_TTL_MS + " ms");

        final long[] now = {10_000L};
        final int[] probes = {0};
        LongSupplier probe = () -> {
            probes[0]++;
            return 1000L + probes[0];
        };
        SpaceRefresh r = new SpaceRefresh(() -> now[0]);

        long s1 = r.submit();
        long s2 = r.submit();
        long s3 = r.submit();
        check("序号单调递增，只有最新一次提交算数",
                s1 == 1L && s2 == 2L && s3 == 3L
                        && r.isCurrent(s3) && !r.isCurrent(s2) && !r.isCurrent(0L),
                "s1/s2/s3 = " + s1 + "/" + s2 + "/" + s3);

        check("TTL 内复用缓存，读盘只发生一次",
                r.freeBytes(probe) == 1001L && r.freeBytes(probe) == 1001L && probes[0] == 1,
                "读盘次数 " + probes[0]);
        now[0] += SpaceRefresh.FREE_BYTES_TTL_MS - 1;
        check("差 1 ms 未到点，仍不读盘",
                r.freeBytes(probe) == 1001L && probes[0] == 1,
                "读盘次数 " + probes[0]);
        now[0] += 1L;
        check("到点重新读盘并更新缓存值",
                r.freeBytes(probe) == 1002L && probes[0] == 2,
                "读盘次数 " + probes[0]);
        now[0] -= 10_000L;
        check("时钟回拨不会被缓存永久锁住",
                r.freeBytes(probe) == 1003L && probes[0] == 3,
                "读盘次数 " + probes[0]);

        long latest = r.submit();
        r.cancel();
        check("Activity 销毁后所有在飞结果作废且不可逆",
                !r.isCurrent(latest) && !r.isCurrent(r.submit()),
                "cancel 后仍放行了结果");

        Catalog c = sample();
        Selection live = new Selection(c);
        live.setChecked(3, true);
        Selection snap = live.snapshot();
        check("快照复制当前勾选", snap.isChecked(3) && !snap.isChecked(6),
                "3/6 = " + snap.isChecked(3) + "/" + snap.isChecked(6));
        live.clear();
        check("UI 线程清空勾选不影响后台快照", snap.isChecked(3) && !live.isChecked(3),
                "快照被真相源的写操作带走了");
        snap.setChecked(6, true);
        check("后台改快照不回写真相源",
                snap.isChecked(6) && !live.isChecked(6), "快照与真相源串了");
    }

    // ---------------- 源码护栏：线程边界与唯一入口 ----------------

    static void spaceRefreshSourceCases() {
        String main = src(REL_MAIN);
        String selection = src(REL_SELECTION);
        String refresh = src(REL_REFRESH);

        check("IA-7：Main / Selection / SpaceRefresh 源码可读",
                main != null && selection != null && refresh != null,
                "main=" + (main != null) + " selection=" + (selection != null)
                        + " refresh=" + (refresh != null));
        check("IA-7：SpaceRefresh 是纯 JDK 类（不 import android）",
                !has(refresh, "import android"), "混进了 Android 依赖，自检空 classpath 会红");
        check("IA-7：过期判定同时看取消标志与最新序号",
                has(refresh, "!cancelled") && has(refresh, "seq == generation"),
                "isCurrent 被简化成恒真或恒假");

        List<String> hits = new ArrayList<>();
        int scanned = scanSourceText(SRC_ROOT, "new SpaceRefresh(", ALLOW_MAIN, hits);
        check("IA-7：调度状态只有 Main 一处实例，别处再建一个即红",
                scanned > 0 && hits.isEmpty(),
                "扫描 " + scanned + " 个文件，命中：\n" + String.join("\n", hits));

        String body = main == null ? null : slice(main, "private void refreshSpaceCheck() {", "\n    }");
        check("IA-7：refreshSpaceCheck 函数体可切片", body != null, "找不到函数体");
        // issue #390：早退分支清 lowSpace 之前必须先推进序号作废在飞结果；否则稍后
        // runOnUiThread 落地的旧任务仍满足 isCurrent(seq)，可能用被丢弃的旧计划回写一次
        // lowSpace。只切片「早退分支」本身，避免误命中正常路径里那个赋给 seq 的提交。
        String early = body == null ? null
                : slice(body, "if (demoActive() || catalog == null || selection == null) {", "\n        }");
        check("IA-7/#390：早退分支清状态前必须 spaceRefresh.submit() 作废在飞结果",
                has(early, "spaceRefresh.submit();")
                        && has(early, "model.setSpaceStatus(false")
                        && early.indexOf("spaceRefresh.submit();") < early.indexOf("model.setSpaceStatus(false"),
                "早退分支没有先 submit() 再清状态，在飞旧序号仍可能回写 lowSpace");
        check("IA-7：勾选 / 组选 / filter 的快照在投递前复制",
                has(body, "selection.snapshot()")
                        && has(body, "new ArrayList<>(model.getVisibleUnits())")
                        && has(body, "final Catalog snapCatalog = catalog;")
                        && has(body, "final PhotoWall.Export snapExport = model.getExportMode();"),
                "后台仍直接读 UI 线程的可变对象");
        check("IA-7：不再把活的 catalog / selection 交给 ImportPlan.of",
                !has(body, "ImportPlan.of(catalog, "), "旧写法又回来了");
        check("IA-7：重活在后台、结果回主线程",
                has(body, "spaceWorker.execute(") && has(body, "runOnUiThread(")
                        && has(body, "spaceRefresh.isCurrent(seq)")
                        && has(body, "SpaceGate.refresh(spaceSink, plan, () -> free)"),
                "后台 / 主线程边界不完整");
        check("IA-7：ImportPlan.of 在后台投递之后才出现",
                has(body, "ImportPlan.of(") && has(body, "spaceWorker.execute(")
                        && body.indexOf("ImportPlan.of(") > body.indexOf("spaceWorker.execute("),
                "计划构造被搬回 UI 线程");
        check("IA-7：界面状态只在主线程落，且读盘结果已取好",
                has(body, "runOnUiThread(")
                        && body.indexOf("SpaceGate.refresh(") > body.indexOf("runOnUiThread(")
                        && has(body, "spaceRefresh.freeBytes(this::freeBytes)")
                        && !has(body, "StatFs"),
                "后台直接改了界面状态，或 StatFs 又回到 UI 线程");

        String throttle = main == null ? null
                : slice(main, "private void scheduleSpaceCheck() {", "\n    }");
        check("IA-7：同一帧的多次刷新先撤后投，只留最后一次",
                has(throttle, "spaceHandler.removeCallbacks(spaceTask)")
                        && has(throttle, "spaceHandler.post(spaceTask)")
                        && throttle.indexOf("removeCallbacks") < throttle.indexOf("post(spaceTask)"),
                "节流顺序或入口不对");

        check("IA-7：三条回调都走节流，没有绕过 scheduleSpaceCheck",
                lines(main, "scheduleSpaceCheck();") == 3
                        && lines(main, "refreshSpaceCheck();") == 0
                        && has(main, "private final Runnable spaceTask = this::refreshSpaceCheck;"),
                "scheduleSpaceCheck 命中 " + lines(main, "scheduleSpaceCheck();")
                        + " 处 / refreshSpaceCheck() 直调 " + lines(main, "refreshSpaceCheck();") + " 处");

        String onSelection = main == null ? null
                : slice(main, "public void onSelectionChanged() {", "\n        }");
        String onBack = main == null ? null
                : slice(main, "public void onBackToCatalog() {", "\n        }");
        String publishFinal = main == null ? null
                : slice(main, "public void publishFinal(ScanRun run, ScanResult scan) {", "\n    }");
        check("IA-7：勾选变化 / 返回照片墙 / 扫描收尾三处都改成节流入口",
                has(onSelection, "scheduleSpaceCheck();")
                        && has(onBack, "scheduleSpaceCheck();")
                        && has(publishFinal, "scheduleSpaceCheck();"),
                "任一路径绕过了主线程合并");

        String destroy = main == null ? null : slice(main, "protected void onDestroy() {", "\n    }");
        check("IA-7：销毁时作废在飞结果、撤回调并停掉后台线程",
                has(destroy, "spaceRefresh.cancel();")
                        && has(destroy, "spaceHandler.removeCallbacksAndMessages(null);")
                        && has(destroy, "spaceWorker.shutdownNow();"),
                "Activity 销毁后仍可能回写界面状态");

        String precheck = main == null ? null
                : slice(main, "private boolean precheckSpace(ImportPlan plan) {", "\n    }");
        check("IA-7：CTA 前的预检仍取实时可用字节，不套 TTL 缓存",
                has(precheck, "SpaceGate.precheck(spaceSink, plan, freeBytes())")
                        && !has(precheck, "spaceRefresh"),
                "缓存把「预检后空间被吃掉」这道防线弄失效了");

        check("IA-7：Selection 的快照复制 checked 集合，不共享同一个 Set",
                has(selection, "public Selection snapshot()")
                        && has(selection, "copy.checked.addAll(checked);"),
                "快照只是把引用传出去，后台读仍会与 UI 线程竞态");
    }
}
