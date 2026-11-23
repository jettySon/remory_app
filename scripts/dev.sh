#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
adb="$sdk/platform-tools/adb"
if [ ! -x "$adb" ]; then
  echo 'Android SDK를 찾지 못했어요. ANDROID_HOME을 설정해 주세요.' >&2
  exit 1
fi
if [ -n "${ANDROID_SERIAL:-}" ]; then
  "$adb" -s "$ANDROID_SERIAL" reverse tcp:5173 tcp:5173
else
  "$adb" reverse tcp:5173 tcp:5173
fi
# Prefer Android Studio's bundled Java, then a locally configured JDK.
if [ -z "${JAVA_HOME:-}" ] && [ -d '/Applications/Android Studio.app/Contents/jbr/Contents/Home' ]; then
  JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
  export JAVA_HOME
fi
./gradlew :app:installDebug
"$adb" shell am start -n com.remory.app/.MainActivity
