#!/usr/bin/env bash
# ===================================================================
#  用 .github-token 里的 Token 推送代码，Token 不会写进 remote URL。
#
#  用法：
#    bash scripts/push.sh                 # 推当前分支到 origin
#    bash scripts/push.sh origin main     # 指定 remote 与分支
# ===================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOKEN_FILE="$ROOT/.github-token"
REMOTE="${1:-origin}"
BRANCH="${2:-$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)}"

if [[ ! -f "$TOKEN_FILE" ]]; then
  echo "缺少 $TOKEN_FILE —— 请先复制 .github-token.example 并填入 Token" >&2
  exit 1
fi

# 只取合法 token 行，忽略注释
TOKEN="$(grep -oE '(ghp_|github_pat_)[A-Za-z0-9_]+' "$TOKEN_FILE" | head -1)"
if [[ -z "$TOKEN" ]]; then
  echo "$TOKEN_FILE 里没找到合法 Token（应以 ghp_ 或 github_pat_ 开头）" >&2
  exit 1
fi

# 用一个临时 askpass 脚本把 token 交给 git，避免出现在命令行/配置里
ASKPASS="$(mktemp)"
trap 'rm -f "$ASKPASS"' EXIT
cat > "$ASKPASS" <<EOF
#!/usr/bin/env bash
case "\$1" in
  *Username*) echo "LuckyLearning" ;;
  *Password*) echo "$TOKEN" ;;
  *)          echo "$TOKEN" ;;
esac
EOF
chmod +x "$ASKPASS"

echo "→ 推送到 $REMOTE/$BRANCH ..."
GIT_ASKPASS="$ASKPASS" GIT_TERMINAL_PROMPT=0 \
  git -C "$ROOT" push "$REMOTE" "$BRANCH"
echo "✓ 推送完成"
