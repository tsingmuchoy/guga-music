#!/usr/bin/env bash
# 手动管线打包（本项目未使用 Gradle，原因见 README「构建」一节）
set -e
cd "$(dirname "$0")"
JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk17}"
ANDROID_SDK="${ANDROID_SDK:-$HOME/tools/android-sdk}"
BT="$ANDROID_SDK/build-tools/34.0.0"
AJ="$ANDROID_SDK/platforms/android-34/android.jar"
SRC="$PWD/src/main"
OUT="$PWD/build-manual"
VER_CODE="${1:-1}"
VER_NAME="${2:-1.0.0}"
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p "$OUT"
if [ ! -f "$OUT/debug.keystore" ]; then
  keytool -genkeypair -keystore "$OUT/debug.keystore" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
fi
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/compiled.zip"
"$BT/aapt2" link -o "$OUT/app-unsigned.apk" -I "$AJ" --manifest "$SRC/AndroidManifest.xml" \
  --java "$OUT/gen" --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VER_CODE" --version-name "$VER_NAME" "$OUT/compiled.zip"
find "$SRC/java" "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
rm -rf "$OUT/classes" "$OUT/dex"; mkdir -p "$OUT/classes" "$OUT/dex"
javac -source 8 -target 8 -encoding UTF-8 -cp "$AJ" -d "$OUT/classes" @"$OUT/sources.txt"
"$BT/d8" --lib "$AJ" --min-api 26 --output "$OUT/dex" $(find "$OUT/classes" -name "*.class")
cp "$OUT/app-unsigned.apk" "$OUT/app-with-dex.apk"
(cd "$OUT" && zip -j app-with-dex.apk dex/classes.dex > /dev/null)
"$BT/zipalign" -p -f 4 "$OUT/app-with-dex.apk" "$OUT/app-aligned.apk"
"$BT/apksigner" sign --ks "$OUT/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --out "$PWD/app-v$VER_NAME.apk" "$OUT/app-aligned.apk"
echo "✔ 已生成 app-v$VER_NAME.apk"
