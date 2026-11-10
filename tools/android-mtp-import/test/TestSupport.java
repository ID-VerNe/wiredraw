package com.rea.mtpimport;

import java.util.Arrays;
import java.util.List;

/** 断言框架与全局计数（#318 从 SelfTest.java 抽出）：`check` 只打印并累计，不抛异常。 */
final class TestSupport {

    static int passed = 0;
    static int failed = 0;
    static final StringBuilder failures = new StringBuilder();

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
}
