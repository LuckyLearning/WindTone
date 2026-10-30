#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME="C:/Users/live_/.jdks/oracle_open_jdk-21"
export PATH="$JAVA_HOME/bin:$PATH"
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8
cd "E:/workspace/Android/WindTone"
pwd
java -version
bash scripts/release.sh patch --verify
