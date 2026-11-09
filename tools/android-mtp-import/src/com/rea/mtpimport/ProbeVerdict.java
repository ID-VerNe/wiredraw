package com.rea.mtpimport;

/**
 * 会话探活结论的唯一归属（#268）。
 *
 * 纯 JDK，不 import android.*：判定规则直接在 JVM 自检里钉住。
 *
 * 关键口径：**`ioLock` 被活跃 IO 持有时按存活处理**。旧实现让探活线程阻塞 `lock()`，
 * UI 线程再 `join(1200 ms)`，等锁时间被一起算进窗口；单张 RAW 真机约 1.14 s，
 * 暂停后立刻 Resume 就会假超时，把本来能续的一轮取消成 `ERROR`。
 */
public final class ProbeVerdict {

    private ProbeVerdict() {
    }

    /**
     * @param hasSession 探活前有可用会话（session 非空且 `device()` 非空）
     * @param lockBusy 试锁失败：有活跃 IO 正持 `ioLock`（导入 / 缩略图）
     * @param finished 探活线程在超时窗口内结束
     * @param error 探活期间抛了异常
     * @param gotInfo `getObjectInfo` 返回了非空结果
     */
    public static boolean alive(boolean hasSession, boolean lockBusy,
                                boolean finished, boolean error, boolean gotInfo) {
        if (!hasSession) return false;
        // #281：顺序是判据本身——hasSession 假必须先于 lockBusy 真判死，
        // 否则无会话一代会被旧会话的在飞 IO 误判为活。
        // 有活跃 IO 正持锁 → 会话必然还在工作，直接判活；不把等锁算进超时窗口。
        if (lockBusy) return true;
        if (!finished) return false;
        if (error) return false;
        return gotInfo;
    }
}
