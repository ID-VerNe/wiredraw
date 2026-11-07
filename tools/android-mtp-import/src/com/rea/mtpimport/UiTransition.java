package com.rea.mtpimport;

/**
 * 界面状态迁移表（#213）。
 *
 * 动机：`backgroundScanning` / `transferViewClosed` / `exitConfirmOpen` 这三个
 * 「跨状态字段」曾经散在 {@code AppModel} 的十个方法里各自复位，改一个状态很容易
 * 漏复位另一个。本表把「进入某个动作时，三个字段各自该变成什么」集中到一处，
 * {@code AppModel} 只留一个私有 `transition(action)` 按表赋值。
 *
 * 本类是纯 JDK（不 import android.* / Compose），所以 {@code SelfTest} 能直接驱动
 * 每一条迁移——这是本单唯一可执行的回归网。
 *
 * 逐条与 `main@4c0800c` 的现有行为等价：
 * <ul>
 *   <li>{@code COLD} / {@code PERMISSION} / {@code EMPTY} / {@code UNAVAILABLE} /
 *       {@code ERROR} / {@code CATALOG_FULL}：只复位扫描标志，覆盖层保持；</li>
 *   <li>{@code CATALOG_PARTIAL}：渐进发布特有，扫描标志置真；</li>
 *   <li>{@code IMPORT_START} / {@code DONE}：复位视图关闭与退出确认框，扫描标志保持；
 *       注意 {@code showDone} 历史上并不写 `backgroundScanning`，「`DONE` 是否应复位它」
 *       规格未定（#213「待裁决」），这里按现状保留，不顺手改行为；</li>
 *   <li>{@code PROGRESS} / {@code PAUSED} / {@code BACK_READY} / {@code BACK_COLD}：只换状态；</li>
 *   <li>{@code DISCARD_PARTIAL}：不换状态，只复位扫描标志；</li>
 *   <li>{@code DEMO_RESET}：不换状态，复位两个覆盖层；{@code LEAVE_VIEW}：不换状态，置视图关闭。</li>
 * </ul>
 */
public final class UiTransition {

    /** 迁移动作：每个写 `state` 或写跨状态字段的入口一个。 */
    public enum Action {
        COLD,
        PERMISSION,
        EMPTY,
        UNAVAILABLE,
        ERROR,
        CATALOG_FULL,
        CATALOG_PARTIAL,
        SCANNING,
        IMPORT_START,
        PROGRESS,
        DONE,
        PAUSED,
        DISCARD_PARTIAL,
        DEMO_RESET,
        LEAVE_VIEW,
        BACK_READY,
        BACK_COLD
    }

    /** 迁移结果：`state` 为 null 表示本动作不换状态，只处置跨状态字段。 */
    public static final class Result {
        public final UiState.State state;
        public final boolean backgroundScanning;
        public final boolean transferViewClosed;
        public final boolean exitConfirmOpen;

        Result(UiState.State state, boolean backgroundScanning,
               boolean transferViewClosed, boolean exitConfirmOpen) {
            this.state = state;
            this.backgroundScanning = backgroundScanning;
            this.transferViewClosed = transferViewClosed;
            this.exitConfirmOpen = exitConfirmOpen;
        }
    }

    /**
     * 按当前跨状态字段算出一次迁移的终值。
     *
     * @param action       迁移动作
     * @param scanning     当前 `backgroundScanning`
     * @param viewClosed   当前 `transferViewClosed`
     * @param confirmOpen  当前 `exitConfirmOpen`
     */
    public static Result resolve(Action action, boolean scanning, boolean viewClosed, boolean confirmOpen) {
        UiState.State next;
        boolean scan = scanning;
        boolean closed = viewClosed;
        boolean confirm = confirmOpen;
        switch (action) {
            case COLD:
                next = UiState.State.COLD;
                scan = false;
                break;
            case PERMISSION:
                next = UiState.State.PERMISSION_PENDING;
                scan = false;
                break;
            case EMPTY:
                next = UiState.State.EMPTY_CATALOG;
                scan = false;
                break;
            case UNAVAILABLE:
                next = UiState.State.UNAVAILABLE;
                scan = false;
                break;
            case ERROR:
                next = UiState.State.ERROR;
                scan = false;
                break;
            case CATALOG_FULL:
                next = UiState.State.CATALOG_READY;
                scan = false;
                break;
            case CATALOG_PARTIAL:
                next = UiState.State.CATALOG_READY;
                scan = true;
                break;
            case SCANNING:
                next = UiState.State.SCANNING;
                break;
            case IMPORT_START:
                next = UiState.State.IMPORTING;
                closed = false;
                confirm = false;
                break;
            case PROGRESS:
                next = UiState.State.IMPORTING;
                break;
            case DONE:
                next = UiState.State.DONE;
                closed = false;
                confirm = false;
                break;
            case PAUSED:
                next = UiState.State.PAUSED;
                break;
            case DISCARD_PARTIAL:
                next = null;
                scan = false;
                break;
            case DEMO_RESET:
                next = null;
                closed = false;
                confirm = false;
                break;
            case LEAVE_VIEW:
                next = null;
                closed = true;
                confirm = false;
                break;
            case BACK_READY:
                next = UiState.State.CATALOG_READY;
                break;
            case BACK_COLD:
                next = UiState.State.COLD;
                break;
            default:
                throw new IllegalArgumentException("未覆盖的迁移动作：" + action);
        }
        return new Result(next, scan, closed, confirm);
    }
}
