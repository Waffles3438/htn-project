#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/android-env.sh"
mkdir -p "$CIRCUIT_REPO/build"
"$UNITY_EDITOR" -batchmode -nographics -quit -projectPath "$CIRCUIT_REPO/unity" -executeMethod CircuitXR.Editor.NativeModelExport.Export -logFile "$CIRCUIT_REPO/build/native-model-export.log"
