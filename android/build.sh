#!/usr/bin/env bash
# Compila, empaqueta, firma e instala la app Android "Sala ATV & Wii"
# para el Motorola Signature (Android 16 / API 35).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_SRC="$SCRIPT_DIR/app/src/main"
BUILD_DIR="$SCRIPT_DIR/build"
OUT_APK="$SCRIPT_DIR/AtvSignatureRemote.apk"

JAVA_HOME="${JAVA_HOME:-$HOME/.antigravity-ide/extensions/redhat.java-1.58.0-darwin-arm64/jre/25.0.4.1-macosx-aarch64}"
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"

TOOLS_DIR="$HOME/.local/android/tools"
AAPT2="$TOOLS_DIR/aapt2"
ANDROID_JAR="$TOOLS_DIR/android.jar"
R8_JAR="$TOOLS_DIR/r8.jar"
SIGNER_JAR="$TOOLS_DIR/uber-apk-signer.jar"
ADB="${ADB:-$HOME/.local/bin/adb}"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/obj" "$BUILD_DIR/dex" "$BUILD_DIR/res"

echo "==> [1/5] Compilando recursos con aapt2..."
"$AAPT2" compile --dir "$APP_SRC/res" -o "$BUILD_DIR/res/resources.zip"
"$AAPT2" link \
    -I "$ANDROID_JAR" \
    --manifest "$APP_SRC/AndroidManifest.xml" \
    -o "$BUILD_DIR/unsigned.apk" \
    --java "$BUILD_DIR/gen" \
    "$BUILD_DIR/res/resources.zip"

echo "==> [2/5] Compilando código Java (API 35)..."
find "$APP_SRC/java" "$BUILD_DIR/gen" -name "*.java" > "$BUILD_DIR/sources.txt"
"$JAVAC" --release 17 \
    -cp "$ANDROID_JAR" \
    -d "$BUILD_DIR/obj" \
    "@$BUILD_DIR/sources.txt"

echo "==> [3/5] Empaquetando bytecode DEX con D8/R8..."
find "$BUILD_DIR/obj" -name "*.class" > "$BUILD_DIR/classes.txt"
"$JAVA" -cp "$R8_JAR" com.android.tools.r8.D8 \
    --min-api 28 \
    --lib "$ANDROID_JAR" \
    --output "$BUILD_DIR/dex" \
    "@$BUILD_DIR/classes.txt"

echo "==> [4/5] Ensamblando y firmando APK v2/v3..."
cp "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/unaligned.apk"
(cd "$BUILD_DIR/dex" && zip -q "$BUILD_DIR/unaligned.apk" classes.dex)
"$JAVA" -jar "$SIGNER_JAR" \
    --apks "$BUILD_DIR/unaligned.apk" \
    --out "$BUILD_DIR/signed" >/dev/null

cp "$BUILD_DIR/signed/unaligned-aligned-debugSigned.apk" "$OUT_APK"
echo "✓ APK generado: $OUT_APK ($(du -h "$OUT_APK" | cut -f1))"

if [[ "${1:-}" == "--install" ]]; then
    echo "==> [5/5] Instalando en Motorola Signature vía ADB..."
    "$ADB" install -r "$OUT_APK"
    echo "✓ App instalada en el Motorola Signature"
fi
