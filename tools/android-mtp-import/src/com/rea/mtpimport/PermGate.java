package com.rea.mtpimport;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * USB 权限申请的等待门（#11 的墓碑）。
 *
 * 为什么单独抽出来：
 * 1. 原来 `Main` 里的 `CountDownLatch` 是**一次性**的：第一次授权成功后计数归零并保持 0，
 *    之后每次 `await()` 都立即通过，`permGranted` 也还是上一次的 `true`。
 *    于是「第二次没给权限」被当成「已授权」放行，最后以 `MtpDevice.open()` 失败收场，
 *    报错位置与真实原因不一致（#11）。
 * 2. 回调可能迟到：上一次申请超时后用户才点「允许 / 拒绝」，这条迟到的广播
 *    不得把新一次申请标成「已回答」。所以每次申请带序号，只认当前序号。
 * 3. **重发**（PR #41 首轮评审 B1）：`PERMISSION_PENDING` 里点 `Allow` 会在扫描线程已经
 *    阻塞在 `await` 上时再开一轮申请。旧实现 `await` 只捕获调用瞬间的 latch、`begin()`
 *    又不唤醒旧 latch，扫描线程会一直卡在**上一轮**的 latch 上直到 120 s 超时——
 *    用户点了 `Allow` 界面也不动（U-42 必失败）。现在 `begin()` 会唤醒旧 latch，
 *    `await` 醒来后回环重取当前 latch，因此新序号的回调能结束同一次等待。
 *
 * 纯 JDK（不 import android.*），由 `test/SelfTest.java` 的 `permissionCases` / `allowCases` 钉住。
 */
public final class PermGate {

    private CountDownLatch latch = new CountDownLatch(1);
    private int seq;
    private boolean answered;
    private boolean granted;
    private boolean cancelled;

    /** 开始一次新的申请：重建 latch、复位结果、序号 +1，返回本次序号（回调要带回来）。 */
    public synchronized int begin() {
        CountDownLatch previous = latch;
        latch = new CountDownLatch(1);
        answered = false;
        granted = false;
        cancelled = false;
        // 唤醒还阻塞在上一轮的 `await`：它醒来后会回环重取新 latch，继续等这一轮的回答。
        // 少这一行就会出现「点了 Allow，扫描线程仍要空等旧 latch 到 120 s 超时」（B1）。
        previous.countDown();
        return ++seq;
    }

    /**
     * 收到权限回调。只有「序号等于当前申请且尚未回答」才生效；
     * 旧申请迟到的回调返回 false，不释放本次等待、也不写结果。
     */
    public synchronized boolean onResult(int requestSeq, boolean isGranted) {
        if (requestSeq != seq || answered) return false;
        answered = true;
        granted = isGranted;
        latch.countDown();
        return true;
    }

    /**
     * 等本次申请的回答；超时返回 false（超时不算回答）。
     *
     * 返回 true 的两种来源：本轮已回答、或 `cancel()`（Activity 销毁）放弃等待。
     * 仅仅被 `begin()` 重发唤醒**不**算回答——醒来后回环继续等新 latch，
     * 否则「点了 Allow 但没授权」会被误判成已放行。
     */
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        long timeoutNanos = unit.toNanos(timeout);
        int round;
        long deadline;
        synchronized (this) {
            round = seq;
            deadline = System.nanoTime() + timeoutNanos;
        }
        while (true) {
            CountDownLatch l;
            synchronized (this) {
                if (answered || cancelled) return true;
                if (seq != round) {
                    // 等待期间发生了重发：按新一轮重新计时，别让用户刚点 Allow 就立刻超时。
                    round = seq;
                    deadline = System.nanoTime() + timeoutNanos;
                }
                l = latch;
            }
            long remain = deadline - System.nanoTime();
            if (remain <= 0) return false;
            // 醒来可能是「已回答 / 已取消 / 被重发唤醒 / 虚假唤醒」，统一回环重判。
            l.await(remain, TimeUnit.NANOSECONDS);
        }
    }

    /** 本次申请是否真的拿到了权限（超时或拒绝都是 false）。 */
    public synchronized boolean granted() {
        return answered && granted;
    }

    /** 放弃等待（Activity 销毁时用）：提前释放 `await`，结果保持「未授权」。 */
    public void cancel() {
        CountDownLatch l;
        synchronized (this) {
            cancelled = true;
            l = latch;
        }
        l.countDown();
    }
}
