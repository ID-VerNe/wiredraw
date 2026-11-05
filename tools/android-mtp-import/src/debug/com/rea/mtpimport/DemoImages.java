package com.rea.mtpimport;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * debug 的 demo 照片预览（issue #46）。
 *
 * 素材由 Gradle 的 `generateDemoPhotos` 从本机照片降采样写入 `src/debug/assets/demo/`，
 * 产物 gitignore，不随仓库分发。这里按文件名排序后与假树 handle 做稳定映射，
 * 保证同一张照片每次打开都对应同一张图。
 *
 * 素材缺失（别的机器 / CI）时 {@link #create(Context)} 返回 null，界面退回无图胶囊。
 * release 源集里没有本类，`Main` 反射加载失败即静默跳过（U-54）。
 */
public final class DemoImages implements PhotoPreview {

    private static final String DIR = "demo";

    private final AssetManager assets;
    private final List<String> names = new ArrayList<>();
    /** 5 张图，缓存 8 张足够；每张 1440 长边约 5.5 MB 位图，不重复解码。 */
    private final LruCache<Integer, Bitmap> cache = new LruCache<>(8);

    private DemoImages(Context context) {
        assets = context.getAssets();
        String[] list = null;
        try {
            list = assets.list(DIR);
        } catch (IOException ignored) {
            // 目录不存在按无素材处理
        }
        if (list != null) {
            Arrays.sort(list);
            for (String name : list) {
                if (name.toLowerCase().endsWith(".jpg")) names.add(DIR + "/" + name);
            }
        }
    }

    /** 有素材时返回预览来源；素材目录为空时返回 null。 */
    public static PhotoPreview create(Context context) {
        DemoImages images = new DemoImages(context);
        return images.names.isEmpty() ? null : images;
    }

    @Override
    public Bitmap previewFor(Catalog.Entry entry) {
        if (entry == null || names.isEmpty()) return null;
        int index = DemoTree.previewIndex(entry.handle, names.size());
        if (index < 0) return null;
        Bitmap cached = cache.get(index);
        if (cached != null) return cached;
        try (InputStream in = assets.open(names.get(index))) {
            Bitmap bitmap = BitmapFactory.decodeStream(in);
            if (bitmap != null) cache.put(index, bitmap);
            return bitmap;
        } catch (IOException e) {
            return null;
        }
    }
}
