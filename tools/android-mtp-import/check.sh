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
#
# 注意这条边界是**编译期强制**的：这里不给 -classpath android.jar，
# 所以哪个纯逻辑类偷偷 `import android.*` 会直接编译失败，而不是等到真机上才崩。
# 清单必须与磁盘一致——漏列一个文件，它就悄悄脱离了这条保护，
# 所以下面第 0 步会拿 `grep -L '^import android'` 的实际结果对清单做漂移检查。
PURE=(
  src/com/rea/mtpimport/AttachGate.java
  src/com/rea/mtpimport/Catalog.java
  src/com/rea/mtpimport/ImportListener.java
  src/com/rea/mtpimport/ImportPlan.java
  src/com/rea/mtpimport/ImportService.java
  src/com/rea/mtpimport/Journal.java
  src/com/rea/mtpimport/Log.java
  src/com/rea/mtpimport/PermGate.java
  src/com/rea/mtpimport/ResumableTransfer.java
  src/com/rea/mtpimport/Selection.java
  src/com/rea/mtpimport/TransferProgress.java
)

echo "== 0. 纯逻辑清单与磁盘一致性 =="
mapfile -t PURE_ACTUAL < <(grep -L '^import android' src/com/rea/mtpimport/*.java | sort)
mapfile -t PURE_DECL < <(printf '%s\n' "${PURE[@]}" | sort)
if [ "${PURE_ACTUAL[*]}" != "${PURE_DECL[*]}" ]; then
  echo "PURE 清单与磁盘上「不 import android 的文件」不一致。"
  echo "磁盘上纯 JDK 文件："
  printf '  %s\n' "${PURE_ACTUAL[@]}"
  echo "差异（左=清单独有，右=磁盘独有）："
  diff <(printf '%s\n' "${PURE_DECL[@]}") <(printf '%s\n' "${PURE_ACTUAL[@]}") || true
  echo "新增纯逻辑类必须加进本脚本的 PURE，否则它不受「不许 import android」保护。"
  exit 1
fi
echo "  ${#PURE[@]} 个纯 JDK 文件，与清单一致"

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
