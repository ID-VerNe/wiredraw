package com.rea.mtpimport;

/**
 * 极小的日志接口：让纯逻辑之外的部分也能把输出接到界面 / logcat / 文件，
 * 而不必到处 import android.util.Log（否则这些类就没法在 JVM 上测了）。
 */
public interface Log {
    void line(String s);

    /**
     * 落点写入失败回退（issue #28 分节 PB）：默认转普通日志；Android 侧可覆盖它把回退原因
     * 送进界面的固定展示位，避免「已回退」被后续逐文件日志挤掉而变成静默失败。
     * 保留单抽象方法，方法引用（`this::logLine`）仍可用。
     */
    default void fallback(String reason) {
        line(reason);
    }

    /** 什么都不做的实现，给自检与单元测试用。 */
    Log NOOP = s -> { };
}
