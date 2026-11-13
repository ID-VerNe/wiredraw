package com.rea.mtpimport;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.rea.mtpimport.TestSupport.*;

/** 异步等待与轮询断言组（#345 抽出公共 helper 并自检）。 */
final class TestSupportCases {

    static void testSupportCases() throws Exception {
        // 1) latch 正常归零立即返回 true
        CountDownLatch done = new CountDownLatch(1);
        done.countDown();
        check("awaitLatch：已计数归零的 latch 正常返回 true（#345）",
                awaitLatch(done), "实际返回 false");

        // 2) latch 超时返回 false（短超时 20ms 验证超时路径）
        CountDownLatch hanging = new CountDownLatch(1);
        check("awaitLatch：未释放的 latch 超时返回 false（#345）",
                !awaitLatch(hanging, 20L), "实际返回 true");

        // 3) 条件成立正常返回 true
        AtomicBoolean condition = new AtomicBoolean(true);
        check("awaitCondition：成立的条件正常返回 true（#345）",
                awaitCondition(condition::get), "实际返回 false");

        // 4) 条件超时返回 false（短超时 20ms 验证超时路径）
        check("awaitCondition：未满足的条件超时返回 false（#345）",
                !awaitCondition(() -> false, 20L, 5L), "实际返回 true");
    }
}
