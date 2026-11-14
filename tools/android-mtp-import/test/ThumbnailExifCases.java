package com.rea.mtpimport;

import java.io.ByteArrayOutputStream;
import java.io.File;

import static com.rea.mtpimport.TestSupport.*;
import static com.rea.mtpimport.SourceGuard.*;

/**
 * 网格缩略图的 EXIF 姿态（issue #367 第二代修复）。
 *
 * 上一轮回签的根因不是「没读 EXIF」，而是**读错了字节源**：`decode` 只读传入字节自身的 EXIF，
 * 而网格的字节源是 MTP 内嵌缩略图，那份 JPEG 不带 APP1，`ExifInterface` 只能回落「正常」；
 * 大图链路拿的是完整 JPG（`Orientation=8`），于是出现「同一张图，网格横躺、点开正立」。
 *
 * 这一组断言分两条腿：
 * ① `ExifOrientation.parse` 是纯 JDK 的真值解析器，用**合成 JPEG 头**直接喂，逐项验小端 / 大端 /
 *    无 APP1 / APP1 但不是 EXIF / IFD 截断 / 非法值 / 类型不符——它一旦判错，界面就会把正立的照片转歪；
 * ② `ThumbnailStore` 是 Android 类（起不了 JVM 实例），所以用源级护栏钉住接线：字节源没 EXIF 时
 *    必须去查单元姿态、姿态只读 8 KiB 头而不是整张、姿态缓存随换会话 / 清空一起失效。
 */
final class ThumbnailExifCases {

    private static final String REL_STORE = "com/rea/mtpimport/ThumbnailStore.java";

    /** 竖拍（顺时针 90°）。 */
    private static final int ROTATE_90 = 6;
    /** 竖拍（逆时针 90°）——真机 `DSC04948.JPG` 就是这一档。 */
    private static final int ROTATE_270 = 8;

    static void thumbnailExifCases() {
        parseLittleEndian();
        parseBigEndian();
        parseMissingAndBroken();
        parseTruncation();
        storeWiringGuards();
    }

    // ---------------- 解析器真值 ----------------

    private static void parseLittleEndian() {
        check("EXIF 姿态：小端 IFD0 的 Orientation=8 读成 8（#367）",
                ExifOrientation.parse(jpeg(true, ROTATE_270, true)) == ROTATE_270,
                "读到 " + ExifOrientation.parse(jpeg(true, ROTATE_270, true)));

        check("EXIF 姿态：小端 IFD0 的 Orientation=6 读成 6（#367）",
                ExifOrientation.parse(jpeg(true, ROTATE_90, true)) == ROTATE_90,
                "读到 " + ExifOrientation.parse(jpeg(true, ROTATE_90, true)));

        check("EXIF 姿态：小端 Orientation=1 读成 1 而不是「无 EXIF」（#367）",
                ExifOrientation.parse(jpeg(true, ExifOrientation.NORMAL, true)) == ExifOrientation.NORMAL,
                "读到 " + ExifOrientation.parse(jpeg(true, ExifOrientation.NORMAL, true)));

        check("EXIF 姿态：段在但没有 Orientation tag 时按正常朝向（#367）",
                ExifOrientation.parse(jpeg(true, 0, false)) == ExifOrientation.NORMAL,
                "读到 " + ExifOrientation.parse(jpeg(true, 0, false)));
    }

    private static void parseBigEndian() {
        check("EXIF 姿态：大端 IFD0 的 Orientation=6 读成 6（#367）",
                ExifOrientation.parse(jpeg(false, ROTATE_90, true)) == ROTATE_90,
                "读到 " + ExifOrientation.parse(jpeg(false, ROTATE_90, true)));

        check("EXIF 姿态：大端 Orientation=8 读成 8（#367）",
                ExifOrientation.parse(jpeg(false, ROTATE_270, true)) == ROTATE_270,
                "读到 " + ExifOrientation.parse(jpeg(false, ROTATE_270, true)));
    }

