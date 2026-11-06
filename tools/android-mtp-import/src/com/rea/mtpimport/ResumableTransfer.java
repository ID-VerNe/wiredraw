package com.rea.mtpimport;

/**
 * 断点续传的**契约**。PA 的 {@link MtpTransfer} 只做整文件传输；真正的 offset 续读
 * 留给 PC 分节（issue #50），在 `getPartialObject` 语义于 ILCE-7C 上验证之前不实现。
 *
 * 为什么不做真续传：`MtpDevice.getPartialObject(handle, offset, size, buffer)`
 * 对 a7C 的 `0xB101` 对象是否支持 offset 续读、超界时是报 `InvalidParameter` 还是
 * 静默少传，**本机一次都没验证过**（探针只用了 `importFile()`，它没有 offset 语义）。
 * 写一个「看起来能续传」的实现比没有实现危险得多：它会让我们以为断点续传已经可用，
 * 而真机上可能写出一堆截断的 ARW。
 *
 * 真实现该做的三件事（留给 PC 分节，先在真机把语义钉死）：
 *
 * 1. **跨会话重识别**：MTP 的 object handle 不跨会话稳定，重连后要按
 *    `size + relPath` 在 `Catalog` 里找到新 handle，`Journal` 存的就是这两样。
 * 2. **从 resumeFrom 续读**：`getPartialObject(handle, resumeFrom, chunk, buf)` 循环，
 *    每块 append 到 `<handle>.part`，成功后 rename 成最终文件名。
 * 3. **对账**：落盘字节必须等于 `oi.getCompressedSizeLong()`；不等就判 FAILED 并保留
 *    `.part`，不要静默当成成功（探针里出现过「大小❌」的分支）。
 */
public interface ResumableTransfer {

    enum Kind {
        /** 整个文件传完并已落盘。 */
        OK,
        /** 日志显示已传满，直接跳过。 */
        SKIPPED,
        /** 中途失败，`.part` 保留，下次可续。 */
        FAILED,
        /**
         * 收到取消，当前文件会被尝试中断（PB）：本次机型实测约 0.6 s 返回，能否打断没有跨机型保证。
         * 传输实现负责清掉本次未传满的 `.part`；
         * 编排层不得把它记成完成，也不计入失败。
         */
        CANCELLED
    }

    final class Result {
        public final Kind kind;
        public final String note;

        public Result(Kind kind, String note) {
            this.kind = kind;
            this.note = note == null ? "" : note;
        }

        public static Result of(Kind kind) {
            return new Result(kind, "");
        }

        @Override
        public String toString() {
            return kind + (note.isEmpty() ? "" : "：" + note);
        }
    }

    /**
     * 传一个文件。实现方负责写 `partPath`，成功后再改名到最终路径。
     *
     * @param item       待传项（句柄、相对路径、期望大小）
     * @param partPath   半成品落盘路径（已由 {@link Journal#partPath} 算好）
     * @param resumeFrom 已落盘字节数，0 表示从头开始
     * @param token      取消令牌；实现方必须在文件内注册中断动作（见 {@link CancelToken}）
     */
    Result transfer(ImportPlan.Item item, String partPath, long resumeFrom, CancelToken token);
}
