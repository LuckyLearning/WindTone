#!/usr/bin/env bash
# ===================================================================
#  发版助手：跑单测 → 改版本号 → 提交 → 打 tag → 推送
#  推 tag 后 GitHub Actions 会自动构建签名 APK 并创建 Release。
#
#  顺序说明：单测在**改版本号之前**跑。这样测试失败时仓库保持干净，
#  不会留下「版本号已改但没提交」的状态（否则重跑会让 versionCode 再 +1）。
#
#  用法：
#    bash scripts/release.sh patch          # 1.0.0 → 1.0.1
#    bash scripts/release.sh minor          # 1.0.0 → 1.1.0
#    bash scripts/release.sh major          # 1.0.0 → 2.0.0
#    bash scripts/release.sh 1.5.2          # 指定版本号
#    bash scripts/release.sh patch --dry    # 只打印将要做什么
# ===================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_FILE="$ROOT/app/build.gradle.kts"
BUMP="${1:-patch}"
DRY="${2:-}"

[[ -f "$GRADLE_FILE" ]] || { echo "找不到 $GRADLE_FILE" >&2; exit 1; }

# 版本号在 app/build.gradle.kts 顶部集中定义（appVersionName / appVersionCode）
CUR_CODE=$(grep -oP 'appVersionCode\s*=\s*\K[0-9]+' "$GRADLE_FILE" | head -1)
CUR_NAME=$(grep -oP 'appVersionName\s*=\s*"\K[^"]+' "$GRADLE_FILE" | head -1)

IFS='.' read -r MA MI PA <<< "$CUR_NAME"
case "$BUMP" in
  major) NEW_NAME="$((MA + 1)).0.0" ;;
  minor) NEW_NAME="$MA.$((MI + 1)).0" ;;
  patch) NEW_NAME="$MA.$MI.$((PA + 1))" ;;
  [0-9]*.[0-9]*.[0-9]*) NEW_NAME="$BUMP" ;;
  *) echo "无法识别的参数：$BUMP（用 major/minor/patch 或 3.5.2 这样的版本号）" >&2; exit 1 ;;
esac
NEW_CODE=$((CUR_CODE + 1))
TAG="v$NEW_NAME"

echo "版本：$CUR_NAME (code $CUR_CODE)  →  $NEW_NAME (code $NEW_CODE)"
echo "tag ：$TAG"

if [[ "$DRY" == "--dry" ]]; then
  echo "（--dry：未做任何改动）"
  exit 0
fi

# 1) 先验证能编译
#
# ⚠️ 这一步**故意放在改版本号之前**。反过来的话，单测一旦失败，仓库就会留下
#    「版本号已改、但没提交」的脏状态；下次直接重跑 release.sh，读到的就是已经
#    +1 过的 code，于是再 +1（2026-10-14 已踩出过 code 6）。先测后改即可根治。
echo "→ 先跑一遍单测确认没坏..."
# Android 构建必须用 JDK 21（temurin/openjdk），不能落到 IntelliJ 自带的 GraalVM ——
# GraalVM 的 jlink 在处理 android-35 的 core-for-system-modules.jar 时会报
# JdkImageTransform 失败。
#
# ⚠️ 注意：「JAVA_HOME 非空」并不代表可用：本机 shell 的 JAVA_HOME 默认就指向
#    C:\Users\stu_l\.jdks\graalvm-jdk-21.0.7，只判断空值会直接放行到 GraalVM
#    （2026-10-14 实际踩中：单测阶段 BUILD FAILED）。所以路径里带 graal 的也要换掉。
PREFERRED_JDK="C:/Users/stu_l/.jdks/openjdk-21.0.2"
CUR_JDK="${JAVA_HOME:-}"
if [[ -d "$PREFERRED_JDK" ]] && { [[ -z "$CUR_JDK" ]] || [[ "$CUR_JDK" == *[Gg]raal* ]]; }; then
  export JAVA_HOME="$PREFERRED_JDK"
  echo "  （原 JAVA_HOME=${CUR_JDK:-未设置} 不可用于 Android 构建，已改用 $JAVA_HOME）"
fi
echo "  JDK：${JAVA_HOME:-未设置}"
(cd "$ROOT" && ./gradlew testDebugUnitTest --console=plain -q)

# 2) 预览发版说明：固定模板 + 只替换版本号，正文不需要手写/让 AI 生成
echo
echo "→ 本次 Release 说明预览（由 .github/release-notes/ 模板自动渲染）："
echo "----------------------------------------------------------------"
bash "$ROOT/scripts/render-release-notes.sh" "$NEW_NAME" "$NEW_CODE"
echo "----------------------------------------------------------------"

# 3) 单测通过后才动版本号
echo "→ 写入版本号..."
sed -i "s/val appVersionCode = $CUR_CODE/val appVersionCode = $NEW_CODE/" "$GRADLE_FILE"
sed -i "s/val appVersionName = \"$CUR_NAME\"/val appVersionName = \"$NEW_NAME\"/" "$GRADLE_FILE"
grep -E 'appVersionName|appVersionCode' "$GRADLE_FILE"

# 4) 提交 + 打 tag
git -C "$ROOT" add -A
git -C "$ROOT" commit -m "release: $TAG"
git -C "$ROOT" tag "$TAG"

# 5) 推送（走 scripts/push.sh，Token 不落盘到配置里）
bash "$ROOT/scripts/push.sh" origin "$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)"
bash "$ROOT/scripts/push.sh" origin "$TAG"

echo
echo "✓ 已推送 $TAG"
echo "  Release 构建进度： https://github.com/LuckyLearning/WindTone/actions/workflows/android-release.yml"
