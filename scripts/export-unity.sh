#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/android-env.sh"
cd "$CIRCUIT_REPO"
if [ ! -x "$UNITY_EDITOR" ]; then echo 'Set UNITY_EDITOR to Unity 6000.6.2f1 with Android Build Support.' >&2; exit 1; fi
mkdir -p build
"$UNITY_EDITOR" -batchmode -nographics -quit -buildTarget Android -projectPath "$CIRCUIT_REPO/unity" -executeMethod CircuitXR.Editor.AndroidExport.Export -logFile "$CIRCUIT_REPO/build/unity-export.log"
python3 scripts/integrate-unity-export.py
./scripts/build-android.sh "$@"
