#!/usr/bin/env bash
# ===================================================================
#  发版验证链（四步）：tag → workflow → Release 资产 → APK 验签
#
#  为什么必须走这四步（每一条都对应一次真实事故）：
#   ① tag 引用存在       —— 早年 tag 推送静默失败，Release 根本没触发；
#   ② workflow success   —— 构建失败时也可能留着上一版的 Release 资产，不能只看资产；
#   ③ 4 个 ABI 资产齐全   —— 少一个就是某些架构的用户装不上；
#   ④ APK 验签 + 包内版本串 —— CI 缺 KEYSTORE_BASE64 时只打 ::warning:: 且 step 仍
#      success，「构建成功但装不上」就是这么来的；而**字节数相同≠同一个包**
#      （zipalign 对齐会让连续几版体积一模一样），所以要连包内 AndroidManifest
#      的版本串一起核（scripts/verify_apk.py）。
#
#  另附：公共分发仓库 latest.json 是否指向本次版本（应用内更新读的就是它）。
#
#  用法：
#    bash scripts/verify-release.sh v1.1.54                  # 只给 tag，其余自动推导
#    bash scripts/verify-release.sh v1.1.54 Owner/Repo 1.1.54 <sha>
#    bash scripts/verify-release.sh v1.1.54 --skip-ci        # CI 已知跑完，只核后半段
#
#  ⚠️ 访问 GitHub 需要出沙箱（沙箱会断 TLS）：调用方须 dangerouslyDisableSandbox。
# ===================================================================
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ⚠️ 开关和位置参数不能按下标硬取：写成 REPO_SLUG="$2" 的话，
#    `verify-release.sh v1.1.54 --skip-ci` 会把 "--skip-ci" 当成仓库名，
#    于是所有 URL 变成 repos/--skip-ci/... 全 404（2026-10-23 实测踩中）。
#    先把开关摘出来，剩下的按出现顺序回填位置参数。
ARGS=()
SKIP_CI=0
for arg in "$@"; do
  case "$arg" in
    --skip-ci) SKIP_CI=1 ;;
    --*) echo "无法识别的开关：$arg（只支持 --skip-ci）" >&2; exit 1 ;;
    *) ARGS+=("$arg") ;;
  esac
done
TAG="${ARGS[0]:-}"
REPO_SLUG="${ARGS[1]:-}"
NEW_NAME="${ARGS[2]:-}"
HEAD_SHA="${ARGS[3]:-}"