    private static void parseMissingAndBroken() {
        check("EXIF 姿态：MTP 内嵌缩略图那种「只有 APP0 直接到图像数据」的字节判为无 EXIF（#367）",
                ExifOrientation.parse(thumbnailLike()) == ExifOrientation.NO_EXIF,
                "读到 " + ExifOrientation.parse(thumbnailLike()));

        check("EXIF 姿态：APP1 是 XMP 时跳过、继续找后面的 EXIF APP1（#367）",
                ExifOrientation.parse(xmpThenExif()) == ROTATE_270,
                "读到 " + ExifOrientation.parse(xmpThenExif()));

        check("EXIF 姿态：非 JPEG（PNG 头）判为无 EXIF（#367）",
                ExifOrientation.parse(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A}) == ExifOrientation.NO_EXIF,
                "非 JPEG 被判出姿态");

        check("EXIF 姿态：null / 空 / 过短不抛异常且判为无 EXIF（#367）",
                ExifOrientation.parse(null) == ExifOrientation.NO_EXIF
                        && ExifOrientation.parse(new byte[0]) == ExifOrientation.NO_EXIF
                        && ExifOrientation.parse(new byte[] {(byte) 0xFF, (byte) 0xD8}) == ExifOrientation.NO_EXIF,
                "畸形输入没有稳定返回 NO_EXIF");

        check("EXIF 姿态：非法值 9 按正常朝向处理，不拿去旋转（#367）",
                ExifOrientation.parse(jpeg(true, 9, true)) == ExifOrientation.NORMAL,
                "读到 " + ExifOrientation.parse(jpeg(true, 9, true)));

        check("EXIF 姿态：Orientation 的类型不是 SHORT 时按正常朝向（#367）",
                ExifOrientation.parse(jpegWrongType()) == ExifOrientation.NORMAL,
                "读到 " + ExifOrientation.parse(jpegWrongType()));

        check("EXIF 姿态：TIFF 字节序标记不对时判为无 EXIF（#367）",
                ExifOrientation.parse(jpegBadByteOrder()) == ExifOrientation.NO_EXIF,
                "读到 " + ExifOrientation.parse(jpegBadByteOrder()));

