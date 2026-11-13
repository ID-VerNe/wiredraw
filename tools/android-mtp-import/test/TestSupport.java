package com.rea.mtpimport;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** 断言框架与全局计数（#318 从 SelfTest.java 抽出）：`check` 只打印并累计，不抛异常。 */
final class TestSupport {

    static int passed = 0;
    static int failed = 0;
    static final StringBuilder failures = new StringBuilder();

    /** 默认异步操作完成上限（毫秒）：遵守 5 秒不变，不作掩盖式延长（#345）。 */
    static final long DEFAULT_AWAIT_TIMEOUT_MS = 5000L;

    static String number(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name + "  —— " + detail);
            failures.append("  - ").append(name).append("：").append(detail).append('\n');
        }
    }

    static List<Integer> ints(int... v) {
        Integer[] boxed = Arrays.stream(v).boxed().toArray(Integer[]::new);
        return Arrays.asList(boxed);
    }

    /**
     * 等待 CountDownLatch 倒数归零（默认 5 秒上限，#345）。
     * 超时在 stderr 打印耗时与活动线程快照，便于区分调度抖动与死锁。
     */
    static boolean awaitLatch(CountDownLatch latch) {
        return awaitLatch(latch, DEFAULT_AWAIT_TIMEOUT_MS);
    }

    /**
     * 等待 CountDownLatch 倒数归零（指定上限毫秒）。
     * 超时在 stderr 打印耗时与活动线程快照。
     */
    static boolean awaitLatch(CountDownLatch latch, long timeoutMs) {
        if (latch == null) return false;
        long start = System.currentTimeMillis();
        boolean ok;
        try {
            ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!ok) {
            long elapsed = System.currentTimeMillis() - start;
            System.err.println("[TIMEOUT] latch 等待超时（耗时 " + elapsed + "ms / 上限 " + timeoutMs + "ms）");
            dumpActiveThreads();
        }
        return ok;
    }

    /**
     * 轮询条件成立（短步长轮询 + 默认 5 秒超时上限，#345）。
     * 用于非 latch 驱动或状态机条件的等待；超时在 stderr 打印耗时与活动线程快照。
     */
    static boolean awaitCondition(BooleanSupplier condition) {
        return awaitCondition(condition, DEFAULT_AWAIT_TIMEOUT_MS, 20L);
    }

    static boolean awaitCondition(BooleanSupplier condition, long timeoutMs, long pollIntervalMs) {
        if (condition == null) return false;
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        boolean finalOk = condition.getAsBoolean();
        if (!finalOk) {
            long elapsed = System.currentTimeMillis() - start;
            System.err.println("[TIMEOUT] 条件轮询超时（耗时 " + elapsed + "ms / 上限 " + timeoutMs + "ms）");
            dumpActiveThreads();
        }
        return finalOk;
    }

    static void dumpActiveThreads() {
        System.err.println("--- 活动线程快照 ---");
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && !t.isDaemon() && t != Thread.currentThread()) {
                System.err.println("  Thread [" + t.getName() + "] state=" + t.getState());
                StackTraceElement[] st = t.getStackTrace();
                int limit = Math.min(st.length, 5);
                for (int i = 0; i < limit; i++) {
                    System.err.println("    at " + st[i]);
                }
            }
        }
        System.err.println("--------------------");
    }
}
