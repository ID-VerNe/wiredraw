package com.rea.mtpimport;

/**
 * 一轮扫描的取消令牌（PR #41 第四轮评审 B1 / U-06 / P-02）。
 *
 * 只在 `SCANNING` 点 `Cancel` 时把界面退回目录是不够的：扫描线程还在跑，
 * 完成时会把界面又翻回 `CATALOG_READY`，而 {@link AttachGate} 仍被这一轮占着，
 * 用户再点 `Connect camera` 只会拿到「扫描已在进行中，忽略本次重复事件」——假的 `COLD`。
 *
 * 所以每一轮扫描持有一个令牌：
 * - `Main.onCancel` 置位，并唤醒还在等 USB 权限的线程、关闭当前 MTP 会话；
 * - `Main.scanWorker` 在进度、结果、错误三处落地前都判一次，取消后一律丢弃。
 *
 * 纯 JDK（不 import android.*），所以「取消后结果不得翻回照片墙」能由 JVM 自检守住。
 */
public final class ScanRun {

    /** 由界面线程置位、扫描线程读取，必须 `volatile`。 */
    private volatile boolean cancelled;

    /** 取消本轮扫描。重复调用安全。 */
    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * 迟到回调的落地闸：返回 true 才允许改界面。
     *
     * 必须判在**执行时**（`runOnUiThread` 的 lambda 里再判一次），
     * 只判在投递前会漏掉「已投递、未执行」的窗口。
     */
    public boolean accept() {
        return !cancelled;
    }
}
