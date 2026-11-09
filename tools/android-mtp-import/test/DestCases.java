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
}
