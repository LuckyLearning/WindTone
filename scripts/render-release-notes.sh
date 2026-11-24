#!/usr/bin/env bash
# ===================================================================
#  渲染 Release 说明正文 → 替换版本号等占位符
#
#  用法：
#    bash scripts/render-release-notes.sh                     # 渲染并打印到终端
#    bash scripts/render-release-notes.sh -o body.md          # 渲染并写入文件
#    bash scripts/render-release-notes.sh 1.0.7 9 body.md     # 显式指定版本/versionCode/输出
#    bash scripts/render-release-notes.sh 1.0.7 9 --require-version-file   # 缺本版说明即失败
#
#  取值规则：
#    · 版本号 / versionCode 省略时从 --gradle 指定的构建文件读取（默认 :app）
#    · 正文来源优先级：--template 指定文件 > .github/release-notes/v{版本}.md > 默认兜底模板
#    · 从 v1.3.1 起默认走「每版一份实际说明」；--require-version-file 强制版本文件必须存在
#    · 占位符：{version} {version_code} {date}
#
#  CI 与本地发版脚本共用本脚本，避免多处各写一份 sed 逻辑而漂移。
#  AnyLyric 已独立到 https://github.com/LuckyLearning/anylyric，模板与脚本随它迁移。
# ===================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_FILE="$ROOT/app/build.gradle.kts"
NOTES_DIR="$ROOT/.github/release-notes"
DEFAULT_TEMPLATE="$NOTES_DIR/template.md"
FORCED_TEMPLATE=""
REQUIRE_VERSION_FILE=0
VERSION_KEY="appVersionName"
CODE_KEY="appVersionCode"

# 相对路径一律按仓库根解析，这样在任意目录下调用结果都一致
resolve_path() {
  case "$1" in
    /*) printf '%s' "$1" ;;
    *)  printf '%s' "$ROOT/$1" ;;
  esac
}

OUT=""
ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    -o|--out)
      OUT="${2:?--out 需要一个路径}"; shift 2 ;;
    --out=*)
      OUT="${1#*=}"; shift ;;
    --gradle)
      GRADLE_FILE="$(resolve_path "${2:?--gradle 需要一个路径}")"; shift 2 ;;
    --gradle=*)
      GRADLE_FILE="$(resolve_path "${1#*=}")"; shift ;;
    --template)
      FORCED_TEMPLATE="$(resolve_path "${2:?--template 需要一个路径}")"; shift 2 ;;
    --template=*)
      FORCED_TEMPLATE="$(resolve_path "${1#*=}")"; shift ;;
    --require-version-file)
      REQUIRE_VERSION_FILE=1; shift ;;
    --notes-dir)
      NOTES_DIR="$(resolve_path "${2:?--notes-dir 需要一个路径}")"; shift 2 ;;
    --version-key)
      VERSION_KEY="${2:?--version-key 需要一个 key}"; shift 2 ;;
    --code-key)
      CODE_KEY="${2:?--code-key 需要一个 key}"; shift 2 ;;
    --)
      shift; ARGS+=("$@"); break ;;
    -*)
      echo "无法识别的开关：$1" >&2; exit 1 ;;
    *)
      ARGS+=("$1"); shift ;;
  esac
done

# 位置参数：version code out（都省略时从构建文件读）
VERSION="${ARGS[0]:-}"
CODE="${ARGS[1]:-}"
[[ -z "$OUT" && ${#ARGS[@]} -ge 3 ]] && OUT="${ARGS[2]}"

[[ -f "$GRADLE_FILE" ]] || { echo "找不到构建文件：$GRADLE_FILE" >&2; exit 1; }

if [[ -z "$VERSION" ]]; then
  VERSION=$(grep -oP "${VERSION_KEY}\s*=\s*\"\\K[^\"]+" "$GRADLE_FILE" | head -1 || true)
fi
if [[ -z "$CODE" ]]; then
  CODE=$(grep -oP "${CODE_KEY}\s*=\s*\K[0-9]+" "$GRADLE_FILE" | head -1 || true)
fi
[[ -n "$VERSION" ]] || { echo "无法从 $GRADLE_FILE 读到 ${VERSION_KEY}" >&2; exit 1; }
[[ -n "$CODE" ]] || CODE="0"

DATE_UTC="$(date -u +%Y-%m-%d)"
OVERRIDE="$NOTES_DIR/v$VERSION.md"

# 强制要求「本版实际说明」：没有 v{版本}.md 就失败，避免悄悄退回兜底模板发出空话
if (( REQUIRE_VERSION_FILE )) && [[ -z "$FORCED_TEMPLATE" && ! -f "$OVERRIDE" ]]; then
  echo "✗ 缺少本版发版说明：${OVERRIDE#"$ROOT"/}" >&2
  echo "  发版说明必须按本版实际改动编写（参考 git log <上个 tag>..HEAD 与改动内容）。" >&2
  echo "  确需沿用兜底模板时，请显式传 --template，或去掉 --require-version-file。" >&2
  exit 1
fi

if [[ -n "$FORCED_TEMPLATE" ]]; then
  SRC="$FORCED_TEMPLATE"
elif [[ -f "$OVERRIDE" ]]; then
  SRC="$OVERRIDE"
else
  SRC="$DEFAULT_TEMPLATE"
fi
[[ -f "$SRC" ]] || { echo "找不到说明模板：$SRC" >&2; exit 1; }

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
