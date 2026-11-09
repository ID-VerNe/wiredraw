package com.rea.mtpimport;

import java.io.File;
import java.nio.file.Files;

import static com.rea.mtpimport.TestSupport.*;

/**
 * 落点用户可选与成品去向（issue #28 分节 PA/PB）的断言组。
 *
 * #318 把 SelfTest.java 拆成 `test/*Cases.java` 后，原分支新增的
 * `locationChoiceCases` / `productDestinationCases` 落在本文件；
 * 与 {@link ConflictCases#destinationPolicyCases()} 同属落点决策主题，
 * 但这里只覆盖「用户可选相册」与「相册成品写入的机械可判部分」。
 */
final class DestCases {

    /** 落点用户可选（issue #28）：可用性注入、安全回退与偏好串解析，全部纯 JDK。 */
    static void locationChoiceCases() throws Exception {
        File filesDir = Files.createTempDirectory("wiredraw-loc").toFile();
        File privateRoot = new File(filesDir, "imported");

        DestPolicy.Destination allowed = DestPolicy.choose(filesDir, DestPolicy.Kind.GALLERY, true);
        check("落点可选：相册可用时尊重用户选择，且状态根恒私有",
                allowed.kind == DestPolicy.Kind.GALLERY
                        && !allowed.fellBack() && allowed.fallbackReason.isEmpty()
                        && allowed.root.getCanonicalFile().equals(privateRoot.getCanonicalFile()),
                "kind=" + allowed.kind + " root=" + allowed.root
                        + " reason=[" + allowed.fallbackReason + "]");

        DestPolicy.Destination denied = DestPolicy.choose(filesDir, DestPolicy.Kind.GALLERY, false);
        check("落点可选：相册不可用时回退私有且原因非空（不得静默）",
                denied.kind == DestPolicy.Kind.PRIVATE && denied.fellBack()
                        && denied.fallbackReason.contains("回退")
                        && denied.root.getCanonicalFile().equals(privateRoot.getCanonicalFile()),
                "kind=" + denied.kind + " reason=[" + denied.fallbackReason + "]");

        DestPolicy.Destination priv = DestPolicy.choose(filesDir, DestPolicy.Kind.PRIVATE, true);
        check("落点可选：默认私有不受相册可用性影响",
                priv.kind == DestPolicy.Kind.PRIVATE && !priv.fellBack(),
                "kind=" + priv.kind + " reason=[" + priv.fallbackReason + "]");

        DestPolicy.Destination legacy = DestPolicy.choose(filesDir, DestPolicy.Kind.GALLERY);
        check("落点可选：两参入口未提供可用性一律按不可用安全回退",
                legacy.kind == DestPolicy.Kind.PRIVATE && legacy.fellBack(),
                "kind=" + legacy.kind + " reason=[" + legacy.fallbackReason + "]");

        check("落点偏好：GALLERY 串解析成相册",
                DestPolicy.parseKind("GALLERY") == DestPolicy.Kind.GALLERY,
                "实际 " + DestPolicy.parseKind("GALLERY"));
        check("落点偏好：未知 / 空 / null 按默认私有",
                DestPolicy.parseKind("NOPE") == DestPolicy.Kind.PRIVATE
                        && DestPolicy.parseKind("") == DestPolicy.Kind.PRIVATE
                        && DestPolicy.parseKind(null) == DestPolicy.Kind.PRIVATE,
                "unknown=" + DestPolicy.parseKind("NOPE"));
        check("落点偏好：往返不丢用户选择",
                "GALLERY".equals(DestPolicy.storeKind(DestPolicy.Kind.GALLERY))
                        && DestPolicy.parseKind(DestPolicy.storeKind(DestPolicy.Kind.GALLERY))
                                == DestPolicy.Kind.GALLERY,
                "stored=" + DestPolicy.storeKind(DestPolicy.Kind.GALLERY));
        check("落点偏好：null 落点按默认私有落库",
                "PRIVATE".equals(DestPolicy.storeKind(null)),
                "实际 " + DestPolicy.storeKind(null));
    }

