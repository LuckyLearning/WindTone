#!/usr/bin/env bash
# ===================================================================
#  AnyLyric 发版助手：改版本号 → 提交 → 打 tag → 推送
#
#  推 `anylyric-v*` tag 后，.github/workflows/anylyric-release.yml 会自动
#  跑单测 + lint 门禁、构建签名 APK、创建 GitHub Release，并把安装包发到
#  公共分发仓库 LuckyLearning/Release 的 AnyLyric/ 目录下。
#
#  用法：
#    bash scripts/release-anylyric.sh --tag-current  # 用当前版本号直接发（首发/重发）
#    bash scripts/release-anylyric.sh patch          # 0.1.0 → 0.1.1
#    bash scripts/release-anylyric.sh minor          # 0.1.0 → 0.2.0
#    bash scripts/release-anylyric.sh 0.3.0          # 指定版本号
#    bash scripts/release-anylyric.sh patch --dry    # 只打印将要做什么
#    bash scripts/release-anylyric.sh patch --verify # 推完等 CI + 核对公共仓库
#
#  ⚠️ 与 WindTone 的 release.sh 是**两条独立版本线**（0.x 与 1.2.x），
#     tag 前缀也不同（anylyric-v* / v*），互不触发对方的流水线。
#  ⚠️ 判断成败以输出里的「✓ 已推送 ...（远端已确认）」为准 —— 管道会吃掉退出码。
# ===================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_FILE="$ROOT/anylyric/build.gradle.kts"
SLUG="LuckyLearning/WindTone"
PUBLIC_SLUG="LuckyLearning/Release"

DRY=0
VERIFY=0
TAG_CURRENT=0
ARGS=()
for arg in "$@"; do
  case "$arg" in
    --dry) DRY=1 ;;
    --verify) VERIFY=1 ;;
    --tag-current) TAG_CURRENT=1 ;;
    --*) echo "无法识别的开关：$arg（只支持 --dry / --verify / --tag-current）" >&2; exit 1 ;;
    *) ARGS+=("$arg") ;;
  esac
done
BUMP="${ARGS[0]:-patch}"

[[ -f "$GRADLE_FILE" ]] || { echo "找不到 $GRADLE_FILE" >&2; exit 1; }

CUR_CODE=$(grep -oP 'anyLyricVersionCode\s*=\s*\K[0-9]+' "$GRADLE_FILE" | head -1)
CUR_NAME=$(grep -oP 'anyLyricVersionName\s*=\s*"\K[^"]+' "$GRADLE_FILE" | head -1)
[[ -n "$CUR_CODE" && -n "$CUR_NAME" ]] || { echo "无法从 $GRADLE_FILE 读到 anyLyricVersionName / anyLyricVersionCode" >&2; exit 1; }

if (( TAG_CURRENT )); then
  NEW_NAME="$CUR_NAME"
  NEW_CODE="$CUR_CODE"
else
  IFS='.' read -r MA MI PA <<< "$CUR_NAME"
  case "$BUMP" in
    major) NEW_NAME="$((MA + 1)).0.0" ;;
    minor) NEW_NAME="$MA.$((MI + 1)).0" ;;
    patch) NEW_NAME="$MA.$MI.$((PA + 1))" ;;
    [0-9]*.[0-9]*.[0-9]*) NEW_NAME="$BUMP" ;;
    *) echo "无法识别的参数：$BUMP（用 major/minor/patch、3.5.2 这样的版本号，或 --tag-current）" >&2; exit 1 ;;
  esac
  NEW_CODE=$((CUR_CODE + 1))
fi
TAG="anylyric-v$NEW_NAME"

echo "版本：$CUR_NAME (code $CUR_CODE)  →  $NEW_NAME (code $NEW_CODE)"
echo "tag ：$TAG"

if (( DRY )); then
  echo "（--dry：未做任何改动）"
  exit 0
fi

