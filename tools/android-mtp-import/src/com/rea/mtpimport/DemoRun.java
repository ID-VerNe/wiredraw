package com.rea.mtpimport;

/**
 * demo 传输模拟的 main 侧接口（issue #46）。
 *
 * 实现类 `DemoTransfer` 只存在于 debug 源集，由 {@link DemoBridge#newRun(int, long)}
 * 构造；release 里 Main 拿不到实例，进度模拟整段不生效，也不会引用 debug 类。
 */
public interface DemoRun {

    int doneFiles();

    long doneBytes();

    int totalFiles();

    /** 推进一个文件；到顶返回 true。 */
    boolean advance();
}
