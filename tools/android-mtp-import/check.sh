#!/usr/bin/env bash
# 一条命令验完新工具：JVM 纯逻辑自检 → APK 构建 → 签名校验。
#
#   bash tools/android-mtp-import/check.sh
#   SKIP_BUILD=1 bash tools/android-mtp-import/check.sh   # 只跑纯逻辑自检，不用 Android SDK
#
# 纯逻辑那几个类不 import android.*，所以自检能在普通 JVM 上跑完；
# 这也是本骨架唯一能拿门禁守住的部分（真机行为见 README 的真机待验清单）。
set -euo pipefail
cd "$(dirname "$0")"

JDK="${JDK:-/c/program files/eclipse adoptium/jdk-21.0.11.10-hotspot}"
JAVAC="$JDK/bin/javac"
JAVA="$JDK/bin/java"

OUT=build/jvm
rm -rf "$OUT"
mkdir -p "$OUT"

# 只编纯 JDK 类型：这批文件不允许 import android.*，否则自检就跑不起来。
PURE=(
  src/com/rea/mtpimport/Catalog.java
  src/com/rea/mtpimport/Selection.java
  src/com/rea/mtpimport/ImportPlan.java
  src/com/rea/mtpimport/TransferProgress.java
  src/com/rea/mtpimport/Journal.java
)

echo "== 1. 编译纯逻辑 =="
"$JAVAC" -encoding UTF-8 -Xlint:all -d "$OUT" "${PURE[@]}" test/SelfTest.java

echo "== 2. 纯逻辑自检 =="
# 强制 UTF-8 输出，否则 Windows 控制台会把中文日志按 cp936 吐成乱码
"$JAVA" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -cp "$OUT" com.rea.mtpimport.SelfTest

if [ "${SKIP_BUILD:-0}" = "1" ]; then
  echo
  echo "SKIP_BUILD=1，跳过 APK 构建。"
  exit 0
fi

echo
echo "== 3. APK 构建 =="
"${BASH:-bash}" build.sh
