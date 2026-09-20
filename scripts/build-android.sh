#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/android-env.sh"
cd "$CIRCUIT_REPO"
if [ ! -x "${JAVA_HOME:-}/bin/java" ] || [ ! -d "$ANDROID_HOME/platforms" ]; then
  echo 'Install JDK 17 and Android SDK 35, or set JAVA_HOME and ANDROID_HOME.' >&2
  exit 1
fi
exec ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug "$@"