# 1) Release 说明预览：固定模板 + 只替换版本号，正文不需要手写
echo
echo "→ 本次 Release 说明预览（由 .github/release-notes/anylyric-template.md 自动渲染）："
echo "----------------------------------------------------------------"
bash "$ROOT/scripts/render-release-notes.sh" \
  --gradle anylyric/build.gradle.kts \
  --template .github/release-notes/anylyric-template.md \
  --version-key anyLyricVersionName \
  --code-key anyLyricVersionCode \
  "$NEW_NAME" "$NEW_CODE"
echo "----------------------------------------------------------------"

# 2) 写入版本号（只有 --tag-current 时不动文件）
if (( TAG_CURRENT )); then
  echo "→ --tag-current：沿用当前版本号，不改构建文件"
else
  echo "→ 写入版本号..."
  sed -i "s/val anyLyricVersionCode = $CUR_CODE/val anyLyricVersionCode = $NEW_CODE/" "$GRADLE_FILE"
  sed -i "s/val anyLyricVersionName = \"$CUR_NAME\"/val anyLyricVersionName = \"$NEW_NAME\"/" "$GRADLE_FILE"
  grep -E 'anyLyricVersionName|anyLyricVersionCode' "$GRADLE_FILE"
fi

# 3) 提交 + 打 tag
if [[ -n "$(git -C "$ROOT" status --porcelain)" ]]; then
  echo "→ 工作区有未提交改动，一并提交到本次发版（commit: release: $TAG）"
  git -C "$ROOT" add -A
  git -C "$ROOT" commit -m "release: $TAG"
else
  echo "→ 工作区干净，跳过提交"
fi
git -C "$ROOT" tag "$TAG"

# 4) 推送 + 推后实查（不信任 push 的返回值，以 REST 实查远端为准）
BRANCH="$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)"
HEAD_SHA="$(git -C "$ROOT" rev-parse HEAD)"
TOKEN="$(grep -oE '(ghp_|github_pat_)[A-Za-z0-9_]+' "$ROOT/.github-token" 2>/dev/null | head -1 || true)"

api_code() {
  curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" \
    "https://api.github.com/repos/$SLUG/$1"
}

branch_on_remote() {
  [[ -n "$TOKEN" ]] || return 1
  [[ "$(api_code "git/refs/heads/$BRANCH")" == "200" ]] || return 1
  local remote_sha
  remote_sha="$(curl -s -H "Authorization: Bearer $TOKEN" \
    "https://api.github.com/repos/$SLUG/git/refs/heads/$BRANCH" \
    | grep -o '"sha": *"[0-9a-f]\{40\}"' | head -1 | grep -o '[0-9a-f]\{40\}')"
  [[ "$remote_sha" == "$HEAD_SHA" ]]
}

tag_on_remote() {
  [[ -n "$TOKEN" ]] || return 1
  [[ "$(api_code "git/refs/tags/$TAG")" == "200" ]]
}

push_and_confirm() { # $1 = ref, $2 = 校验函数, $3 = 成功提示
  local ref="$1" check="$2" ok_msg="$3" attempt
  for attempt in 1 2; do
    if (( attempt == 2 )); then
      echo "  → 第 1 次推送后远端查不到 $ref，补推一次..."
    fi
    bash "$ROOT/scripts/push.sh" origin "$ref" || echo "  （push.sh 返回非零：$?，以下一次远端实查为准）"
    if "$check"; then
      echo "$ok_msg"
      return 0
    fi
  done
  echo "✗ $ref 推送后远端实查两次都不通过 —— 请手动执行：bash scripts/push.sh origin $ref" >&2
  return 1
}

push_and_confirm "$BRANCH" branch_on_remote "✓ 已推送 $BRANCH（远端 SHA 确认为 ${HEAD_SHA:0:7}）"
push_and_confirm "$TAG" tag_on_remote "✓ 已推送 $TAG（远端已确认）"

