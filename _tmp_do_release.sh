#!/bin/bash
set -euo pipefail
cd /mnt/e/workspace/Android/WindTone
rm -f _tmp_check_quota.sh
# run release; capture full log
# --verify blocks waiting for CI; log to file
bash scripts/release.sh patch --verify 2>&1 | tee /tmp/wt-release-1.2.44.log
echo "RELEASE_EXIT=${PIPESTATUS[0]}"