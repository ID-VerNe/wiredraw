package com.rea.mtpimport;

import java.util.function.LongConsumer;

/**
 * 传输**契约**。PA / PB 的 {@link MtpTransfer} 只做整文件传输与取消。
 *
 * **续传已停用（止血，issue #177）**：issue #67 曾按 `tools/android-mtp-probe` 的结论
 * 认为 `getPartialObject` 的 offset 是绝对偏移；但 2026-11-07 的真机复测（小米 9，
 * Android 15，USB MTP）显示 offset 不生效，reader 忽略偏移、从对象起点顺序给数据。
 * 继续把 `.part` 当可信前缀 append 会得到「字节数对、内容错、日志打 OK」的文件。
 * 因此本契约里的 `resumeFrom` 只保留字段兼容，恒为 0；实现方不得据此续读。
 *
 * 实现契约：
 *
 * - **旧 `.part` 一律作废**：传输前删除任何既有半成品（内容不可验证），再从 0 整段重写。
 *   删除失败必须判 FAILED，不能跳过后继续写。
 * - **跨会话身份**：MTP 的 object handle 不跨会话稳定；半成品 `.part` 仍按 `relPath + size`
 *   命名（{@link Journal#partPath}），但名字只用于临时落盘，不再承载「已传前缀」语义。
 * - **对账**：落盘字节必须等于 `ImportPlan.Item.size`；不等就判 FAILED 并保留 `.part`。
 *   尺寸一致只是必要条件（内容未校验），日志/结果文案必须如实说明。
 */
public interface ResumableTransfer {

    enum Kind {
        /** 整个文件传完并已落盘（仅尺寸核对通过，内容未校验）。 */
        OK,
        /** 成品已存在同尺寸文件或账本已确认，本轮未写数据。 */
        SKIPPED,
        /** 中途失败，`.part` 保留，下次从 0 重写（不再续传，#177）。 */
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
     * @param partPath   半成品落盘路径（已由 {@link Journal#partPath} 按 relPath + size 算好，
 *                   跨会话稳定）
     * @param resumeFrom 恒为 0（#177 续传停用）。实现方必须忽略它并删除既有 `.part`；
     *                   非 0 值只用于把「谁还在试图续传」记进日志
     * @param token      取消令牌；实现方必须在文件内注册中断动作（见 {@link CancelToken}）
     * @param progress   issue #70：当前文件的**绝对字节偏移**回调。整文件 `importFile`
     *                   路径不支持文件内上报，可以不调用，由编排层在文件边界记账
     */
    Result transfer(ImportPlan.Item item, String partPath, long resumeFrom, CancelToken token,
                    LongConsumer progress);
}
