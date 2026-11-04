#!/usr/bin/env bash
# 手搓 APK：aapt2 -> javac -> d8 -> zipalign -> apksigner（不依赖 Gradle）
set -euo pipefail
cd "$(dirname "$0")"

SDK="/c/Users/VerNe/AppData/Local/Android/Sdk"
BT="$SDK/build-tools/35.0.1"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
JDK="/c/program files/eclipse adoptium/jdk-21.0.11.10-hotspot"
JAVAC="$JDK/bin/javac"

OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/flat" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== 1. aapt2 compile =="
"$BT/aapt2.exe" compile --dir res -o "$OUT/flat/res.zip"

echo "== 2. aapt2 link =="
"$BT/aapt2.exe" link \
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

echo "== 4. d8 =="
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8.bat" --release --lib "$ANDROID_JAR" --min-api 31 \
  --output "$OUT/dex" @"$OUT/classes.txt"

echo "== 5. 打包 dex =="
python - "$OUT/base.apk" "$OUT/dex/classes.dex" << 'PY'
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
"$BT/zipalign.exe" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "== 7. 签名 =="
KS="rea-debug.keystore"
if [ ! -f "$KS" ]; then
  "$JDK/bin/keytool" -genkeypair -v -keystore "$KS" -storepass android -keypass android \
    -alias rea -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=REA Probe, OU=Dev, O=REA, L=NA, S=NA, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner.bat" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/mtp-probe.apk" "$OUT/aligned.apk"
"$BT/apksigner.bat" verify "$OUT/mtp-probe.apk"

echo
echo "✅ 产物: $(pwd)/$OUT/mtp-probe.apk"
ls -lh "$OUT/mtp-probe.apk"
