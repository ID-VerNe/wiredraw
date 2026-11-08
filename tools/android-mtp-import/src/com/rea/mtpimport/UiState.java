package com.rea.mtpimport;

import java.util.Locale;

/**
 * 界面状态机（UI-SPEC §1.2 的 10 态）。
 *
 * 这一层只做「状态名 → 界面」的映射，不 import android.*，便于在 JVM 上直接测。
 * 真正的状态推进仍由 {@link Main} 里的 USB / 权限 / 扫描 / 传输编排驱动。
 */
public final class UiState {

    public enum State {
        /** 没有相机，首屏给相机图标 + `No camera` + `Connect camera`。 */
        COLD,
        /** 已找到相机，等系统 USB 权限弹窗，唯一主按钮是 `Allow`。 */
        PERMISSION_PENDING,
        /** 枚举目录中：细环 + `320/1526` + `Cancel`。 */
        SCANNING,
        /** 枚举完成，照片墙可交互。 */
        CATALOG_READY,
        /** 扫到了但没有任何照片：`No photos` + `Connect camera`。 */
        EMPTY_CATALOG,
        /** 传输实现未就绪 / 相机不可用：`Not available` + `OK`。 */
        UNAVAILABLE,
        /** 传输中：环内百分比是唯一主数字，环下 a/b，`Pause` / `Cancel`。 */
        IMPORTING,
        /** 已暂停：环冻结，`Resume` / `Cancel`。 */
        PAUSED,
        /** 完成：完成标记 + `Done` + 数量/容量 + 位置图标 + `Open`。 */
        DONE,
        /** 出错：一行中文原因 + `Reconnect`（顶栏标签与内容区插头图标）+ 详情抽屉。 */
        ERROR
    }

    private UiState() {
    }

    /**
     * 解析 `--es demo_state <X>` 或内部状态名。
     *
     * 兼容任务口径里的短名（`PERMISSION` / `READY` / `EMPTY`），未知值返回 {@code null}
     * ——调用方据此判断「没有开 demo」，而不是悄悄落到某个默认态。
     */
    public static State parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.US).replace('-', '_').replace(' ', '_');
        switch (s) {
            case "COLD":
                return State.COLD;
            case "PERMISSION":
            case "PERMISSION_PENDING":
                return State.PERMISSION_PENDING;
            case "SCANNING":
                return State.SCANNING;
            case "READY":
            case "CATALOG_READY":
                return State.CATALOG_READY;
            case "EMPTY":
            case "EMPTY_CATALOG":
                return State.EMPTY_CATALOG;
            case "UNAVAILABLE":
                return State.UNAVAILABLE;
            case "IMPORTING":
                return State.IMPORTING;
            case "PAUSED":
                return State.PAUSED;
            case "DONE":
                return State.DONE;
            case "ERROR":
                return State.ERROR;
            default:
                return null;
        }
    }

    /** 传输屏：唯一焦点是环（`IMPORTING` / `PAUSED`）。 */
    public static boolean isTransfer(State s) {
        return s == State.IMPORTING || s == State.PAUSED;
    }

    /** 照片墙屏。 */
    public static boolean showsLibrary(State s) {
        return s == State.CATALOG_READY;
    }

    /**
     * USB 拔出时是否要标断线（#44 复审 / P1-1）：只有传输态（`IMPORTING` / `PAUSED`）。
     *
     * 非传输态本来就有各自的连接指示（`COLD` / `ERROR` 的 `Disconnected`），
     * 拔出事件不改它们的状态，避免把照片墙或完成页无谓地打回。
     */
    public static boolean detachMarksTransfer(State s) {
        return isTransfer(s);
    }
}
