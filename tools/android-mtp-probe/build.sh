#!/usr/bin/env bash
# 手搓 APK：aapt2 -> javac -> d8 -> zipalign -> apksigner（不依赖 Gradle）
set -euo pipefail
cd "$(dirname "$0")"

# ---- 0. 只读红线静态检查（#222）--------------------------------------------
# AGENTS.md §2 红线 2：探针不得下发写操作。这里把它变成结构约束：MtpDevice 只允许
# 出现在只读门面 ReadOnlyMtp.java 里，其余源码拿不到设备句柄；写 / 破坏性 API 全包禁止。
# 只认「import 语句」「声明」「new」「泛型 / 数组」几种真实用法，注释与日志文案里提到
# MtpDevice 不算；`MtpDeviceInfo` 等类型名也不会命中。
REDLINE_LEAK="$(grep -rlE --include='*.java' 'android\.mtp\.MtpDevice([^A-Za-z]|$)|new[[:space:]]+[A-Za-z.]*MtpDevice([^A-Za-z]|$)|MtpDevice[[:space:]]+[A-Za-z_]|MtpDevice[[:space:]]*[\[>]' src | grep -v 'ReadOnlyMtp.java' || true)"
if [ -n "$REDLINE_LEAK" ]; then
  echo "[错误] 只读红线：MtpDevice 只允许出现在 ReadOnlyMtp.java，以下文件越界：" >&2
  printf '%s\n' "$REDLINE_LEAK" | while IFS= read -r f; do printf '       %s\n' "$f" >&2; done
  exit 1
fi
# 写 / 破坏性 API：API 35 的 android.mtp.MtpDevice 上只有这三个（javap 实测），
# 只认「方法调用」形态，注释里提名字不会误伤。
REDLINE_WRITE="$(grep -rnE --include='*.java' '\.(deleteObject|sendObject|sendObjectInfo)[[:space:]]*\(' src || true)"
if [ -n "$REDLINE_WRITE" ]; then
  echo "[错误] 只读红线：探针源码出现写 / 破坏性 API：" >&2
  printf '%s\n' "$REDLINE_WRITE" | while IFS= read -r line; do printf '       %s\n' "$line" >&2; done
  exit 1
fi

# 构建版本写死：同一 commit 在任何机器上都应产出相同的 APK。
# 找不到对应组件时见下方报错里的「安装 / 改哪两行」提示。
BT_VER="35.0.1"
PLATFORM="android-35"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) IS_WIN=1; EXE=".exe"; BAT=".bat" ;;
  *)                     IS_WIN=0; EXE="";     BAT=""     ;;
esac

die() {
  printf '[错误] %s\n' "$1" >&2
  shift
  for line in "$@"; do printf '       %s\n' "$line" >&2; done
  exit 1
}

