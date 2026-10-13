#!/usr/bin/env bash
# ===================================================================
#  发版助手：改版本号 → 提交 → 打 tag → 推送
#  推 tag 后 GitHub Actions 会自动构建签名 APK 并创建 Release。
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

# 1) 改版本号
sed -i "s/val appVersionCode = $CUR_CODE/val appVersionCode = $NEW_CODE/" "$GRADLE_FILE"
sed -i "s/val appVersionName = \"$CUR_NAME\"/val appVersionName = \"$NEW_NAME\"/" "$GRADLE_FILE"
grep -E 'appVersionName|appVersionCode' "$GRADLE_FILE"

# 2) 本地先验证能编译
echo "→ 先跑一遍单测确认没坏..."
(cd "$ROOT" && ./gradlew testDebugUnitTest --console=plain -q)

# 3) 提交 + 打 tag
git -C "$ROOT" add -A
git -C "$ROOT" commit -m "release: $TAG"
git -C "$ROOT" tag "$TAG"

# 4) 推送（走 scripts/push.sh，Token 不落盘到配置里）
bash "$ROOT/scripts/push.sh" origin "$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)"
git -C "$ROOT" push origin "$TAG"

echo
echo "✓ 已推送 $TAG"
echo "  Release 构建进度： https://github.com/LuckyLearning/WindTone/actions/workflows/android-release.yml"
