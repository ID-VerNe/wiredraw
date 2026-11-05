package com.rea.mtpimport;

import android.graphics.Bitmap;

/**
 * 照片详情页的全幅预览来源（issue #46）。
 *
 * 真实 MTP 路径没有实现：相机里的 ARW 还没取回本地，按 UI-SPEC U-51 只显示
 * `ARW · <size> MB` 胶囊。debug 的 `DemoImages` 实现本接口，返回 APK 里 demo 占位图的位图，
 * 让没有相机时也能验证照片页的信息层级。
 */
public interface PhotoPreview {

    /** 该条目的预览图；没有素材时返回 null（界面退回胶囊形态）。 */
    Bitmap previewFor(Catalog.Entry entry);
}
