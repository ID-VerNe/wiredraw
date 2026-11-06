package com.rea.mtpimport;

/**
 * 一次导入的取消令牌（PB，issue #50）。
 *
 * 为什么不是只用一个 volatile 布尔：真正中断阻塞中的 `MtpDevice.importFile` 需要传输层
 * 在「取消发生的那一刻」做一件事——{@link MtpTransfer} 用它关闭正在写的 ParcelFileDescriptor，
 * 让原生写失败、阻塞调用尽快返回。只置位的话，取消要等整个文件传完才生效，那不是取消，
 * 只是「这个文件传完后停」，与暂停没有区别。
 *
 * 单槽而不是监听器列表：同一时刻只有一个文件在传；传输实现进入时注册自己的收尾动作，
 * 退出时 {@link #clearOnCancel()} 清掉，不必为几百个文件堆一串监听器。
 */
public final class CancelToken {

    private boolean cancelled;
    private Runnable action;

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    /**
     * 注册取消时要立即执行的动作。若已经取消，动作当场执行（在调用者线程上）。
     * 传输实现应在打开半成品后调用；动作必须幂等（可能被 close 多次）。
     */
    public void setOnCancel(Runnable r) {
        Runnable run = null;
        synchronized (this) {
            action = r;
            if (r != null && cancelled) run = r;
        }
        if (run != null) run.run();
    }

    /** 传输结束时清槽，避免下一次传输误触发上一条收尾动作。 */
    public synchronized void clearOnCancel() {
        action = null;
    }

    /**
     * 置位并同步执行已注册动作。
     *
     * 动作在调用者线程上执行：界面点 Cancel 时直接关 pfd，阻塞在 `importFile` 里的工作线程
     * 随后自己返回并清理；这里不等待、不 join，也不关闭 MTP 会话（会话仍给下一次导入用）。
     * 动作抛异常只吞掉，不影响取消语义——工作线程仍会按 {@link #isCancelled()} 收尾。
     */
    public void cancel() {
        Runnable run;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            run = action;
        }
        if (run != null) {
            try {
                run.run();
            } catch (Throwable ignored) {
                // 收尾动作失败不代表取消失败；工作线程仍会看到 isCancelled()
            }
        }
    }
}
