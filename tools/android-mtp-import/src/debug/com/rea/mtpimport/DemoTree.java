package com.rea.mtpimport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 假数据模式的数据源（只在 `src/debug` 里编译，release APK 不含本类，U-54）。
 *
 * 口径与真机一致（`RESEARCH-android-usb-raw-a7c.md` §12.3 / #17 的复验行）：
 * 1526 个对象 = 503 个文件夹 + 511 张 ARW + 512 张 JPEG，合计 12.75 GB，其中 ARW 11.89 GB。
 * 文件夹里只有 12 个日期目录挂了照片，其余是空的日期目录——这样照片墙正好 12 个日期组。
 * issue #83 起照片墙按照片单元计（512 个单元 = 511 双格式 + 1 仅 JPG），
 * 顶栏计数胶囊与 `Import all 512` 同分母，默认载荷 JPG 优先（923 408 256 字节 ≈ 0.86 GB）。
 *
 * 本类不 import android.*：它和 main 侧的纯逻辑类一起在**空 classpath**下编译，
 * 并进 selfTest 的 classpath（U-54 的四个总量由 JVM 断言守住）。
 */
public final class DemoTree {

    public static final int OBJECT_COUNT = 1526;
    public static final int FOLDER_COUNT = 503;
    public static final int ARW_COUNT = 511;
    public static final int JPEG_COUNT = 512;

    /** 12.75 GB（1024 进制）= 13 690 208 256 字节。 */
    public static final long TOTAL_BYTES = 13_690_208_256L;
    /** 11.89 GB（1024 进制）≈ 12 766 800 000 字节，与真机扫描行同口径。 */
    public static final long RAW_BYTES = 12_766_800_000L;
    /** JPEG 合计 = 923 408 256 字节 ≈ 0.86 GB，即 `All` / `JPG` 档的默认导出量（#83）。 */
    public static final long JPEG_BYTES = TOTAL_BYTES - RAW_BYTES;

    /** 传输屏的假批次：36 张，与定稿屏的 `33%` + `12/36` 同源（U-45 要求两者自洽）。 */
    public static final int BATCH_PHOTOS = 36;
    /** 假速率 3.4 MB/s（只进 Details 抽屉，P-03 不允许进主视觉）。 */
    public static final long FAKE_RATE = 3_567_104L;

    /** `ERROR` 的一行原因（≤20 字，C-01 / T-ERROR）。 */
    public static final String ERROR_REASON = "相机连接已断开，请拔插数据线";

    private static final String[] DATES = {
            "20260607", "20260601", "20260528", "20260522",
            "20260514", "20260430", "20260418", "20260330",
            "20260318", "20260209", "20260123", "20251225"
    };

    private static final int DCIM_HANDLE = 1;
    private static final int FOLDER_HANDLE_BASE = 2;
    private static final int FILE_HANDLE_BASE = 600;

    private DemoTree() {
    }

    /** 10 态的显示名（详情抽屉里的切态入口按这个顺序排，与 UI-SPEC §1.2 一致）。 */
    public static List<String> stateNames() {
        List<String> out = new ArrayList<>();
        for (UiState.State s : UiState.State.values()) out.add(s.name());
        return out;
    }

    /** 单张 ARW 的假大小（511 张之和 = {@link #RAW_BYTES}）。 */
    public static long arwSize() {
        return RAW_BYTES / ARW_COUNT;
    }

