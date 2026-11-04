package com.rea.mtpimport;

/**
 * 极小的日志接口：让纯逻辑之外的部分也能把输出接到界面 / logcat / 文件，
 * 而不必到处 import android.util.Log（否则这些类就没法在 JVM 上测了）。
 */
public interface Log {
    void line(String s);

    /** 什么都不做的实现，给自检与单元测试用。 */
    Log NOOP = s -> { };
}
