#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME="/c/Users/live_/.jdks/corretto-21.0.3"
export PATH="$JAVA_HOME/bin:$PATH"
cd "/e/workspace/Android/WindTone"
echo "JAVA_HOME=$JAVA_HOME"
java -version
bash scripts/release.sh patch --verify