# Windows 的 C:\x / C:/x 统一成 MSYS 风格 /c/x；其它平台原样返回。
# 不做这步，继承来的 JAVA_HOME（C:\Program Files\...）在 Git Bash 里不可用。
norm_path() {
  if [ "$IS_WIN" != 1 ]; then
    printf '%s\n' "$1"
    return
  fi
  case "$1" in
    [A-Za-z]:\\*|[A-Za-z]:/*)
      local drive="${1:0:1}" rest="${1:2}"
      rest="${rest//\\//}"
      printf '/%s%s\n' "$(printf '%s' "$drive" | tr 'A-Z' 'a-z')" "$rest"
      ;;
    *) printf '%s\n' "$1" ;;
  esac
}

# ---- SDK 探测：ANDROID_HOME -> ANDROID_SDK_ROOT -> 常见默认路径 -------------
SDK=""
SDK_SEEN=""
for cand in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
            "$HOME/AppData/Local/Android/Sdk" \
            "$HOME/Library/Android/sdk" \
            "$HOME/Android/Sdk"; do
  [ -n "$cand" ] || continue
  cand="$(norm_path "$cand")"
  [ -d "$cand" ] || continue
  if [ -f "$cand/build-tools/$BT_VER/aapt2$EXE" ] && [ -f "$cand/platforms/$PLATFORM/android.jar" ]; then
    SDK="$cand"
    break
  fi
  [ -n "$SDK_SEEN" ] || SDK_SEEN="$cand"
done

if [ -z "$SDK" ]; then
  if [ -n "$SDK_SEEN" ]; then
    die "在 $SDK_SEEN 找到了 Android SDK，但缺少探针所需的组件。" \
        "需要：build-tools/$BT_VER 与 platforms/$PLATFORM。" \
        "安装：sdkmanager \"build-tools;$BT_VER\" \"platforms;$PLATFORM\"" \
        "或修改本脚本顶部的 BT_VER / PLATFORM 两行，匹配你已安装的版本。"
  fi
  die "找不到 Android SDK（已尝试 ANDROID_HOME、ANDROID_SDK_ROOT 与常见默认路径）。" \
      "请设置后重试：export ANDROID_HOME=/path/to/Android/Sdk"
fi

# ---- JDK 探测：JAVA_HOME -> PATH 里的 javac 反推 -------------------------
JDK=""
if [ -n "${JAVA_HOME:-}" ]; then
  jhome="$(norm_path "$JAVA_HOME")"
  if [ -x "$jhome/bin/javac$EXE" ] || [ -x "$jhome/bin/javac" ]; then
    JDK="$jhome"
  fi
fi
if [ -z "$JDK" ] && command -v javac >/dev/null 2>&1; then
  JDK="$(dirname "$(dirname "$(command -v javac)")")"
fi
[ -n "$JDK" ] || die "找不到 JDK（需要 javac）。" \
    "已尝试 JAVA_HOME 与 PATH；JAVA_HOME 指向的必须是 JDK，不能是 JRE。" \
    "请设置后重试：export JAVA_HOME=/path/to/jdk-21"

JAVAC=""
for cand in "$JDK/bin/javac$EXE" "$JDK/bin/javac"; do
  if [ -x "$cand" ]; then JAVAC="$cand"; break; fi
done
[ -n "$JAVAC" ] || JAVAC="$(command -v javac)"
[ -n "$JAVAC" ] || die "JDK 里找不到 javac：$JDK"

JAVA=""
for cand in "$JDK/bin/java$EXE" "$JDK/bin/java"; do
  if [ -x "$cand" ]; then JAVA="$cand"; break; fi
done
[ -n "$JAVA" ] || JAVA="$(command -v java || true)"
[ -n "$JAVA" ] || die "JDK 里找不到 java（纯 JDK 自检需要）：$JDK"

KEYTOOL="$JDK/bin/keytool$EXE"
[ -x "$KEYTOOL" ] || KEYTOOL="$(command -v keytool || true)"

# 第 5 步打包 dex 用 Python 3；解释器解析收敛到共享探针（issue #205），本文件不再
# 自带查找逻辑。探针相对当前目录定位：顶部已 cd 到本工程目录，仓库根就是 ../../，
# 从任何目录调用 build.sh 都能找到 scripts/resolve_python.sh。
if ! PY="$(sh ../../scripts/resolve_python.sh)"; then
  die "找不到 python（第 5 步打包 dex 需要 Python 3）。" \
      "解释器探针 scripts/resolve_python.sh 确认 PATH 里既无 python 也无 python3。"
fi

BT="$SDK/build-tools/$BT_VER"
ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"
AAPT2="$BT/aapt2$EXE"
D8="$BT/d8$BAT"
ZIPALIGN="$BT/zipalign$EXE"
APKSIGNER="$BT/apksigner$BAT"

echo "SDK = $SDK"
echo "JDK = $JDK"

OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/flat" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== 1. aapt2 compile =="
"$AAPT2" compile --dir res -o "$OUT/flat/res.zip"

echo "== 2. aapt2 link =="
"$AAPT2" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  -R "$OUT/flat/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 31 \
  --target-sdk-version 35 \
  --auto-add-overlay

echo "== 3. javac =="
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
# 注意：不要用管道接 grep，否则 javac 的失败退出码会被吞掉
if ! "$JAVAC" -encoding UTF-8 -nowarn \
      -classpath "$ANDROID_JAR" \
      -d "$OUT/classes" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
  echo "javac 失败："
  cat "$OUT/javac.log"
  exit 1
fi
[ -n "$(find "$OUT/classes" -name '*.class' -print -quit)" ] || { echo "javac 没有产出任何 class"; exit 1; }

echo "== 3.5 纯 JDK 自检（ProbeFiles / ObjectRef）=="
# 这三个文件不 import android，必须能脱离 android.jar 单独编译并跑在 JVM 上；
# 跑不起来说明它们混进了 Android 依赖，或 md5 / 文件头逻辑被改坏。
mkdir -p "$OUT/jvm"
if ! "$JAVAC" -encoding UTF-8 -nowarn -d "$OUT/jvm" \
      src/com/rea/mtpprobe/ProbeFiles.java \
      src/com/rea/mtpprobe/ObjectRef.java \
      test/com/rea/mtpprobe/ProbeFilesTest.java > "$OUT/jvm-javac.log" 2>&1; then
  echo "纯 JDK 自检编译失败："
  cat "$OUT/jvm-javac.log"
  exit 1
fi
if ! "$JAVA" -cp "$OUT/jvm" com.rea.mtpprobe.ProbeFilesTest > "$OUT/jvm-test.log" 2>&1; then
  echo "纯 JDK 自检失败："
  cat "$OUT/jvm-test.log"
  exit 1
fi
cat "$OUT/jvm-test.log"

echo "== 4. d8 =="
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$D8" --release --lib "$ANDROID_JAR" --min-api 31 \
  --output "$OUT/dex" @"$OUT/classes.txt"

echo "== 5. 打包 dex =="
"$PY" - "$OUT/base.apk" "$OUT/dex/classes.dex" << 'PY'
import sys, zipfile, shutil, os
apk, dex = sys.argv[1], sys.argv[2]
tmp = apk + ".tmp"
shutil.move(apk, tmp)
with zipfile.ZipFile(tmp) as zin, zipfile.ZipFile(apk, "w", zipfile.ZIP_DEFLATED) as zout:
    for it in zin.infolist():
        zout.writestr(it, zin.read(it.filename))
    zout.write(dex, "classes.dex")
os.remove(tmp)
print("  classes.dex 已加入")
PY

echo "== 6. zipalign =="
"$ZIPALIGN" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "== 7. 签名 =="
KS="rea-debug.keystore"
if [ ! -f "$KS" ]; then
  [ -n "$KEYTOOL" ] || die "keystore 缺失，且找不到 keytool 重新生成；请把 JAVA_HOME 指向 JDK。"
  "$KEYTOOL" -genkeypair -v -keystore "$KS" -storepass android -keypass android \
    -alias rea -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=REA Probe, OU=Dev, O=REA, L=NA, S=NA, C=CN" >/dev/null 2>&1
fi
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/mtp-probe.apk" "$OUT/aligned.apk"
"$APKSIGNER" verify "$OUT/mtp-probe.apk"

echo
echo "✅ 产物: $(pwd)/$OUT/mtp-probe.apk"
ls -lh "$OUT/mtp-probe.apk"
