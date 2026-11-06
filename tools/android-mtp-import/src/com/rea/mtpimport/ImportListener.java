package com.rea.mtpimport;

/**
 * 编排层回调。全部给默认空实现，调用点只覆盖自己关心的那几个。
 *
 * 约定：所有回调都在**工作线程**触发（界面层自己 post 到主线程），
 * 这样扫描 59 秒时界面不会被跨线程细节拖累。
 */
public interface ImportListener {

    default void onScanProgress(int scanned, int total) {
    }

    default void onCatalogReady(Catalog catalog) {
    }

    default void onPlanReady(ImportPlan plan) {
    }

    default void onProgress(TransferProgress progress, ImportPlan.Item current) {
    }

    default void onFileFinished(ImportPlan.Item item, ResumableTransfer.Kind kind, String note) {
    }

    /**
     * 一轮结束。
     *
     * @param cancelled 是否被用户取消
     * @param ok        真正传输成功的文件数
     * @param skipped   日志显示已存在、本轮跳过的文件数
     * @param failed    传输失败的文件数
     * @param summary   人读摘要（日志/抽屉用，不参与状态判定）
     */
    default void onFinished(boolean cancelled, int ok, int skipped, int failed, String summary) {
    }

    default void onError(String message, Throwable t) {
    }
}
