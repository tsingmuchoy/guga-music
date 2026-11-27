#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_SDK:?Set ANDROID_SDK to the Android SDK directory}"
BT="$ANDROID_SDK/build-tools/${BT_VER:-34.0.0}"
AJ="$ANDROID_SDK/platforms/android-${PLAT_VER:-34}/android.jar"
OUT="$PWD/build-manual"
mkdir -p "$OUT/test-classes" "$OUT/test-dex"
"$BT/aapt2" link -o "$OUT/test.apk" -I "$AJ" --manifest tests/android/AndroidManifest.xml
javac --release 8 -encoding UTF-8 -cp "$AJ:$OUT/classes" -d "$OUT/test-classes" tests/android/GlassRegressionTest.java
find "$OUT/test-classes" -name '*.class' > "$OUT/test-sources.txt"
"$BT/d8" --lib "$AJ" --classpath "$OUT/classes" --min-api 26 --output "$OUT/test-dex" @"$OUT/test-sources.txt"
(cd "$OUT/test-dex" && zip -j "$OUT/test.apk" classes.dex)
"$BT/zipalign" -f 4 "$OUT/test.apk" "$OUT/test-aligned.apk"
"$BT/apksigner" sign --ks "$OUT/debug.keystore" --ks-pass pass:android --key-pass pass:android "$OUT/test-aligned.apk"
adb install -r app-v1.53.0-dev.apk
adb install -r "$OUT/test-aligned.apk"
adb shell am instrument -w com.guga.music.tests/com.guga.music.GlassRegressionTest | tee "$OUT/android-test.log"
mkdir -p "$OUT/screenshots"
adb pull /sdcard/Android/data/com.guga.music/files/glass-preview/. "$OUT/screenshots/" || true
adb logcat -d -t 300 > "$OUT/logcat.txt"
grep -q 'PASS: visibility' "$OUT/android-test.log"
