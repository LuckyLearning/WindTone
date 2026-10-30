#!/usr/bin/env bash
# ===================================================================
#  对本机构建产物（APK / AAB）算 SHA-256，便于与 CI Step Summary 或
#  另一台机器对照。详见 docs/可复现构建.md
#
#  用法：
#    bash scripts/hash-apk.sh
#    bash scripts/hash-apk.sh app/build/outputs/apk/release/*.apk
# ===================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

hash_one() {
  local f="$1"
  if [[ ! -f "$f" ]]; then
    echo "跳过（不是文件）：$f" >&2
    return 0
  fi
  local digest size
  if command -v sha256sum >/dev/null 2>&1; then
    digest="$(sha256sum "$f" | awk '{print $1}')"
  elif command -v shasum >/dev/null 2>&1; then
    digest="$(shasum -a 256 "$f" | awk '{print $1}')"
  else
    echo "需要 sha256sum 或 shasum" >&2
    exit 1
  fi
  if command -v stat >/dev/null 2>&1; then
    size="$(stat -c%s "$f" 2>/dev/null || stat -f%z "$f")"
  else
    size="?"
  fi
  printf '%s  %s  %s\n' "$digest" "$size" "$f"
}

mapfile -t FILES < <(
  if [[ $# -gt 0 ]]; then
    printf '%s\n' "$@"
  else
    # 无参数：扫常见输出目录（存在才进）
    find app/build/outputs/apk app/build/outputs/bundle release \
      -type f \( -name '*.apk' -o -name '*.aab' \) 2>/dev/null | sort || true
  fi
)

if [[ ${#FILES[@]} -eq 0 ]]; then
  echo "未找到 APK/AAB。先 assemble，或传入路径。" >&2
  exit 1
fi

for f in "${FILES[@]}"; do
  hash_one "$f"
done
