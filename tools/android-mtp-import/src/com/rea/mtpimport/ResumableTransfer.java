package com.rea.mtpimport;

/**
 * 断点续传的**契约**。PA / PB 的 {@link MtpTransfer} 先只做整文件传输与取消；
 * PC 分节（issue #50）已按下面钉死的语义补上 offset 续读。
 *
 * `MtpDevice.getPartialObject(handle, offset, size, buffer)` 在 ILCE-7C（固件 2.00）
 * 上的语义已由 `tools/android-mtp-probe` 实测（issue #67）：
 *
 * 1. **offset 是绝对偏移**：`offset=0` 连读两次拿到相同字节；1 MiB 分块拼满 24.4 MB ARW
 *    的字节数、MD5、TIFF 文件头与 `importFile` 完全一致（22.86 MB/s）。
 * 2. **边界**：`length` 超过剩余字节会截断到 EOF；`offset == size` 返回 0；
 *    `offset == size + 1` 抛 `IOException: Failed to read data.`（#67 首轮观察；越界后会话
 *    是否进入错误态本轮未复测、也未做会话探活）——实现必须保证 `0 <= offset <= size`
 *    且 `length <= size - offset`，永不越界。
 * 3. **支持面**：4 张 ARW + 1 张 JPEG 的首块都成功（普通对象都支持；`0xFFFFC001` 未测）。
 *
 * 实现契约：
 *
 * - **跨会话身份**：MTP 的 object handle 不跨会话稳定；半成品 `.part` 按 `relPath + size`
 *   命名（{@link Journal#partPath}），续传起点按磁盘实际长度（{@link Journal#resumeFrom}），
 *   不依赖 handle 的持久性。`size` 进文件名后，同路径异 size 的旧残留不会被误认。
 * - **从 resumeFrom 续读**：`getPartialObject(handle, resumeFrom, min(1 MiB, size - resumeFrom), buf)`
 *   循环，每块 append 到 `.part`，成功后 rename 成最终文件名。
 * - **对账**：落盘字节必须等于 `ImportPlan.Item.size`；不等就判 FAILED 并保留
 *   `.part`，不要静默当成成功。
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
     * @param partPath   半成品落盘路径（已由 {@link Journal#partPath} 按 relPath + size 算好，
 *                   跨会话稳定）
     * @param resumeFrom 已落盘字节数（由 {@link Journal#resumeFrom} 按磁盘实际长度判定），
     *                   0 表示从头开始；等于 item.size 表示半成品已满，只需落盘
     * @param token      取消令牌；实现方必须在文件内注册中断动作（见 {@link CancelToken}）
     */
    Result transfer(ImportPlan.Item item, String partPath, long resumeFrom, CancelToken token);
}
