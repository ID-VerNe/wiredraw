package com.rea.mtpimport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 只读区间分块读原语（G11 / A10 / issue #221）。
 *
 * 预览链（`ThumbnailStore.fetchFull`）与任何未来的只读预览读都走这里，把
 * 「按 offset 分块、短返回累计、0 / 负返回判失败」收成一份实现；导入链不走这里
 * （它用 `importFile` 整文件下载后按字节数对账，见 `MtpTransfer`）。
 *
 * 只读：本类只接收调用方给的读取回调，不持设备、不发写操作。
 * 不 import android.*，可在 JVM 上用假 reader 直接测。
 */
public final class RangeRead {

    /** 单块读取；返回实际读到的字节数，0 / 负数表示该位置已无内容。 */
    public interface Reader {
        long read(long offset, int want, byte[] buffer) throws IOException;
    }

    private RangeRead() {
    }

    /**
     * 从 offset=0 读满 {@code size} 字节。
     *
     * 分块语义：每次请求 `min(chunk, size - offset)`；短返回（`0 < n < want`）按实际字节
     * 累计继续读，只有 `n <= 0` 才判失败。`size` 或 `chunk` 非正数直接返回 null。
     *
     * @return 完整字节；中途返回 0 / 负数时返回 null（调用方保留缩略图）
     */
    public static byte[] read(long size, int chunk, Reader reader) throws IOException {
        if (size <= 0 || chunk <= 0) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(size, 4L << 20));
        byte[] buffer = new byte[chunk];
        long offset = 0;
        while (offset < size) {
            int want = (int) Math.min((long) buffer.length, size - offset);
            long n = reader.read(offset, want, buffer);
            if (n <= 0) return null;
            int copy = (int) Math.min(n, (long) want);   // 超量返回按请求长度截断，防越界写
            out.write(buffer, 0, copy);
            offset += copy;
        }
        return out.toByteArray();
    }
}
