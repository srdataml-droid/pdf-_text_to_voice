#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or 21}"
if [[ ! -f app/src/main/jniLibs/arm64-v8a/libanchor_llama.so ]]; then
  bash scripts/build-native.sh
fi
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
