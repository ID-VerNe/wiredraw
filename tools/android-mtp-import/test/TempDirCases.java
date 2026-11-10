package com.rea.mtpimport;

import java.io.File;

import static com.rea.mtpimport.TestSupport.*;
import static com.rea.mtpimport.Fixtures.*;
import static com.rea.mtpimport.TempDirs.*;

/**
 * 临时目录模板自身的墓碑（#320）。
 *
 * 问题不是「有人忘了写 deleteTree」，而是清理只能靠在方法末尾手工配对：`check()` 只打印不抛异常，
 * 所以断言失败照样走得到末尾；但真异常会直接跳过清理。#320 把清理挪进 `finally` 之后，
 * 这两条分别钉住 withTempDir 的两条出口——正常返回与抛异常。
 */
final class TempDirCases {

    static void tempDirCases() throws Exception {
        File[] normal = new File[1];
        withTempDir("wiredraw-tmp-normal", dir -> {
            normal[0] = dir;
            writeSizedFile(new File(dir, "probe.bin"), 4);
        });
        check("withTempDir：正常结束即删除临时目录（#320）",
                !normal[0].exists(), "仍存在 " + normal[0]);

        File[] thrown = new File[1];
        try {
            withTempDir("wiredraw-tmp-throw", dir -> {
                thrown[0] = dir;
                writeSizedFile(new File(dir, "probe.bin"), 4);
                throw new IllegalStateException("boom");
            });
            check("withTempDir：body 抛异常时仍然删除目录（#320）", false, "异常没有传出来");
        } catch (IllegalStateException e) {
            check("withTempDir：body 抛异常时仍然删除目录（#320）",
                    !thrown[0].exists() && "boom".equals(e.getMessage()),
                    "目录仍存在=" + thrown[0].exists() + "，异常=" + e);
        }
    }
}
