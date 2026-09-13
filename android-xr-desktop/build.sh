#!/usr/bin/env bash
# Build the XR Desk APK without Gradle, using Android SDK command-line tools.
set -euo pipefail

SDK=/home/gwx/Android/sdk
BT="$SDK/build-tools/35.0.0"
AJAR="$SDK/platforms/android-35/android.jar"
JDK8=/usr/lib/jvm/java-8-openjdk
JDK17=/usr/lib/jvm/java-17-openjdk
PROJ="$(cd "$(dirname "$0")" && pwd)"
OUT="$PROJ/build"

export PATH="$JDK17/bin:$PATH"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"

echo "[1/7] aapt2 compile resources"
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/res.zip"

echo "[2/7] aapt2 link (manifest + res -> base.apk, R.java)"
"$BT/aapt2" link \
    -o "$OUT/base.apk" \
    -I "$AJAR" \
    --manifest "$PROJ/AndroidManifest.xml" \
    --java "$OUT/gen" \
    "$OUT/res.zip"

echo "[3/7] javac"
find "$PROJ/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
"$JDK8/bin/javac" \
    -source 1.8 -target 1.8 \
    -bootclasspath "$AJAR" \
    -classpath "$AJAR" \
    -d "$OUT/classes" \
    @"$OUT/sources.txt"

echo "[4/7] d8"
CLASSES=$(find "$OUT/classes" -name '*.class')
"$JDK17/bin/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --lib "$AJAR" --min-api 30 --output "$OUT/dex" $CLASSES

echo "[5/7] package classes.dex"
cp "$OUT/base.apk" "$OUT/app.apk"
( cd "$OUT/dex" && zip -q "$OUT/app.apk" classes.dex )

echo "[6/7] zipalign"
"$BT/zipalign" -f 4 "$OUT/app.apk" "$OUT/app-aligned.apk"

echo "[7/7] sign"
KS="$PROJ/debug.keystore"
if [ ! -f "$KS" ]; then
    "$JDK17/bin/keytool" -genkeypair -keystore "$KS" -alias androiddebugkey \
        -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
fi
"$BT/apksigner" sign \
    --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/app-signed.apk" "$OUT/app-aligned.apk"
"$BT/apksigner" verify --print-certs "$OUT/app-signed.apk" | head -2

echo
echo "APK: $OUT/app-signed.apk"
