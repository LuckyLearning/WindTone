#!/usr/bin/env bash
# ===================================================================
#  混淆栈还原：把混淆后的崩溃栈还原成可读的类名/行号
#
#  用法：
#    bash scripts/retrace.sh <mapping.txt> <stack.txt>
#    cat stack.txt | bash scripts/retrace.sh <mapping.txt> -
#
#  mapping 从哪来：
#    · CI：Release 的附件 mapping-v<版本>.txt，或 Actions 的构建 artifact；
#    · 本地：app/build/outputs/mapping/release/mapping.txt
#
#  ⚠️ mapping 与 APK **一一对应**，版本对不上会还原出错误的名字 ——
#     还原前先确认版本号（诊断包的 [环境] 里有）。
# ===================================================================
set -euo pipefail

if [ $# -lt 2 ]; then
  echo "用法：bash scripts/retrace.sh <mapping.txt> <stack.txt|->" >&2
  exit 1
fi

MAPPING="$1"
STACK="$2"
[ -f "$MAPPING" ] || { echo "找不到 mapping：$MAPPING" >&2; exit 1; }
if [ "$STACK" != "-" ] && [ ! -f "$STACK" ]; then
  echo "找不到栈文件：$STACK" >&2
  exit 1
fi

# 找 r8.jar：优先 ANDROID_HOME，其次常见安装位置
R8_JAR=""
for cand in \
  "${ANDROID_HOME:-}/cmdline-tools/latest/lib/r8.jar" \
  "${ANDROID_SDK_ROOT:-}/cmdline-tools/latest/lib/r8.jar" \
  "$HOME/AppData/Local/Android/Sdk/cmdline-tools/latest/lib/r8.jar" \
  "$HOME/Library/Android/sdk/cmdline-tools/latest/lib/r8.jar" \
  "$HOME/Android/Sdk/cmdline-tools/latest/lib/r8.jar"; do
  if [ -n "$cand" ] && [ -f "$cand" ]; then R8_JAR="$cand"; break; fi
done

if [ -z "$R8_JAR" ]; then
  echo "找不到 r8.jar。请把 ANDROID_HOME 指向 SDK，或手动执行：" >&2
  echo "  java -cp <sdk>/cmdline-tools/latest/lib/r8.jar com.android.tools.r8.retrace.Retrace $MAPPING $STACK" >&2
  exit 1
fi

if [ "$STACK" = "-" ]; then
  java -cp "$R8_JAR" com.android.tools.r8.retrace.Retrace "$MAPPING" -
else
  java -cp "$R8_JAR" com.android.tools.r8.retrace.Retrace "$MAPPING" "$STACK"
fi
