#!/bin/bash
# Ручная сборка APK без Gradle. Требует только Android SDK + JDK.
set -e
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/33.0.2"
BT_D8="$SDK/build-tools/35.0.0-rc4"   # более свежий R8/d8 (в 33 баг на лямбдах)
ANDROID_JAR="$SDK/platforms/android-33/android.jar"
PKG_OUT="build"

echo ">>> clean"
rm -rf "$PKG_OUT"
mkdir -p "$PKG_OUT/gen" "$PKG_OUT/classes" "$PKG_OUT/dex"

echo ">>> aapt2 compile resources"
"$BT/aapt2" compile --dir res -o "$PKG_OUT/res.zip"

echo ">>> aapt2 link"
"$BT/aapt2" link \
  -o "$PKG_OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  --java "$PKG_OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 33 \
  --version-code 1 --version-name 1.0 \
  "$PKG_OUT/res.zip"

echo ">>> javac"
find src "$PKG_OUT/gen" -name '*.java' > "$PKG_OUT/sources.txt"
javac --release 11 \
  -classpath "$ANDROID_JAR" \
  -d "$PKG_OUT/classes" \
  @"$PKG_OUT/sources.txt"

echo ">>> d8 (dex)"
find "$PKG_OUT/classes" -name '*.class' > "$PKG_OUT/classlist.txt"
"$BT_D8/d8" \
  --output "$PKG_OUT/dex" \
  --lib "$ANDROID_JAR" \
  --min-api 26 \
  @"$PKG_OUT/classlist.txt"

echo ">>> add classes.dex into apk"
cp "$PKG_OUT/dex/classes.dex" "$PKG_OUT/classes.dex"
( cd "$PKG_OUT" && zip -q -j base.apk classes.dex )

echo ">>> zipalign"
"$BT/zipalign" -f -p 4 "$PKG_OUT/base.apk" "$PKG_OUT/aligned.apk"

echo ">>> keystore (постоянный, вне build — чтобы обновления ставились поверх)"
KS="debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" \
    -storepass android -keypass android -alias key \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Snowstorm, O=Personal, C=US" >/dev/null 2>&1
fi

echo ">>> apksigner sign"
"$BT/apksigner" sign \
  --ks "$KS" \
  --ks-pass pass:android --key-pass pass:android \
  --out "$PKG_OUT/Snowstorm.apk" \
  "$PKG_OUT/aligned.apk"

echo ">>> verify"
"$BT/apksigner" verify --print-certs "$PKG_OUT/Snowstorm.apk" | head -3
echo ""
echo "DONE -> $PKG_OUT/Snowstorm.apk"
ls -lh "$PKG_OUT/Snowstorm.apk"
