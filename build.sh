#!/usr/bin/env bash
# 小红书 SaveFix LSPosed 模块构建(纯 Java,无 native)。
set -euo pipefail
PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD="$PROJ/build"
die() { echo "ERROR: $*" >&2; exit 1; }

# JDK
JDKHOME="${JAVA_HOME:-}"
if [[ -z "$JDKHOME" || ! -x "$JDKHOME/bin/javac" ]]; then
  command -v javac >/dev/null || die "JDK not found"
  JDKHOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
fi
export JAVA_HOME="$JDKHOME"; JAVAC="$JDKHOME/bin/javac"; KEYTOOL="$JDKHOME/bin/keytool"
echo "JDK = $JDKHOME"

# SDK / build-tools / android.jar
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -d "$SDK" ]] || for c in "$HOME/Android/Sdk" /opt/android-sdk; do [[ -d "$c" ]] && SDK="$c" && break; done
[[ -d "$SDK" ]] || die "Android SDK not found (set ANDROID_HOME)"
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"; BT="${BT%/}"
D8="$BT/d8"; AAPT2="$BT/aapt2"; ZIPALIGN="$BT/zipalign"; APKSIGNER="$BT/apksigner"
ANDROID_JAR="$(ls "$SDK"/platforms/*/android.jar 2>/dev/null | sort -V | tail -1)"
[[ -f "$ANDROID_JAR" ]] || die "android.jar not found"
echo "SDK = $SDK  BT = $BT"

# Keystore
KS="${KEYSTORE:-$PROJ/debug.keystore}"
if [[ ! -f "$KS" ]]; then
  "$KEYTOOL" -genkeypair -v -keystore "$KS" -storepass android -alias androiddebugkey \
    -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Debug,O=Debug,C=US' >/dev/null 2>&1
fi

rm -rf "$BUILD"; mkdir -p "$BUILD/stubs" "$BUILD/app"

echo "== 1. javac stubs =="
find "$PROJ/stubs" -name '*.java' > "$BUILD/stub.list"
"$JAVAC" --release 8 -d "$BUILD/stubs" @"$BUILD/stub.list"

echo "== 2. javac module =="
find "$PROJ/app/src/main/java" -name '*.java' > "$BUILD/src.list"
"$JAVAC" -g -encoding UTF-8 --release 8 -cp "$BUILD/stubs" -d "$BUILD/app" @"$BUILD/src.list"

echo "== 3. d8 -> classes.dex =="
find "$BUILD/app" -name '*.class' > "$BUILD/cls.list"
"$D8" --min-api 22 --no-desugaring --output "$BUILD" @"$BUILD/cls.list"

echo "== 4. aapt2 compile + link =="
"$AAPT2" compile --dir "$PROJ/app/src/main/res" -o "$BUILD/res.zip"
"$AAPT2" link -o "$BUILD/app-unsigned.apk" -I "$ANDROID_JAR" \
  --manifest "$PROJ/app/src/main/AndroidManifest.xml" \
  -A "$PROJ/app/src/main/assets" \
  --min-sdk-version 22 --target-sdk-version 34 "$BUILD/res.zip"

echo "== 5. 塞入 classes.dex =="
( cd "$BUILD" && zip -q -X "$BUILD/app-unsigned.apk" classes.dex )

echo "== 6. zipalign =="
"$ZIPALIGN" -p -f 4 "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk"

echo "== 7. apksigner sign =="
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android --ks-key-alias androiddebugkey \
  --key-pass pass:android --out "$PROJ/xhs-savefix.apk" "$BUILD/app-aligned.apk"

echo "== DONE =="
echo "APK = $PROJ/xhs-savefix.apk  size=$(awk "BEGIN{printf \"%.1f\", $(stat -c%s "$PROJ/xhs-savefix.apk")/1024}")KB"
