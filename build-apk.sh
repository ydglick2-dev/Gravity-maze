#!/usr/bin/env bash
# Builds the NOVA-9 APK: copies web/ into the Android assets, then runs Gradle.
# Needs a JDK 17+, Gradle 8.x and an Android SDK (set ANDROID_HOME or android/local.properties).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "→ syncing web/ into the app assets"
rm -rf "$here/android/app/src/main/assets/www"
mkdir -p "$here/android/app/src/main/assets/www"
cp -r "$here/web/." "$here/android/app/src/main/assets/www/"

echo "→ building"
cd "$here/android"
gradle assembleDebug --no-daemon

apk="$here/android/app/build/outputs/apk/debug/app-debug.apk"
mkdir -p "$here/dist"
cp "$apk" "$here/dist/nova9.apk"
echo "✓ $here/dist/nova9.apk"
