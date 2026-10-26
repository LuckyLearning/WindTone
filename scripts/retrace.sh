#!/usr/bin/env bash
# ===================================================================
#  混淆栈还原：把已混淆的崩溃栈还原成可读类名/行号
#
#  用法：
#    bash scripts/retrace.sh <mapping.txt> <stack.txt>
#    cat stack.txt | bash scripts/retrace.sh <mapping.txt> -
#    bash scripts/retrace.sh --from-log <crash-or-export.txt> [mapping目录]
#
#  --from-log（A2）：
#    读日志头里的「mapping：mapping-vX.Y.Z」行，在 mapping 目录（默认当前目录
#    与脚本仓库根）查找 mapping-vX.Y.Z.txt，再把整个日志文件交给 Retrace
#    （R8 会跳过非栈行，多段 crash 可一次还原）。
#
#  mapping 从哪来：
#    · CI：Release 的附件 mapping-v<版本>.txt，或 Actions 的构建 artifact
#    · 本地：app/build/outputs/mapping/release/mapping.txt
#
#  ⚠️ mapping 与 APK **一一对应**，版本对不上会还原出错误的名字 ——
#     还原前先确认版本号（诊断包 [环境] 小节 / 崩溃日志头）。
# ===================================================================
set -euo pipefail

usage() {
  echo "用法：" >&2
  echo "  bash scripts/retrace.sh <mapping.txt> <stack.txt|->" >&2
  echo "  bash scripts/retrace.sh --from-log <crash-log.txt> [mapping目录]" >&2
  exit 1
}

find_r8_jar() {
  local cand R8_JAR=""
  for cand in \
    "${ANDROID_HOME:-}/cmdline-tools/latest/lib/r8.jar" \
    "${ANDROID_SDK_ROOT:-}/cmdline-tools/latest/lib/r8.jar" \
    "$HOME/AppData/Local/Android/Sdk/cmdline-tools/latest/lib/r8.jar" \
    "$HOME/Library/Android/sdk/cmdline-tools/latest/lib/r8.jar" \
    "$HOME/Android/Sdk/cmdline-tools/latest/lib/r8.jar"; do
    if [ -n "$cand" ] && [ -f "$cand" ]; then R8_JAR="$cand"; break; fi
  done
  if [ -z "$R8_JAR" ]; then
    echo "找不到 r8.jar。请设 ANDROID_HOME 指向 SDK，或手动执行：" >&2
    echo "  java -cp <sdk>/cmdline-tools/latest/lib/r8.jar com.android.tools.r8.retrace.Retrace <mapping> <stack>" >&2
    exit 1
  fi
  echo "$R8_JAR"
}

run_retrace() {
  local MAPPING="$1"
  local STACK="$2"
  local R8_JAR
  R8_JAR="$(find_r8_jar)"
  if [ "$STACK" = "-" ]; then
    java -cp "$R8_JAR" com.android.tools.r8.retrace.Retrace "$MAPPING" -
  else
    java -cp "$R8_JAR" com.android.tools.r8.retrace.Retrace "$MAPPING" "$STACK"
  fi
}

extract_mapping_name() {
  # 从崩溃导出头解析 mapping-vX.Y.Z（允许全角/半角冒号）
  local log="$1"
  local line name
  line="$(grep -E 'mapping[[:space:]]*[：:][[:space:]]*mapping-v' "$log" | head -n 1 || true)"
  if [ -z "$line" ]; then
    echo "" 
    return 0
  fi
  name="$(echo "$line" | sed -E 's/.*mapping-v/mapping-v/; s/[^A-Za-z0-9._-].*$//')"
  echo "$name"
}

if [ $# -lt 1 ]; then
  usage
fi

if [ "$1" = "--from-log" ]; then
  if [ $# -lt 2 ]; then usage; fi
  LOG="$2"
  MAP_DIR="${3:-.}"
  [ -f "$LOG" ] || { echo "找不到日志：$LOG" >&2; exit 1; }
  NAME="$(extract_mapping_name "$LOG")"
  if [ -z "$NAME" ]; then
    echo "日志里没有「mapping：mapping-v…」行。请用新版 App 导出崩溃日志，或手动：" >&2
    echo "  bash scripts/retrace.sh <mapping.txt> $LOG" >&2
    exit 1
  fi
  # 允许 NAME 已带或不带 .txt
  CANDIDATES=(
    "$MAP_DIR/${NAME}.txt"
    "$MAP_DIR/$NAME"
    "$MAP_DIR/mapping.txt"
    "app/build/outputs/mapping/release/mapping.txt"
  )
  MAPPING=""
  for c in "${CANDIDATES[@]}"; do
    if [ -f "$c" ]; then MAPPING="$c"; break; fi
  done
  if [ -z "$MAPPING" ]; then
    echo "找不到 ${NAME}.txt（在 $MAP_DIR 下）。请从 GitHub Release 下载同名 mapping。" >&2
    exit 1
  fi
  echo "使用 mapping：$MAPPING （来自日志 $NAME）" >&2
  run_retrace "$MAPPING" "$LOG"
  exit 0
fi

if [ $# -lt 2 ]; then usage; fi
MAPPING="$1"
STACK="$2"
[ -f "$MAPPING" ] || { echo "找不到 mapping：$MAPPING" >&2; exit 1; }
if [ "$STACK" != "-" ] && [ ! -f "$STACK" ]; then
  echo "找不到栈文件：$STACK" >&2
  exit 1
fi
run_retrace "$MAPPING" "$STACK"
