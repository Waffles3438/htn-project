#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/android-env.sh"
cd "$CIRCUIT_REPO"
mkdir -p build
exec "$UNITY_EDITOR" -batchmode -nographics -projectPath "$CIRCUIT_REPO/unity" -runTests -testPlatform EditMode -testResults "$CIRCUIT_REPO/build/unity-tests.xml" -logFile "$CIRCUIT_REPO/build/unity-tests.log"
