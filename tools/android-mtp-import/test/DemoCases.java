package com.rea.mtpimport;

import java.util.List;

import static com.rea.mtpimport.TestSupport.*;
import static com.rea.mtpimport.Fixtures.*;

/** 假数据树与 demo 传输模拟的断言组（#318 从 SelfTest.java 拆出）。 */
final class DemoCases {

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
}
