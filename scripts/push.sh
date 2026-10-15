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

# 用一个临时 askpass 脚本把 token 交给 git，避免出现在命令行/配置里。
#
# ⚠️ 两个坑（2026-10-16 实测，六次发版 tag 静默失败的总根因）：
#  1) 本机 Git Bash 的 mktemp 返回的是**混合斜杠的盘符路径**
#     （C:\Users\...\Temp/tmp.xxx，MSYS 的 /tmp 被映射成了 Windows 路径），
#     沙箱的 safe-delete 守卫拒绝删除带盘符前缀的路径，rm -f 会失败；
#     → 用 cygpath -u 把它归一成 /c/... 形式再交给 rm。
#  2) **EXIT trap 里最后一条命令失败会覆盖整个脚本的退出码** —— 上面那条
#     rm 失败，导致 push 明明成功、本脚本却返回 1，调用方 release.sh 的
#     set -e 便在「推 main」之后当场中止，tag 推送永远执行不到。
#     → trap 里的 rm 加 || true，任何情况下不污染退出码。
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

echo "→ 推送到 $REMOTE/$BRANCH ..."
GIT_ASKPASS="$ASKPASS" GIT_TERMINAL_PROMPT=0 \
  git -C "$ROOT" push "$REMOTE" "$BRANCH"
echo "✓ 推送完成"
