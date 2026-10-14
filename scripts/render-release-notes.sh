#!/usr/bin/env bash
# ===================================================================
#  渲染 Release 说明正文：固定模板 → 只替换版本号等占位符
#
#  用法：
#    bash scripts/render-release-notes.sh                     # 渲染并打印到终端
#    bash scripts/render-release-notes.sh -o body.md          # 渲染并写入文件
#    bash scripts/render-release-notes.sh 1.0.7 9 body.md     # 显式指定版本/versionCode/输出
#
#  取值规则：
#    · 版本号 / versionCode 省略时从 app/build.gradle.kts 读取（单一来源）
#    · 正文来源：.github/release-notes/v{版本}.md 存在则用它，否则用 template.md
#    · 占位符：{version} {version_code} {date}
#
#  CI（android-release.yml）与本地发版（release.sh）共用本脚本，
#  避免两处各写一份 sed 逻辑而漂移。**内容由模板决定，不需要人/AI 每次生成。**
# ===================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_FILE="$ROOT/app/build.gradle.kts"
NOTES_DIR="$ROOT/.github/release-notes"

OUT=""
ARGS=()
for arg in "$@"; do
  case "$arg" in
    -o|--out) OUT="__NEXT__" ;;
    *)
      if [[ "$OUT" == "__NEXT__" ]]; then OUT="$arg"
      else ARGS+=("$arg")
      fi
      ;;
  esac
done

VERSION="${ARGS[0]:-}"
CODE="${ARGS[1]:-}"
[[ -z "$OUT" && ${#ARGS[@]} -ge 3 ]] && OUT="${ARGS[2]}"

if [[ -z "$VERSION" ]]; then
  VERSION=$(grep -oP 'appVersionName\s*=\s*"\K[^"]+' "$GRADLE_FILE" | head -1)
fi
if [[ -z "$CODE" ]]; then
  CODE=$(grep -oP 'appVersionCode\s*=\s*\K[0-9]+' "$GRADLE_FILE" | head -1)
fi
[[ -n "$VERSION" ]] || { echo "无法从 $GRADLE_FILE 读到版本号" >&2; exit 1; }
[[ -n "$CODE" ]] || CODE="0"

DATE_UTC="$(date -u +%Y-%m-%d)"
OVERRIDE="$NOTES_DIR/v$VERSION.md"
TEMPLATE="$NOTES_DIR/template.md"

if [[ -f "$OVERRIDE" ]]; then
  SRC="$OVERRIDE"
else
  SRC="$TEMPLATE"
fi
[[ -f "$SRC" ]] || { echo "找不到说明模板：$TEMPLATE" >&2; exit 1; }

RENDERED="$(sed -e "s/{version}/${VERSION}/g" \
                -e "s/{version_code}/${CODE}/g" \
                -e "s/{date}/${DATE_UTC}/g" "$SRC")"

# 兜底：渲染后还残留 {xxx} 说明模板里写了未知占位符，直接失败，避免把半成品发出去
if grep -qE '\{[a-z_]+\}' <<< "$RENDERED"; then
  echo "渲染后仍有未替换的占位符，请检查模板：" >&2
  grep -oE '\{[a-z_]+\}' <<< "$RENDERED" | sort -u >&2
  exit 1
fi

if [[ -n "$OUT" ]]; then
  printf '%s\n' "$RENDERED" > "$OUT"
  echo "已渲染 Release 说明：$OUT（来源 ${SRC#"$ROOT"/}，版本 $VERSION · versionCode $CODE）" >&2
else
  printf '%s\n' "$RENDERED"
fi
