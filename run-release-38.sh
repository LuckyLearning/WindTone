#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8
export WINDTONE_JDK="E:/workspace/jdk/jdk-21"
export JAVA_HOME="E:/workspace/jdk/jdk-21"
mkdir -p build
LOG=build/release-1.2.38.log
echo "====START RELEASE $(date)====" | tee "$LOG"
set +e
bash scripts/release.sh patch --verify 2>&1 | tee -a "$LOG"
RC=${PIPESTATUS[0]}
set -e
echo "====RELEASE EXIT $RC at $(date)====" | tee -a "$LOG"
exit "$RC"
