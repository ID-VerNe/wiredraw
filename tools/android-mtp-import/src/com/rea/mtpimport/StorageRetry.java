package com.rea.mtpimport;

/**
 * `getStorageIds` 的带唤醒重试（PR #41 第五轮评审 B-1）。
 *
 * 原循环在 {@link MtpSession#storageIds} 里直接 `for + Thread.sleep(3000)`：取消后仍会睡满
 * 8 次（约 24 秒），这段时间 {@link AttachGate} 的扫描锁不释放，用户看到的是 `COLD`，
 * 再点 `Connect camera` 却得到「扫描已在进行中，忽略本次重复事件」。
 *
 * 抽成纯 JDK 之后：每次取之前判 {@link ScanRun}，取消即返回空数组；等待器可注入
 * （真机是 `Thread.sleep`，自检里立即返回），所以「取消后只睡 1 次就退出」能被 JVM 断言钉住。
 */
public final class StorageRetry {

    public interface Fetcher {
        /** 取一次存储 ID；失败返回 `null` 或空数组。 */
        int[] fetch();
    }

    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private StorageRetry() {
    }

    /**
     * @return 非空数组 = 成功；空数组 = 重试用尽 / 已取消（调用点一律按失败收尾）
     */
    public static int[] fetch(Fetcher fetcher, Sleeper sleeper, ScanRun run,
                              int retries, long retryMs, Log log) {
        for (int attempt = 1; attempt <= retries; attempt++) {
            if (run != null && run.isCancelled()) {
                log.line("已取消，停止 getStorageIds 重试");
                return new int[0];
            }
            int[] sids = fetcher.fetch();
            if (sids != null && sids.length > 0) return sids;
            // 取的过程中用户可能已经点了 Cancel：不再多睡一个 3 秒。
            if (run != null && run.isCancelled()) {
                log.line("已取消，停止 getStorageIds 重试");
                return new int[0];
            }
            log.line("第 " + attempt + "/" + retries
                    + " 次 getStorageIds() 为空 —— 相机可能休眠了，"
                    + "按一下相机按键唤醒；" + (retryMs / 1000) + " 秒后重试");
            try {
                sleeper.sleep(retryMs);
            } catch (InterruptedException ie) {
                // onCancel 会 interrupt 扫描线程：立刻退出，不再睡满一轮。
                Thread.currentThread().interrupt();
                return new int[0];
            }
        }
        return new int[0];
    }
}