    /**
     * 造一棵假树：`DCIM/` 下 502 个日期目录；照片只落在前 12 个日期目录上。
     * 返回已 {@code build()} 的 catalog（relPath 已按 parent 链算好）。
     */
    public static Catalog catalog() {
        Catalog c = new Catalog();
        c.add(new Catalog.Entry(DCIM_HANDLE, 0, Catalog.FMT_FOLDER, 0, "DCIM"));

        for (int i = 0; i < FOLDER_COUNT - 1; i++) {
            c.add(new Catalog.Entry(FOLDER_HANDLE_BASE + i, DCIM_HANDLE,
                    Catalog.FMT_FOLDER, 0, folderName(i)));
        }

        long arwBase = RAW_BYTES / ARW_COUNT;
        long arwRem = RAW_BYTES % ARW_COUNT;
        for (int k = 0; k < ARW_COUNT; k++) {
            long size = arwBase + (k < arwRem ? 1 : 0);
            c.add(new Catalog.Entry(FILE_HANDLE_BASE + k,
                    FOLDER_HANDLE_BASE + (k % DATES.length),
                    Catalog.FMT_ARW, size,
                    String.format(Locale.US, "DSC%05d.ARW", 10000 + k)));
        }

        long jpegTotal = TOTAL_BYTES - RAW_BYTES;
        long jpegBase = jpegTotal / JPEG_COUNT;
        long jpegRem = jpegTotal % JPEG_COUNT;
        for (int k = 0; k < JPEG_COUNT; k++) {
            long size = jpegBase + (k < jpegRem ? 1 : 0);
            c.add(new Catalog.Entry(FILE_HANDLE_BASE + ARW_COUNT + k,
                    FOLDER_HANDLE_BASE + (k % DATES.length),
                    Catalog.FMT_JPEG, size,
                    String.format(Locale.US, "DSC%05d.JPG", 10000 + k)));
        }
        return c.build();
    }

    /** 前 12 个目录直接用日期名，其余带后缀保证唯一；组头仍按前 8 位日期渲染。 */
    private static String folderName(int i) {
        if (i < DATES.length) return DATES[i];
        return DATES[i % DATES.length] + "-" + (i / DATES.length);
    }

    /**
     * 某个状态的假载荷。目录态与 `ERROR` 带假树（`ERROR` 要验「返回回照片墙、选择还在」），
     * 其余态带该态需要的计数。
     * `IMPORTING` / `PAUSED` 用 12/36（百分比 33% 与定稿屏一致，U-45 的自洽要求）。
     */
    public static DemoPayload payload(UiState.State state) {
        if (state == null) return null;
        switch (state) {
            case SCANNING:
                // 1526 只作扫描进度分母（U-43 / C-09）；顶栏此时不渲染计数胶囊。
                return new DemoPayload(state, null, 320, OBJECT_COUNT,
                        0, 0, 0, 0, 0, "", 0, 0);
            case CATALOG_READY:
                // #83：照片单元数 = 512，默认载荷 JPG 优先 → 512 个文件 / 923 408 256 字节。
                return new DemoPayload(state, catalog(), 0, 0,
                        0, JPEG_COUNT, 0, JPEG_BYTES, 0, "", 0, 0);
            case IMPORTING:
            case PAUSED: {
                long per = arwSize();
                int done = 12;
                return new DemoPayload(state, catalog(), 0, 0,
                        done, BATCH_PHOTOS,
                        per * done, per * BATCH_PHOTOS,
                        FAKE_RATE, "", 0, 0);
            }
            case DONE: {
                long total = arwSize() * BATCH_PHOTOS;
                return new DemoPayload(state, catalog(), 0, 0,
                        BATCH_PHOTOS, BATCH_PHOTOS, total, total, 0, "",
                        BATCH_PHOTOS, total);
            }
            case ERROR:
                // 必须带假树：`ERROR` 的返回键口径是「回照片墙且选择还在」（§1.3），
                // 没有树时 `AppModel.backToCatalog()` 只能落到 `COLD`，这条口径无处可验。
                return new DemoPayload(state, catalog(), 0, 0, 0, 0, 0, 0, 0,
                        ERROR_REASON, 0, 0);
            default:
                // COLD / PERMISSION_PENDING / EMPTY_CATALOG / UNAVAILABLE：不带假树。
                return new DemoPayload(state, null, 0, 0, 0, 0, 0, 0, 0, "", 0, 0);
        }
    }

    /**
     * demo 预览图下标（issue #46）：同一 handle 恒映射到同一张降采样图。
     * `count <= 0`（没有素材）时返回 -1，调用方退回无图胶囊。
     */
    public static int previewIndex(int handle, int count) {
        if (count <= 0) return -1;
        return Math.floorMod(handle, count);
    }

    /** 测试用：把日期目录名列表原样给出（不参与界面）。 */
    static List<String> dateNames() {
        return Arrays.asList(DATES);
    }
}
