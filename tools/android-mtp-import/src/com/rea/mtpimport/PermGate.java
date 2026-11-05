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
 *
 * 纯 JDK（不 import android.*），由 `test/SelfTest.java` 的 `permissionCases` 钉住。
 */
public final class PermGate {

    private CountDownLatch latch = new CountDownLatch(1);
    private int seq;
    private boolean answered;
    private boolean granted;

    /** 开始一次新的申请：重建 latch、复位结果、序号 +1，返回本次序号（回调要带回来）。 */
    public synchronized int begin() {
        latch = new CountDownLatch(1);
        answered = false;
        granted = false;
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

    /** 等本次申请的回答；超时返回 false（超时不算回答）。 */
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        CountDownLatch l;
        synchronized (this) {
            l = latch;
        }
        return l.await(timeout, unit);
    }

    /** 本次申请是否真的拿到了权限（超时或拒绝都是 false）。 */
    public synchronized boolean granted() {
        return answered && granted;
    }

    /** 放弃等待（Activity 销毁时用）：提前释放 `await`，结果保持「未授权」。 */
    public void cancel() {
        CountDownLatch l;
        synchronized (this) {
            l = latch;
        }
        l.countDown();
    }
}