        check("EXIF 姿态：APP1 声明长度超过手上字节、但 IFD0 完整时仍能读出姿态（#367）",
                ExifOrientation.parse(jpegOverlongApp1()) == ROTATE_270,
                "读到 " + ExifOrientation.parse(jpegOverlongApp1()));
    }

    private static void parseTruncation() {
        byte[] full = jpeg(true, ROTATE_270, true);
        // 掐在 IFD0 entry 中间（entry 占 12 字节，这里让它在可用字节之外结束）：
        // 这时既不能报「正常」（会把竖拍转丢），也不能瞎猜一个角度。
        byte[] cut = new byte[full.length - 14];
        System.arraycopy(full, 0, cut, 0, cut.length);
        check("EXIF 姿态：IFD0 被截断时判为无 EXIF，而不是编一个朝向（#367）",
                ExifOrientation.parse(cut) == ExifOrientation.NO_EXIF,
                "读到 " + ExifOrientation.parse(cut));
    }

    // ---------------- 接线护栏 ----------------

    private static void storeWiringGuards() {
        File store = source(REL_STORE);
        String src = readSourceOrNull(store);
        if (src == null) {
            check("缩略图姿态：能读到 ThumbnailStore 源码（#367）", false, "读不到 " + store);
            return;
        }
        String fromCamera = slice(src, "private Bitmap fromCamera(", "private Bitmap thumbnail(");
        String thumbnail = slice(src, "private Bitmap thumbnail(", "private Bitmap applyUnitOrientation(");
        if (fromCamera == null || thumbnail == null) {
            check("缩略图姿态：ThumbnailStore 的方法边界可切片（#367）", false,
                    "切片失败：fromCamera=" + (fromCamera == null) + " thumbnail=" + (thumbnail == null));
            return;
        }

        check("缩略图姿态：网格链路在字节源无 EXIF 时去查单元姿态（#367）",
                src.contains("ExifOrientation.parse(bytes) != ExifOrientation.NO_EXIF")
                        && src.contains("unitOrientation(s, orientationFrom)"),
                "没有「字节无 EXIF → 查单元姿态」的分支");

        check("缩略图姿态：姿态来源是单元里的同名 JPG，ARW 回退也复用它（#367）",
                fromCamera.contains("thumbnail(s, unit.jpg, unit.jpg)")
                        && fromCamera.contains("thumbnail(s, unit.arw, unit.jpg)"),
                "ARW 回退没有沿用同名 JPG 的姿态来源");

        check("缩略图姿态：取姿态只读文件头 8 KiB，不读整张（#367）",
                src.contains("ExifOrientation.HEAD_BYTES")
                        && src.contains("Math.min(ExifOrientation.HEAD_BYTES, entry.size)")
                        && !src.contains("new byte[(int) entry.size]"),
                "头部读的上限没有钉在 HEAD_BYTES");

        check("缩略图姿态：网格链路仍然不碰 fetchFull（#367 带宽红线）",
                !fromCamera.contains("fetchFull(") && !thumbnail.contains("fetchFull("),
                "网格链路出现了整张读取");

        check("缩略图姿态：姿态缓存随换会话与清空一起失效（#367）",
                countLines(src, "orientationCache.evictAll();") == 2,
                "orientationCache.evictAll() 出现 "
                        + countLines(src, "orientationCache.evictAll();") + " 次（应为 2：setSession / clear）");

        // 顺序也重要：清理必须在临界区**内**且排在自增代际之后。只清不锁会留下「在飞写入」的窗口，
        // 而 countLines 那类断言看不出顺序（评审第二轮阻断项就是这么漏掉的）。
        check("缩略图姿态：setSession 的姿态清理在临界区内、排在自增代际之后（#367）",
                insideLockAfterBump(slice(src, "public void setSession(", "public void setDemo(")),
                "setSession 里 orientationCache.evictAll() 不在 epochLock 内或早于 epoch++");

        check("缩略图姿态：clear 的姿态清理同样在临界区内（#367）",
                insideLockAfterBump(slice(src, "public void clear()", "public void detachSession()")),
                "clear 里 orientationCache.evictAll() 不在 epochLock 内");

        String unitOrientation = slice(src, "private int unitOrientation(", "private byte[] fetchHead(");
        check("缩略图姿态：姿态缓存写入过代际校验，挡住在飞写入（#367）",
                unitOrientation != null
                        && unitOrientation.contains("int startEpoch = epoch;")
                        && unitOrientation.contains("commit(startEpoch, jpg.handle, orientationCache")
                        && !unitOrientation.contains("orientationCache.put("),
                unitOrientation == null ? "切片失败：unitOrientation 到 fetchHead" : "缺少代际校验或绕过了 commit");

        // 光有「取过代际」还不够：捕获点必须读头**之前**。挪到 fetchHead 之后就等于把竞态重开——
        // 读头期间换会话，拿旧代际去 commit 仍然通过（#395，第三轮评审变异 E 就是这么绕过护栏的）。
        String unitBody = methodBody(src, "private int unitOrientation(");
        check("缩略图姿态：代际值在读头之前捕获（#395）",
                capturedBeforeRead(unitBody),
                captureFailureDetail(unitBody));

        check("缩略图姿态：字节源自带 EXIF 时不再二次旋转（#367）",
                src.contains("if (ExifOrientation.parse(bytes) != ExifOrientation.NO_EXIF) return decoded;"),
                "缺少「自带 EXIF 直接返回」的早退");

        check("缩略图姿态：大图链路仍走原 ExifInterface 纠偏，未被改动（#367）",
                src.contains("applyExifOrientation(decoded, bytes)"),
                "decode 里的 applyExifOrientation 调用不在了");
    }

    /**
     * 某段方法体里「清姿态缓存」是否真的在 `epochLock` 临界区**内**。
     *
     * 只数「有几处 `evictAll`」看不出位置，只比较「有没有出现在 `epoch++` 之后」也不够——
     * 把语句挪到同步块**之后**同样满足「在后面」（第一版这么写，变异验证时照样全绿，被抓出来了）。
     * 所以按**精确缩进**判定：方法体一级缩进 8 空格，块内语句是 12 空格；这句必须恰好落在 12 空格，
     * 且必须排在自增代际之后（与 `clear` 同构，评审第二轮阻断项）。
     */
    private static boolean insideLockAfterBump(String body) {
        if (body == null) return false;
        int bump = body.indexOf("epoch++;");
        if (bump < 0) return false;
        boolean seen = false;
        for (String line : body.split("\n", -1)) {
            if (!line.trim().equals("orientationCache.evictAll();")) continue;
            if (!line.equals("            orientationCache.evictAll();")) return false;   // 缩进不在块内
            if (body.indexOf(line) < bump) return false;                                   // 早于自增代际
            seen = true;
        }
        return seen;
    }

    /**
     * 取一个方法的**完整方法体**：从签名到与之配对的大括号。
     *
     * 不能用「下一个方法签名」当终点：中间插入任何方法都会把它的内容并进切片，
     * 于是 `indexOf("fetchHead(")` 可能命中插入方法自己内部的调用，判据就被静默绕过
     * （#396 评审阻断项）。按大括号配对取的才是这个方法自己的语句。
     */
    private static String methodBody(String src, String signature) {
        if (src == null) return null;
        int start = src.indexOf(signature);
        if (start < 0) return null;
        int open = src.indexOf('{', start);
        if (open < 0) return null;
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(start, i + 1);
            }
        }
        return null;
    }

    /**
     * `unitOrientation` 方法体里「取代际」是否发生在读头**之前**（issue #395）。
     *
     * 只断言「这行存在」挡不住顺序：把 `int startEpoch = epoch;` 挪到读头之后，
     * 读到的就是**读头之后**的代际，读头期间换了会话也照样判「同代」——竞态被原样重开。
     */
    private static boolean capturedBeforeRead(String body) {
        if (body == null) return false;
        int capture = body.indexOf("int startEpoch = epoch;");
        int read = body.indexOf("fetchHead(");
        return capture >= 0 && read > capture;
    }

    /** 失败文案要能区分「判据找不到锚点」与「捕获点真的在后面」——否则报错会与事实相反。 */
    private static String captureFailureDetail(String body) {
        if (body == null) return "切片失败：取不到 unitOrientation 的方法体";
        if (!body.contains("fetchHead(")) {
            return "unitOrientation 方法体里没有 fetchHead( 调用——若已把读头抽成辅助方法，请同步更新本判据";
        }
        if (!body.contains("int startEpoch = epoch;")) {
            return "unitOrientation 方法体里没有 int startEpoch = epoch;（改名或挪走了）";
        }
        return "int startEpoch = epoch; 出现在 fetchHead( 之后";
    }

    // ---------------- 合成 JPEG 头 ----------------    /** 一个最小可解析的 JPEG 头：SOI + APP0 + APP1(Exif) + SOS 之前的停止点。 */
    private static byte[] jpeg(boolean little, int orientation, boolean withTag) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);                      // SOI
        app0(out);
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        tiff.write(little ? 'I' : 'M');
        tiff.write(little ? 'I' : 'M');
        put16(tiff, 42, little);              // TIFF magic
        put32(tiff, 8, little);               // IFD0 offset
        int count = withTag ? 1 : 0;
        put16(tiff, count, little);
        if (withTag) {
            put16(tiff, 0x0112, little);      // tag: Orientation
            put16(tiff, 3, little);           // type: SHORT
            put32(tiff, 1, little);           // count
            put16(tiff, orientation, little);
            put16(tiff, 0, little);           // value 字段补齐到 4 字节
        }
        put32(tiff, 0, little);               // 没有下一个 IFD
        app1Exif(out, tiff.toByteArray());
        out.write(0xFF);
        out.write(0xDA);                      // SOS
        put16(out, 12, little);
        return out.toByteArray();
    }

    /** 只有 APP0 就进图像数据：MTP 内嵌缩略图的典型形状。 */
    private static byte[] thumbnailLike() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        app0(out);
        out.write(0xFF);
        out.write(0xDB);                      // DQT
        put16(out, 67, true);
        for (int i = 0; i < 65; i++) out.write(0);
        out.write(0xFF);
        out.write(0xDA);                      // SOS
        put16(out, 12, true);
        return out.toByteArray();
    }

    /** 先一个 XMP 的 APP1，再一个 EXIF 的 APP1：解析器必须跳过前者。 */
    private static byte[] xmpThenExif() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        app0(out);
        byte[] xmp = "http://ns.adobe.com/xap/1.0/\0<x/>".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(0xFF);
        out.write(0xE1);
        put16(out, 2 + xmp.length, false);
        out.write(xmp, 0, xmp.length);
        out.write(jpeg(true, ROTATE_270, true), 2, jpeg(true, ROTATE_270, true).length - 2);   // 去掉 SOI 的其余部分
        return out.toByteArray();
    }

    /** APP1 的声明长度远大于实际字节（只读了文件头时就是这样），IFD0 仍完整。 */
    private static byte[] jpegOverlongApp1() {
        byte[] full = jpeg(true, ROTATE_270, true);
        // 找到 APP1 长度字段并把它改大，模拟「段还有后续 maker note 没读进来」。
        int i = 4;                            // SOI 之后是 APP0
        while (i + 3 < full.length) {
            int marker = full[i + 1] & 0xFF;
            int len = ((full[i + 2] & 0xFF) << 8) | (full[i + 3] & 0xFF);
            if (marker == 0xE1) {
                full[i + 2] = (byte) 0x40;    // 0x4000 = 16384，远超手里字节
                full[i + 3] = (byte) 0x00;
                return full;
            }
            i += 2 + len;
        }
        return full;
    }

    private static byte[] jpegWrongType() {
        byte[] full = jpeg(true, ROTATE_270, true);
        // type 字段在 tag 之后 2 字节；改成 4(LONG)。
        int tagAt = indexOfOrientationTag(full);
        if (tagAt < 0) return full;
        full[tagAt + 2] = 4;
        full[tagAt + 3] = 0;
        return full;
    }

    private static byte[] jpegBadByteOrder() {
        byte[] full = jpeg(true, ROTATE_270, true);
        int tiff = indexOfTiffHeader(full);
        if (tiff < 0) return full;
        full[tiff] = 'X';
        full[tiff + 1] = 'X';
        return full;
    }

    private static int indexOfOrientationTag(byte[] b) {
        for (int i = 0; i + 1 < b.length; i++) {
            if ((b[i] & 0xFF) == 0x12 && (b[i + 1] & 0xFF) == 0x01) return i;   // 小端 0x0112
        }
        return -1;
    }

    private static int indexOfTiffHeader(byte[] b) {
        for (int i = 0; i + 7 < b.length; i++) {
            if (b[i] == 'I' && b[i + 1] == 'I' && b[i + 2] == 42 && b[i + 3] == 0) return i;
        }
        return -1;
    }

    private static void app0(ByteArrayOutputStream out) {
        out.write(0xFF);
        out.write(0xE0);
        put16(out, 16, false);
        out.write('J');
        out.write('F');
        out.write('I');
        out.write('F');
        out.write(0);
        out.write(1);
        out.write(1);
        out.write(0);
        put16(out, 1, false);
        put16(out, 1, false);
        out.write(0);
        out.write(0);
    }

    private static void app1Exif(ByteArrayOutputStream out, byte[] tiff) {
        out.write(0xFF);
        out.write(0xE1);
        put16(out, 2 + 6 + tiff.length, false);
        out.write('E');
        out.write('x');
        out.write('i');
        out.write('f');
        out.write(0);
        out.write(0);
        out.write(tiff, 0, tiff.length);
    }

    private static void put16(ByteArrayOutputStream out, int v, boolean little) {
        if (little) {
            out.write(v & 0xFF);
            out.write((v >> 8) & 0xFF);
        } else {
            out.write((v >> 8) & 0xFF);
            out.write(v & 0xFF);
        }
    }

    private static void put32(ByteArrayOutputStream out, int v, boolean little) {
        if (little) {
            out.write(v & 0xFF);
            out.write((v >> 8) & 0xFF);
            out.write((v >> 16) & 0xFF);
            out.write((v >> 24) & 0xFF);
        } else {
            out.write((v >> 24) & 0xFF);
            out.write((v >> 16) & 0xFF);
            out.write((v >> 8) & 0xFF);
            out.write(v & 0xFF);
        }
    }
}
