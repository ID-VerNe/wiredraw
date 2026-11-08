import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

// demo 照片占位（issue #46；#207 从 build.gradle.kts 拆出）。
//
// Compose 假数据只有元数据，photo 页没有像素。这里在 **debug 构建** 时从本机素材目录
// 降采样生成 `src/debug/assets/demo/photo_XX.jpg`，产物 gitignore；素材目录/文件清单走
// `local.properties`（也可被 `-Pdemo.photo.dir` / `-Pdemo.photo.files` 覆盖）：
//   demo.photo.dir=C:/<照片目录>
//   demo.photo.files=DSC04882.JPG,DSC04835.JPG,...   # 可选，留空取目录里前 5 张 JPG
//
// 个人照片不入库；目录缺失时清空产物并跳过，别的机器 / CI 上没有素材也能跑 manual 门禁。
// ARW 不进 APK：假树仍保留 ARW 条目与真实大小，详情页预览用同名 JPG 的降采样图。

@Suppress("UNCHECKED_CAST")
val props = extra["localProperties"] as Map<String, String>
val demoPhotoSource = providers.gradleProperty("demo.photo.dir").orNull?.trim()
    ?: props["demo.photo.dir"].orEmpty()
val demoPhotoFiles = providers.gradleProperty("demo.photo.files").orNull?.trim()
    ?: props["demo.photo.files"].orEmpty()
val demoPhotoOutput = file("src/debug/assets/demo")

/** 读 JPEG 头部 APP1 的 EXIF Orientation；读不到按 1（正常）。 */
fun readExifOrientation(jpeg: File): Int = try {
    val bytes = jpeg.inputStream().use { it.readNBytes(128 * 1024) }
    val marker = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII)

    var tiff = -1
    for (i in 0..(bytes.size - marker.size)) {
        if (marker.indices.all { bytes[i + it] == marker[it] }) {
            tiff = i + marker.size
            break
        }
    }
    if (tiff < 0 || tiff + 8 > bytes.size) {
        1
    } else {
        val little = bytes[tiff] == 'I'.code.toByte()
        fun u16(o: Int): Int {
            val a = bytes[o].toInt() and 0xFF
            val b = bytes[o + 1].toInt() and 0xFF
            return if (little) a or (b shl 8) else (a shl 8) or b
        }
        fun u32(o: Int): Int = if (little) {
            u16(o) or (u16(o + 2) shl 16)
        } else {
            (u16(o) shl 16) or u16(o + 2)
        }
        val ifd = tiff + u32(tiff + 4)
        if (ifd < 0 || ifd + 2 > bytes.size) {
            1
        } else {
            var value = 1
            val count = u16(ifd)
            for (k in 0 until count) {
                val entry = ifd + 2 + k * 12
                if (entry + 12 > bytes.size) break
                if (u16(entry) == 0x0112) {
                    value = u16(entry + 8)
                    break
                }
            }
            value
        }
    }
} catch (t: Throwable) {
    println("  demo 照片：读 EXIF 失败（${jpeg.name}），按正常方向处理：$t")
    1
}

/** 按 EXIF Orientation（1–8）把像素转正。相机竖拍常靠 tag 旋转，不转会躺倒。 */
fun orientImage(src: BufferedImage, orientation: Int): BufferedImage {
    val w = src.width.toDouble()
    val h = src.height.toDouble()
    val (dw, dh) = if (orientation in 5..8) h.toInt() to w.toInt() else src.width to src.height
    val matrix = when (orientation) {
        2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, w, 0.0)
        3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, w, h)
        4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, h)
        5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
        6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, h, 0.0)
        7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, h, w)
        8 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, w)
        else -> return src
    }
    val dst = BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB)
    val g = dst.createGraphics()
    g.drawImage(src, matrix, null)
    g.dispose()
    return dst
}

/** 最长边压到 maxEdge 以内（只缩小不放大）。 */
fun scaleToMaxEdge(src: BufferedImage, maxEdge: Int): BufferedImage {
    val longest = maxOf(src.width, src.height)
    if (longest <= maxEdge) return src
    val ratio = maxEdge.toDouble() / longest
    val w = (src.width * ratio).toInt().coerceAtLeast(1)
    val h = (src.height * ratio).toInt().coerceAtLeast(1)
    val dst = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = dst.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.drawImage(src, 0, 0, w, h, null)
    g.dispose()
    return dst
}

fun writeJpeg(img: BufferedImage, out: File, quality: Float) {
    val writer = ImageIO.getImageWritersByFormatName("jpg").next()
    val param = writer.defaultWriteParam.apply {
        compressionMode = ImageWriteParam.MODE_EXPLICIT
        compressionQuality = quality
    }
    ImageIO.createImageOutputStream(out).use { stream ->
        writer.output = stream
        writer.write(null, IIOImage(img, null, null), param)
    }
    writer.dispose()
}

val generateDemoPhotos = tasks.register("generateDemoPhotos") {
    group = "build"
    description = "把本机真实照片降采样到 src/debug/assets/demo（产物 gitignore；目录缺失时跳过）"
    doLast {
        // 先清空：素材目录被移除后不能留着上次的产物继续进 APK
        demoPhotoOutput.deleteRecursively()
        if (demoPhotoSource.isEmpty() || !File(demoPhotoSource).isDirectory) {
            println("  demo 照片：未配置 demo.photo.dir 或目录不存在，跳过（photo 页退回无图胶囊）")
            return@doLast
        }
        val sourceDir = File(demoPhotoSource)
        val picks: List<File> = if (demoPhotoFiles.isNotEmpty()) {
            demoPhotoFiles.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { File(sourceDir, it) }
        } else {
            sourceDir.listFiles { f: File -> f.isFile && f.extension.equals("jpg", true) }
                ?.sortedBy { it.name }?.take(5) ?: emptyList()
        }
        val valid = picks.filter { it.isFile }
        if (valid.isEmpty()) {
            println("  demo 照片：demo.photo.files 里没有可读的 JPG，跳过（photo 页退回无图胶囊）")
            return@doLast
        }
        demoPhotoOutput.mkdirs()
        valid.forEachIndexed { index, photo ->
            val src = ImageIO.read(photo)
            if (src == null) {
                println("  demo 照片：跳过无法解码的 ${photo.name}")
                return@forEachIndexed
            }
            val upright = orientImage(src, readExifOrientation(photo))
            val scaled = scaleToMaxEdge(upright, 1440)
            val out = File(demoPhotoOutput, "photo_%02d.jpg".format(index + 1))
            writeJpeg(scaled, out, 0.8f)
            println("  demo 照片：${photo.name} → ${out.name}（${scaled.width}×${scaled.height}，${out.length() / 1024} KB）")
        }
    }
}

// 素材生成只影响 debug 的 assets 目录；挂在 preBuild 上保证 assembleDebug 前产物就位。
// release 也会触发一次（没有 local.properties 时直接 skip），不会把照片打进 release。
tasks.named("preBuild") { dependsOn(generateDemoPhotos) }
