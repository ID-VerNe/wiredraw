package com.rea.mtpimport;

/**
 * 断点续传的**契约**。本轮只有接口与占位实现，没有真实现。
 *
 * 为什么不做真实现：`MtpDevice.getPartialObject(handle, offset, size, buffer)`
 * 对 a7C 的 `0xB101` 对象是否支持 offset 续读、超界时是报 `InvalidParameter` 还是
 * 静默少传，**本机一次都没验证过**（探针只用了 `importFile()`，它没有 offset 语义）。
 * 写一个「看起来能续传」的实现比没有实现危险得多：它会让我们以为断点续传已经可用，
 * 而真机上可能写出一堆截断的 ARW。
 *
 * 真实现该做的三件事（留给后续 issue，先在真机把语义钉死）：
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
        /** 本轮框架的默认结果：没实现。 */
        NOT_IMPLEMENTED,
        /** 中途失败，`.part` 保留，下次可续。 */
        FAILED
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
     */
    Result transfer(ImportPlan.Item item, String partPath, long resumeFrom);

    /** 本轮交付的占位实现：明确说「没做」，不假装成功。 */
    final class NotImplemented implements ResumableTransfer {

        private final Log log;

        public NotImplemented(Log log) {
            this.log = log;
        }

        @Override
        public Result transfer(ImportPlan.Item item, String partPath, long resumeFrom) {
            String note = "续传实现待真机验证 getPartialObject 的 offset 语义，"
                    + "本轮只搭框架；目标 " + item.relPath + "（" + item.size + " 字节，"
                    + "已落盘 " + resumeFrom + "）";
            log.line("[未实现] " + note);
            return new Result(Kind.NOT_IMPLEMENTED, note);
        }
    }
}
