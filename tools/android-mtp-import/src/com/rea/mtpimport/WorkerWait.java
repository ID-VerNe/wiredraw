package com.rea.mtpimport;

/**
 * 有界 join 的收尾模板（issue #308）：中断复位 + 存活判定。
 *
 * 两个调用方的时间契约不同——{@link ImportService#awaitWorker(long)} 收**相对毫秒**，
 * `Main.awaitIdle` 收**绝对截止时刻**后自己换算成剩余毫秒（三个 worker 共用一个
 * deadline，加起来不超过 1 s）。所以这里只收已经算好的毫秒数，不接管时间语义：
 * 谁算剩余时间谁负责，模板只负责两条必须一致的口径——被中断时复位中断标志并判
 * 「未退出」，睡醒后按 {@link Thread#isAlive()} 判是否真的退出。
 *
 * 独立顶层文件（issue #341）：#308 收敛的这套模板不再挂在 {@link ImportService} 里，
 * 让服务层不再兼任线程等待工具。本类是纯 JDK 类，已在 `gradle/self-test.gradle.kts`
 * 的 `pureJdkClasses` 清单内申报（`checkPureJdkList` 逐项比对，未申报即判红）。
 */
public final class WorkerWait {
    private WorkerWait() {
    }

    static boolean finishJoin(Thread t, long millis) {
        try {
            t.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !t.isAlive();
    }
}
