#!/usr/bin/env bash
# Source this file to use the local toolchain; it does not modify your shell profile.
if [ -n "${ZSH_VERSION:-}" ]; then
  CIRCUIT_ENV_FILE="${(%):-%x}"
else
  CIRCUIT_ENV_FILE="${BASH_SOURCE[0]}"
fi
CIRCUIT_REPO="$(cd "$(dirname "$CIRCUIT_ENV_FILE")/.." && pwd)"
CIRCUIT_TOOLS="$(dirname "$CIRCUIT_REPO")/.android-tools"
export ANDROID_HOME="${ANDROID_HOME:-$CIRCUIT_TOOLS/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [ -z "${JAVA_HOME:-}" ]; then
  for CIRCUIT_JDK in "$CIRCUIT_TOOLS"/jdk-*/Contents/Home; do
    if [ -x "$CIRCUIT_JDK/bin/java" ]; then export JAVA_HOME="$CIRCUIT_JDK"; break; fi
  done
fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$CIRCUIT_TOOLS/gradle}"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
export UNITY_EDITOR="${UNITY_EDITOR:-$CIRCUIT_TOOLS/Unity-6000.6.2f1/Unity.app/Contents/MacOS/Unity}"