# 5) 可选：等 CI 跑完 + 核对公共仓库
if (( VERIFY )); then
  echo
  echo "→ 等 AnyLyric Release 流水线（约 3~6 分钟）..."
  run_id=""
  for attempt in $(seq 1 120); do
    run_id="$(curl -s -H "Authorization: Bearer $TOKEN" \
      "https://api.github.com/repos/$SLUG/actions/workflows/anylyric-release.yml/runs?event=push&per_page=20" \
      | python3 -c '
import json, sys
target = sys.argv[1]
try:
    data = json.load(sys.stdin)
except Exception:
    sys.exit(0)
for r in data.get("workflow_runs", []):
    if r.get("head_sha") == target:
        print(r["id"]); break
' "$HEAD_SHA")"
    if [[ -n "$run_id" ]]; then break; fi
    sleep 10
  done

  if [[ -z "$run_id" ]]; then
    echo "✗ 没找到本次 tag 的流水线 run，请到 Actions 页面确认" >&2
    exit 1
  fi
  echo "  run: https://github.com/$SLUG/actions/runs/$run_id"

  for attempt in $(seq 1 60); do
    state="$(curl -s -H "Authorization: Bearer $TOKEN" \
      "https://api.github.com/repos/$SLUG/actions/runs/$run_id" \
      | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    print("unknown"); sys.exit(0)
print(d.get("status"), d.get("conclusion"))
')"
    case "$state" in
      "completed success")
        echo "✓ 流水线成功"; break ;;
      "completed "*)
        echo "✗ 流水线失败：$state —— 见 https://github.com/$SLUG/actions/runs/$run_id" >&2
        exit 1 ;;
      *)
        sleep 20 ;;
    esac
  done

  echo "→ 核对公共仓库 AnyLyric/latest.json ..."
  # 只看 raw 会误判：raw 走 Fastly CDN，仓库更新后仍可能返回上一版（实测滞后几十秒甚至更久，
  # 2026-11-12 发 0.2.3 时就报了一次假失败）。所以以 GitHub API（读的是仓库本体）为准，
  # raw 只作为「顺便确认 CDN 也跟上了」的附加检查。
  RAW="https://raw.githubusercontent.com/$PUBLIC_SLUG/main/AnyLyric/latest.json"
  api_manifest_version() {
    [[ -n "$TOKEN" ]] || return 1
    curl -s -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github.raw" \
      "https://api.github.com/repos/$PUBLIC_SLUG/contents/AnyLyric/latest.json?ref=main" 2>/dev/null \
      | grep -o '"version": *"[0-9.]*"' | head -1 | grep -o '[0-9][0-9.]*' || true
  }
  for attempt in 1 2 3 4 5; do
    GOT="$(curl -fsSL "$RAW?nocache=$(date +%s)-$attempt" 2>/dev/null \
      | grep -o '"version": *"[0-9.]*"' | head -1 | grep -o '[0-9][0-9.]*' || true)"
    if [[ "$GOT" == "$NEW_NAME" ]]; then
      echo "✓ 公共仓库已更新：AnyLyric v$NEW_NAME（$RAW）"
      exit 0
    fi
    IN_REPO="$(api_manifest_version || true)"
    if [[ "$IN_REPO" == "$NEW_NAME" ]]; then
      echo "✓ 公共仓库已更新：AnyLyric v$NEW_NAME（API 已确认；raw 侧 CDN 仍缓存 ${GOT:-旧版}，属正常滞后）"
      exit 0
    fi
    echo "  raw=${GOT:-未知} 仓库=${IN_REPO:-未知}，$((attempt * 3))s 后重查 ..."
    sleep $((attempt * 3))
  done
  echo "✗ 公共仓库 latest.json 未指向 v$NEW_NAME —— 请检查 $PUBLIC_SLUG" >&2
  exit 1
fi

echo
echo "  AnyLyric Release： https://github.com/$SLUG/actions/workflows/anylyric-release.yml"
echo "  复核公共仓库： curl -fsSL https://raw.githubusercontent.com/$PUBLIC_SLUG/main/AnyLyric/latest.json"
