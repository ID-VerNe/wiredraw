package com.rea.mtpimport;

/**
 * 主 CTA 的准入判定（唯一归属，N-1）。
 *
 * 两个条件**都必须**生效：后台补齐扫描（issue #60）与空间不足（P-09）。
 * 之前这条判断直接写在 `LibraryScreen` 的 `enabled = ...` 表达式里，
 * 变异实验证明删掉 `!backgroundScanning` 后 292 项自检仍全绿——约束没有可执行断言守着。
 * 现在页面与 {@link SelfTest} 共用本函数，条件不再有第二份拷贝。
 */
public final class ImportGate {

    private ImportGate() {
    }

    /**
     * 主 CTA 是否可点：渐进扫描中禁用（扫描线程与导入不能在同一 MTP 会话上并行发命令），
     * 空间不足时同样禁用（P-09）。
     */
    public static boolean enabled(boolean backgroundScanning, boolean lowSpace) {
        return !backgroundScanning && !lowSpace;
    }
}
