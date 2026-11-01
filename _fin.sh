#!/bin/bash
set -euo pipefail
cd /mnt/e/workspace/Android/WindTone
rm -f _tmp_cleanup.sh _tmp_do_release.sh _tmp_check_quota.sh
git add -A
git status -sb
if ! git diff --cached --quiet; then
  git commit -m "chore: drop leftover temp release scripts"
  bash scripts/push.sh origin main
fi
git status -sb
git log -3 --oneline