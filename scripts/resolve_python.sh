#!/bin/sh
# 解释器解析的唯一实现（issue #205）：.githooks/commit-msg、.githooks/pre-push 与
# tools/android-mtp-probe/build.sh 都调本探针，这三处不再各自写查找逻辑。
# 输出：PATH 上找到的解释器路径（先 python 后 python3）；一个都没有时不打任何
# 东西、以 1 退出，调用方据此 fail-closed——本仓库不允许「缺解释器就静默跳过」
# （#243 在 commit-msg 侧定下的策略，这里把它变成唯一实现）。
PY="$(command -v python || command -v python3 || true)"
if [ -z "$PY" ]; then
  exit 1
fi
printf '%s\n' "$PY"
