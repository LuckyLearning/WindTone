#!/bin/bash
set -euo pipefail
cd /mnt/e/workspace/Android/WindTone
TOKEN=$(grep -oE '(ghp_|github_pat_)[A-Za-z0-9_]+' .github-token | head -1)
# remove accidental temp scripts if present
rm -f _tmp_do_release.sh _tmp_check_quota.sh
if git status --porcelain | grep -q .; then
  git add -A
  git commit -m "chore: remove accidental release helper temp script"
  bash scripts/push.sh origin main
fi
echo "==== release assets ===="
curl -sS -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/LuckyLearning/WindTone/releases/tags/v1.2.44" > /tmp/wt-r244.json
python3 - <<'PY'
import json
d=json.load(open("/tmp/wt-r244.json"))
print("html_url:", d.get("html_url"))
print("tag:", d.get("tag_name"))
print("name:", d.get("name"))
for a in d.get("assets",[]):
    print(a["name"], a["browser_download_url"], a["size"])
print("HEAD:", open("/dev/null"))
PY
git rev-parse HEAD
git log -2 --oneline
grep -E 'appVersionName|appVersionCode' app/build.gradle.kts | head -5