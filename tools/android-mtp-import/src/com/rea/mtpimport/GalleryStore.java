package com.rea.mtpimport;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 相册成品写入（issue #28 分节 PB）：手机侧 MediaStore IO，Android 侧专用，不进纯 JDK 自检。
 *
 * 相机侧只读红线不受影响：这里只写手机自己的共享存储，不向 MTP / PTP opcode 链发任何命令。
 * 手机侧已证 `MediaStore.Pictures` 写自建文件免运行时权限、`.ARW` 以 `image/x-sony-arw`
 * 可入 images 表；**跨 provider 的流式复制仍未真机验证**（issue #28 留 `needs-live-verify`）。
 *
 * 状态目录（Journal / `.part`）恒在私有 `getFilesDir()/imported`，只有成品走这里，
 * 两棵根不混用，换成品去向不会丢导入记录。`RELATIVE_PATH` / `DISPLAY_NAME` 由
 * `DestPolicy.galleryRelativeDir` / `DestPolicy.galleryDisplayName` 派生，MIME 由
 * `DestPolicy.galleryMime` 决定。
 */
public final class GalleryStore {

    private final ContentResolver resolver;
    private final Log log;

    public GalleryStore(ContentResolver resolver, Log log) {
        this.resolver = resolver;
        this.log = log;
    }

    /**
     * 查同名相册条目的尺寸；&lt;0 表示不存在 / 查询失败。
     *
     * 供 `SkipRule.ProductProbe` 与落盘前判重用：同尺寸视为已完成、异尺寸拒绝覆盖。
     * 查询失败按「不存在」处理，调用方会重新导入而不是静默跳过（#177 口径）。
     */
    public long existingSize(String relPath) {
        String dir = DestPolicy.galleryRelativeDir(relPath);
        String name = DestPolicy.galleryDisplayName(relPath);
        if (dir == null || name == null) return -1L;
        Cursor c = null;
        try {
            c = resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media.SIZE},
                    MediaStore.Images.Media.RELATIVE_PATH + "=? AND "
                            + MediaStore.Images.Media.DISPLAY_NAME + "=?",
                    new String[]{dir, name}, null);
            if (c == null || !c.moveToFirst()) return -1L;
            int idx = c.getColumnIndex(MediaStore.Images.Media.SIZE);
            return idx < 0 ? -1L : c.getLong(idx);
        } catch (Throwable t) {
            log.line("相册同名查询失败（按不存在处理）：" + t);
            return -1L;
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * 把已对账的 `.part` 流式复制进 MediaStore；返回 null 表示成功，非 null 是失败原因。
     *
     * 先插 `IS_PENDING=1` 的占位行，再用 `openOutputStream` 复制字节，最后清 `IS_PENDING`
     * 让条目对相册可见。任一步失败都删掉占位行并返回原因，由调用方回退私有并明确告知用户。
     * 跨 provider 不能用 `File.renameTo`：成品不在私有目录的文件视图里。
     */
    public String publish(File part, String relPath) {
        String dir = DestPolicy.galleryRelativeDir(relPath);
        String name = DestPolicy.galleryDisplayName(relPath);
        String mime = DestPolicy.galleryMime(relPath);
        if (dir == null || name == null) return "相册目标路径越界：" + relPath;
        if (mime == null) return "相册不支持该文件类型：" + relPath;

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, mime);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, dir);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = null;
        try {
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return "MediaStore 无法创建条目";
            try (InputStream in = new FileInputStream(part);
                 OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IOException("openOutputStream 返回 null");
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (resolver.update(uri, done, null, null) < 1) {
                throw new IOException("清 IS_PENDING 失败");
            }
            return null;
        } catch (Throwable t) {
            if (uri != null) {
                try {
                    resolver.delete(uri, null, null);
                } catch (Throwable ignored) {
                    log.line("相册 pending 行清理失败：" + uri);
                }
            }
            return "相册写入失败：" + t;
        }
    }
}