# 仓库名必须形如 Owner/Repo，否则当没给（走 remote 推导），别把脏值带进 URL
[[ "$REPO_SLUG" == */* ]] || REPO_SLUG=""

[[ -n "$TAG" ]] || { echo "用法：bash scripts/verify-release.sh <tag> [Owner/Repo] [version] [head_sha] [--skip-ci]" >&2; exit 1; }
[[ -n "$NEW_NAME" ]] || NEW_NAME="${TAG#v}"
[[ -n "$REPO_SLUG" ]] || REPO_SLUG="$(git -C "$ROOT" remote get-url origin | sed -n 's#.*github\.com[:/]\(.*\)\.git$#\1#p')"
[[ -n "$HEAD_SHA" ]] || HEAD_SHA="$(git -C "$ROOT" rev-parse "${TAG}^{commit}" 2>/dev/null || echo '')"

PUBLIC_REPO="LuckyLearning/Release"   # 安装包公共分发仓库（匿名可访问）
TOKEN="$(grep -oE '(ghp_|github_pat_)[A-Za-z0-9_]+' "$ROOT/.github-token" 2>/dev/null | head -1)"
[[ -n "$TOKEN" ]] || { echo "读不到 .github-token，无法查 REST" >&2; exit 1; }

FAILURES=0
api_code() { curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" \
  "https://api.github.com/repos/$REPO_SLUG/$1"; }
json_field() { echo "$1" | grep -o "\"$2\": *\"[^\"]*\"" | head -1 | sed 's/.*: "//;s/"$//'; }
pick_python() {
  local c
  for c in "${PYTHON_BIN:-}" python3 python py \
           "/c/Users/stu_l/.workbuddy/binaries/python/versions/3.13.12/python.exe"; do
    [[ -n "$c" ]] || continue
    if command -v "$c" >/dev/null 2>&1 || [[ -x "$c" ]]; then echo "$c"; return 0; fi
  done
  return 1
}

echo "→ 四步验证链（$TAG / $NEW_NAME）"

# ① tag ---------------------------------------------------------------
if [[ "$(api_code "git/refs/tags/$TAG")" == "200" ]]; then
  echo "  ① tag $TAG 远端存在 ✅"
else
  echo "  ① tag $TAG 远端查不到 ❌"; FAILURES=$((FAILURES + 1))
fi

# ② workflow ----------------------------------------------------------
if (( SKIP_CI )); then
  echo "  ② workflow：--skip-ci，跳过"
else
  RUN_ID=""
  for i in $(seq 1 20); do
    RUN_JSON="$(curl -s -H "Authorization: Bearer $TOKEN" \
      "https://api.github.com/repos/$REPO_SLUG/actions/runs?head_sha=$HEAD_SHA&per_page=1")"
    RUN_ID="$(echo "$RUN_JSON" | grep -o '"id": *[0-9]\+' | head -1 | grep -o '[0-9]\+' || true)"
    [[ -n "$RUN_ID" ]] && break
    sleep 15
  done
  if [[ -z "$RUN_ID" ]]; then
    echo "  ② 找不到 workflow run ❌（head_sha=$HEAD_SHA）"; FAILURES=$((FAILURES + 1))
  else
    echo "  ② workflow run $RUN_ID → https://github.com/$REPO_SLUG/actions/runs/$RUN_ID"
    ST=""; CC=""
    for i in $(seq 1 40); do
      RUN_ONE="$(curl -s -H "Authorization: Bearer $TOKEN" \
        "https://api.github.com/repos/$REPO_SLUG/actions/runs/$RUN_ID")"
      ST="$(json_field "$RUN_ONE" status)"
      CC="$(json_field "$RUN_ONE" conclusion)"
      echo "     轮询 #$i：status=$ST conclusion=${CC:-null}"
      [[ "$ST" == "completed" ]] && break
      sleep 45
    done
    if [[ "$CC" == "success" ]]; then
      echo "     → success ✅"
    else
      echo "     → 未成功（conclusion=${CC:-未完成}）❌"; FAILURES=$((FAILURES + 1))
    fi
  fi
fi

# ③ Release 资产 ------------------------------------------------------
REL_JSON="$(curl -s -H "Authorization: Bearer $TOKEN" \
  "https://api.github.com/repos/$REPO_SLUG/releases/tags/$TAG")"
ASSET_N="$(echo "$REL_JSON" | grep -c "\"name\": *\"WindTone-v$NEW_NAME" || true)"
if [[ "$ASSET_N" == "4" ]]; then
  echo "  ③ Release 资产：4 个 ABI 齐全 ✅"
else
  echo "  ③ Release 资产：$ASSET_N 个（应为 4）❌"; FAILURES=$((FAILURES + 1))
fi

# ③b 公共分发仓库 latest.json -----------------------------------------
latest_version() {
  # 公共仓库匿名可读，优先走 raw：少一层 base64，也避开 fine-grained PAT 可能只对
  # WindTone 单仓库授权的情况。raw 有 CDN 缓存，拿不到再回退 contents API。
  local v
  v="$(curl -sL "https://raw.githubusercontent.com/$PUBLIC_REPO/main/WindTone/latest.json" \
        | grep -o '"version": *"[^"]*"' | head -1 | sed 's/.*: "//;s/"$//')"
  [[ -n "$v" ]] && { echo "$v"; return 0; }

  # 回退：contents API 返回 base64。⚠️ JSON 字符串里的换行是**字面上**的 \n 两个
  # 字符，tr -d '\n\r' 只删真换行、删不掉它，base64 -d 会中途报 invalid input
  # 然后静默截断（2026-10-23 实测踩中，表现为「latest.json 读不到」）。
  # 所以必须先用 sed 把字面 \n 抹掉再解码。
  curl -s -H "Authorization: Bearer $TOKEN" \
    "https://api.github.com/repos/$PUBLIC_REPO/contents/WindTone/latest.json" \
    | grep -o '"content": *"[^"]*"' | head -1 \
    | sed 's/.*: "//;s/"$//;s/\\n//g' | tr -d '\n\r' \
    | base64 -d 2>/dev/null \
    | grep -o '"version": *"[^"]*"' | head -1 | sed 's/.*: "//;s/"$//'
}
LATEST_V="$(latest_version)"
if [[ "$LATEST_V" == "$NEW_NAME" ]]; then
  echo "  ③b 公共分发仓库 latest.json = $NEW_NAME ✅"
else
  echo "  ③b latest.json = ${LATEST_V:-读不到}（应为 $NEW_NAME）⚠️ —— CI 的 publish 步骤可能还没跑完，等 45s 补查"
  sleep 45
  LATEST_V="$(latest_version)"
  if [[ "$LATEST_V" == "$NEW_NAME" ]]; then
    echo "     补查通过 ✅"
  else
    echo "     补查仍不符 ⚠️（不阻塞发版，但应用内更新会拿不到新版；见 docs/发版规范）"
  fi
fi

# ④ APK 验签 + 包内版本串 ---------------------------------------------
PY="$(pick_python)" || PY=""
if [[ -n "$PY" ]]; then
  echo "  ④ 下载 universal 包验签（python: $PY）"
  if "$PY" "$ROOT/scripts/verify_apk.py" "$TAG" "$REPO_SLUG"; then
    echo "     → PASS ✅"
  else
    echo "     → FAIL ❌"; FAILURES=$((FAILURES + 1))
  fi
else
  echo "  ④ 找不到 python，跳过验签 ⚠️（手动：$PY scripts/verify_apk.py $TAG）"
fi

echo
if (( FAILURES == 0 )); then
  echo "✓ 验证链全部通过：$TAG"
  exit 0
fi
echo "✗ 验证链有 $FAILURES 项未通过：$TAG"
exit 1
