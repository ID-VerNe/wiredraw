package com.rea.mtpimport;

import android.graphics.Bitmap;

/**
 * demo 素材契约（issue #46 / #59 / #61）。
 *
 * debug 的 `DemoImages` 实现本接口，返回 APK 里 demo 占位图的位图；由 `ThumbnailStore`
 * 同时供照片墙（降采样到 512 px）与看图页（整图）使用。真实 MTP 路径不注入实现，
 * 一律走相机只读的 `getThumbnail`。
 */
public interface PhotoPreview {

    /** 该条目的预览图；没有素材时返回 null（界面退回胶囊形态）。 */
    Bitmap previewFor(Catalog.Entry entry);
}
