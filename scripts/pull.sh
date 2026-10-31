#!/usr/bin/env bash
# ===================================================================
#  用 .github-token 拉取代码，Token 不会写进 remote URL。
#  与 push.sh 同一套 GIT_ASKPASS；临时清空 credential.helper，避免 GCM 弹「选账号」。
#  用法：
#    bash scripts/pull.sh                 # fetch + ff-only 当前分支
#    bash scripts/pull.sh origin main     # 指定 remote 与分支
# ===================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOKEN_FILE="$ROOT/.github-token"
REMOTE="${1:-origin}"
BRANCH="${2:-$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)}"

if [[ ! -f "$TOKEN_FILE" ]]; then
  echo "缺少 $TOKEN_FILE — 请先复制 .github-token.example 并填入 Token" >&2
  exit 1
fi

TOKEN="$(grep -oE '(ghp_|github_pat_)[A-Za-z0-9_]+' "$TOKEN_FILE" | head -1)"
if [[ -z "$TOKEN" ]]; then
  echo "$TOKEN_FILE 里没找到合法 Token（应以 ghp_ 或 github_pat_ 开头）" >&2
  exit 1
fi

ASKPASS="$(mktemp)"
trap 'rm -f "$ASKPASS" 2>/dev/null || true' EXIT
if command -v cygpath >/dev/null 2>&1; then
  ASKPASS="$(cygpath -u "$ASKPASS")"
fi
cat > "$ASKPASS" <<EOF
#!/usr/bin/env bash
case "\$1" in
  *Username*) echo "LuckyLearning" ;;
  *Password*) echo "$TOKEN" ;;
  *)          echo "$TOKEN" ;;
esac
EOF
chmod +x "$ASKPASS"

echo "↓ 拉取 $REMOTE/$BRANCH ..."
GIT_ASKPASS="$ASKPASS" GIT_TERMINAL_PROMPT=0 \
  git -C "$ROOT" -c credential.helper= fetch "$REMOTE" "$BRANCH" --prune

LOCAL="$(git -C "$ROOT" rev-parse HEAD)"
REMOTE_SHA="$(git -C "$ROOT" rev-parse "$REMOTE/$BRANCH")"
if [[ "$LOCAL" == "$REMOTE_SHA" ]]; then
  echo "✓ 已是最新 ${REMOTE_SHA:0:7}"
  exit 0
fi
if git -C "$ROOT" merge-base --is-ancestor "$LOCAL" "$REMOTE_SHA"; then
  git -C "$ROOT" merge --ff-only "$REMOTE/$BRANCH"
  echo "✓ 已快进到 $(git -C "$ROOT" rev-parse --short HEAD)"
else
  echo "本地与 $REMOTE/$BRANCH 已分叉，未自动 merge。请人工处理。" >&2
  git -C "$ROOT" status -sb
  exit 2
fi