    /**
     * 落点写入链路（issue #28 分节 PB）：成品去向判定与回退原因，全部纯 JDK 可测。
     *
     * MediaStore 流式复制本身是 Android 侧 IO（真机待验），这里覆盖它能被机械判定的部分：
     * 状态根恒私有与成品去向解耦、`RELATIVE_PATH` / `DISPLAY_NAME` / MIME 派生、
     * 同名异尺寸拒绝覆盖、写入失败回退原因非空，以及跨 provider 后 {@link SkipRule}
     * 按注入探针判跳过（不把整文件重下）。
     */
    static void productDestinationCases() throws Exception {
        File filesDir = Files.createTempDirectory("wiredraw-product").toFile();
        long size = 24_000_000L;
        String rel = "DCIM/100MSDCF/DSC09782.ARW";

        DestPolicy.Destination g = DestPolicy.choose(filesDir, DestPolicy.Kind.GALLERY, true);
        check("成品去向：状态根恒私有，不随成品去向变（换根不丢 Journal）",
                g.root.getCanonicalFile().equals(new File(filesDir, "imported").getCanonicalFile()),
                "root=" + g.root);
        check("成品去向：kind 只表达成品去向",
                g.kind == DestPolicy.Kind.GALLERY, "kind=" + g.kind);

        check("成品去向：PRIVATE 镜像 relPath（与旧落点一致）",
                rel.equals(DestPolicy.productRelativePath(DestPolicy.Kind.PRIVATE, rel)),
                "实际 " + DestPolicy.productRelativePath(DestPolicy.Kind.PRIVATE, rel));
        check("成品去向：GALLERY 落在 Pictures/<relPath>",
                ("Pictures/" + rel).equals(DestPolicy.productRelativePath(DestPolicy.Kind.GALLERY, rel)),
                "实际 " + DestPolicy.productRelativePath(DestPolicy.Kind.GALLERY, rel));

        check("成品去向：绝对路径 / .. 段 / 空串返回 null",
                DestPolicy.productRelativePath(DestPolicy.Kind.GALLERY, "/etc/passwd") == null
                        && DestPolicy.productRelativePath(DestPolicy.Kind.PRIVATE, "DCIM/../x.ARW") == null
                        && DestPolicy.productRelativePath(DestPolicy.Kind.GALLERY, "") == null,
                "越界未被拦住");
        check("成品去向：文件名含 .. 但不是路径段不误杀",
                "Pictures/DCIM/A..B.ARW".equals(
                        DestPolicy.productRelativePath(DestPolicy.Kind.GALLERY, "DCIM/A..B.ARW")),
                "实际 " + DestPolicy.productRelativePath(DestPolicy.Kind.GALLERY, "DCIM/A..B.ARW"));

        check("成品去向：RELATIVE_PATH 带 Pictures 前缀与尾斜杠",
                "Pictures/DCIM/100MSDCF/".equals(DestPolicy.galleryRelativeDir(rel)),
                "实际 " + DestPolicy.galleryRelativeDir(rel));
        check("成品去向：DISPLAY_NAME 取文件名",
                "DSC09782.ARW".equals(DestPolicy.galleryDisplayName(rel)),
                "实际 " + DestPolicy.galleryDisplayName(rel));

        check("成品去向：ARW 以 image/x-sony-arw 入 images 表",
                "image/x-sony-arw".equals(DestPolicy.galleryMime("DCIM/X.ARW")),
                "实际 " + DestPolicy.galleryMime("DCIM/X.ARW"));
        check("成品去向：JPG 走 image/jpeg，未知类型返回 null",
                "image/jpeg".equals(DestPolicy.galleryMime("DCIM/X.JPG"))
                        && DestPolicy.galleryMime("DCIM/X.MP4") == null,
                "jpg=" + DestPolicy.galleryMime("DCIM/X.JPG")
                        + " mp4=" + DestPolicy.galleryMime("DCIM/X.MP4"));

        check("成品去向：相册无同名条目 → NONE",
                DestPolicy.existingBySize(-1L, size) == DestPolicy.Existing.NONE,
                "实际 " + DestPolicy.existingBySize(-1L, size));
        check("成品去向：相册同名同尺寸 → SAME_SIZE（不覆盖）",
                DestPolicy.existingBySize(size, size) == DestPolicy.Existing.SAME_SIZE,
                "实际 " + DestPolicy.existingBySize(size, size));
        check("成品去向：相册同名异尺寸 → DIFFERENT_SIZE（拒绝覆盖）",
                DestPolicy.existingBySize(size - 1, size) == DestPolicy.Existing.DIFFERENT_SIZE,
                "实际 " + DestPolicy.existingBySize(size - 1, size));

        String reason = DestPolicy.writeFallbackReason("相册写入失败：openOutputStream 返回 null");
        check("成品去向：写入失败回退原因非空且含『回退』与原始原因",
                reason != null && !reason.isEmpty() && reason.contains("回退")
                        && reason.contains("openOutputStream"),
                "reason=[" + reason + "]");

        Journal j = new Journal();
        j.markDone(1, size, size, rel);
        final boolean[] probed = {false};
        SkipRule.ProductProbe hit = r -> size;
        SkipRule.ProductProbe miss = r -> -1L;
        SkipRule.ProductProbe diff = r -> size - 1;
        check("成品去向：账本完成 + 相册同尺寸 → SKIP（跨 provider 不重导）",
                SkipRule.check(j, hit, rel, size) == SkipRule.Verdict.SKIP, "未 SKIP");
        check("成品去向：账本完成 + 相册无成品 → STALE",
                SkipRule.check(j, miss, rel, size) == SkipRule.Verdict.STALE, "未 STALE");
        check("成品去向：账本完成 + 相册异尺寸 → STALE",
                SkipRule.check(j, diff, rel, size) == SkipRule.Verdict.STALE, "未 STALE");
        check("成品去向：账本无记录 → TODO 且不查成品探针",
                SkipRule.check(new Journal(), r -> { probed[0] = true; return size; }, rel, size)
                        == SkipRule.Verdict.TODO && !probed[0],
                "probed=" + probed[0]);
    }
}
