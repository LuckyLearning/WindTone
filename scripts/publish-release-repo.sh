#!/usr/bin/env bash
# ===================================================================
#  把本次构建的 APK 发布到**公共分发仓库**（默认 LuckyLearning/Release），
#  并保证每个软件只保留最近 KEEP（默认 5）个版本。
#
#  为什么需要它：源码仓库已转为私有，匿名用户拿不到私有 Release 的资产，
#  而应用内更新必须能在没有任何鉴权的情况下读到版本信息与安装包。
#
#  目录结构（公共仓库根）：
#    WindTone/
#      latest.json            ← 应用内更新读这个
#      v1.1.30/               ← 每个版本一个目录
#        WindTone-v1.1.30-arm64-v8a.apk …
#        notes.md
#        checksums.txt
#      v1.1.29/ …
#    AnyLyric/                ← 同一个脚本发布多个软件，靠 APP_DIR 区分
#      latest.json
#      v0.1.0/
#        AnyLyric-v0.1.0.apk
#
#  由 .github/workflows/android-release.yml（APP_DIR=WindTone）与
#  .github/workflows/anylyric-release.yml（APP_DIR=AnyLyric）在打 tag 时调用；
#  本地也能手动跑（需要环境变量 RELEASE_REPO_TOKEN）。
#
#  ⚠️ 公共仓库每次都是「孤立提交 + force push 覆盖」，两条流水线并发会互相吞掉
#  对方的版本目录。所以两个 workflow 的发布 job 共用 concurrency group
#  `public-release-repo`（cancel-in-progress: false）串行执行。
#
#  用法：
#    VERSION=1.1.30 CODE=40 NOTES_PATH=/tmp/body.md DIST_DIR=dist MIN_SDK=24 \
#    RELEASE_REPO_TOKEN=github_pat_xxx bash scripts/publish-release-repo.sh
# ===================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

VERSION="${VERSION:?缺少 VERSION（如 1.1.30）}"
CODE="${CODE:?缺少 CODE（如 40）}"
NOTES_PATH="${NOTES_PATH:?缺少 NOTES_PATH（Release 说明文件）}"
DIST_DIR="${DIST_DIR:-$ROOT/dist}"
PUBLIC_SLUG="${PUBLIC_SLUG:-LuckyLearning/Release}"
APP_DIR="${APP_DIR:-WindTone}"
MIN_SDK="${MIN_SDK:-24}"
KEEP="${KEEP:-5}"
TOKEN="${RELEASE_REPO_TOKEN:-}"

if [[ -z "$TOKEN" ]]; then
  echo "缺少 RELEASE_REPO_TOKEN —— 跳过公共仓库发布" >&2
  exit 1
fi
if ! ls "$DIST_DIR"/*.apk >/dev/null 2>&1; then
  echo "找不到安装包：$DIST_DIR/*.apk" >&2
  exit 1
fi

WORK="$(mktemp -d)"
AP="$(mktemp)"
cleanup() { rm -rf "$WORK" 2>/dev/null || true; rm -f "$AP" 2>/dev/null || true; }
trap cleanup EXIT

# token 走 askpass，不写进 remote URL / 命令行 / 配置（与 scripts/push.sh 同一套做法）
cat > "$AP" <<EOF
#!/usr/bin/env bash
case "\$1" in
  *Username*) echo "x-access-token" ;;
  *)          echo "$TOKEN" ;;
esac
EOF
chmod +x "$AP"
export GIT_ASKPASS="$AP" GIT_TERMINAL_PROMPT=0

echo "→ 拉取公共仓库 $PUBLIC_SLUG ..."
git -c credential.helper= clone --depth 1 "https://github.com/$PUBLIC_SLUG.git" "$WORK/repo" >/dev/null

REPO="$WORK/repo"
VDIR="$REPO/$APP_DIR/v$VERSION"
mkdir -p "$VDIR"

echo "→ 拷入本次产物与说明 ..."
cp "$DIST_DIR"/*.apk "$VDIR/"
cp "$NOTES_PATH" "$VDIR/notes.md"
( cd "$VDIR" && sha256sum *.apk > checksums.txt && cat checksums.txt )

# 保留最近 KEEP 个版本：按语义版本排序，多余的删掉
mapfile -t VERSIONS < <(ls -d "$REPO/$APP_DIR"/v* 2>/dev/null | xargs -r -n1 basename | sort -V -r)
if (( ${#VERSIONS[@]} > KEEP )); then
  for old in "${VERSIONS[@]:$KEEP}"; do
    echo "→ 清理旧版本 $old"
    rm -rf "$REPO/$APP_DIR/$old"
  done
fi

echo "→ 生成 latest.json / 刷新 README ..."
python3 "$ROOT/scripts/gen-release-manifest.py" \
  --root "$REPO" \
  --app-dir "$APP_DIR" \
  --version "$VERSION" \
  --code "$CODE" \
  --tag "v$VERSION" \
  --min-sdk "$MIN_SDK" \
  --published "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --notes "$NOTES_PATH"

# 公共仓库只做「当前 5 个版本的快照」，不让历史二进制一直堆在 git 里：
# 每次用一个孤立提交覆盖 main（force push），仓库体积恒定在 ~5 个版本。
cd "$REPO"
git config user.name "$APP_DIR Release Bot"
git config user.email "release-bot@users.noreply.github.com"
git checkout -q --orphan snapshot
git add -A
git commit -q -m "release: $APP_DIR v$VERSION (code $CODE)"

echo "→ 推送公共仓库 ..."
# 不吞推送的报错：2026-11-12 首次 AnyLyric 发版就栽在这 —— push 的真实原因是
# RELEASE_REPO_TOKEN 失效（401），但输出被丢进 /dev/null，脚本只剩一句
# 「大概是分支保护」，排查方向直接被带偏。现在原样打印 git 的报错。
PUSH_LOG="$(mktemp)"
if ! git -c credential.helper= push -f origin snapshot:main >"$PUSH_LOG" 2>&1; then
  echo "✗ 推送公共仓库失败，git 原始输出：" >&2
  sed 's/^/    /' "$PUSH_LOG" >&2
  echo "  常见原因：" >&2
  echo "   1) RELEASE_REPO_TOKEN 失效（Bad credentials / 401 / 403）—— 换新 token 后更新仓库 secret；" >&2
  echo "   2) 公共仓库 main 开了分支保护（本脚本用「快照覆盖」发布，需要允许 force push）。" >&2
  rm -f "$PUSH_LOG" 2>/dev/null || true
  exit 1
fi
rm -f "$PUSH_LOG" 2>/dev/null || true

# 推后实查：公共仓库的 latest.json 必须已经指向本次版本（CDN 有缓存，带随机参数）
RAW="https://raw.githubusercontent.com/$PUBLIC_SLUG/main/$APP_DIR/latest.json"
for attempt in 1 2 3 4 5; do
  GOT="$(curl -fsSL "$RAW?nocache=$(date +%s)-$attempt" 2>/dev/null | grep -o "\"version\": \"[0-9.]*\"" | head -1 | grep -o '[0-9][0-9.]*' || true)"
  if [[ "$GOT" == "$VERSION" ]]; then
    echo "✓ 公共仓库已更新：$APP_DIR v$VERSION（$RAW）"
    exit 0
  fi
  echo "  远端 latest.json 仍是 ${GOT:-未知}，$((attempt * 3))s 后重查 ..."
  sleep $((attempt * 3))
done

echo "✗ 公共仓库 latest.json 未指向 v$VERSION —— 请检查 $PUBLIC_SLUG" >&2
exit 1
