#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME=/e/workspace/jdk/jdk-17
export PATH="$JAVA_HOME/bin:$PATH"
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8
cd /e/workspace/Android/WindTone
java -version
bash scripts/release.sh patch --verify
