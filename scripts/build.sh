#!/bin/zsh
# Builds Montana for Android with the platform's own tools only — no Gradle, no libraries.
#
#   aapt2      resources  → R.java + a resource package
#   javac      R.java     → classes
#   kotlinc    our Kotlin → classes
#   d8         classes    → classes.dex (the phone's bytecode)
#   zip        dex + the core (libmt_bindings.so) into the package
#   zipalign + apksigner  → an installable, signed APK
#
# Usage: scripts/build.sh [--install]
set -euo pipefail

ROOT="${0:A:h:h}"
SDK="$HOME/Library/Android/sdk"
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
KOTLINC="$HOME/Library/Montana-tools/kotlinc/bin/kotlinc"
STDLIB="$HOME/Library/Montana-tools/kotlinc/lib/kotlin-stdlib.jar"
CORE_SO="$ROOT/native/montana-jni/target/aarch64-linux-android/release/libmontana.so"

# The version: scripts/version says how it is made.
source "$ROOT/scripts/version"
if [[ -n "$TARGET" ]]; then
    # catching up: our own build is the count of commits on main, and the iOS build we go to stands beside it
    VERSION_CODE=$(git -C "$ROOT" rev-list --count HEAD)
    (( VERSION_CODE < TARGET )) || { echo "own build $VERSION_CODE reached $TARGET: the jump to $TARGET would go backwards (scripts/version)"; exit 1; }
    VERSION_NAME="$MARKETING ($VERSION_CODE → $TARGET)"
else
    # level with iOS: the number is the iOS build Android does what it does
    VERSION_CODE=$BUILD
    VERSION_NAME="$MARKETING ($BUILD)"
fi

APP="$ROOT/app/src/main"
OUT="$ROOT/build"
rm -rf "$OUT"; mkdir -p "$OUT"/{res,gen,classes,dex,apk/lib/arm64-v8a}

[[ -f "$CORE_SO" ]] || { echo "the core is not built: scripts/build-core.sh"; exit 1; }

echo "1/6 resources"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res/compiled.zip"
"$BT/aapt2" link -I "$ANDROID_JAR" --manifest "$APP/AndroidManifest.xml" \
    --min-sdk-version 26 --target-sdk-version 35 \
    --version-code $VERSION_CODE --version-name "$VERSION_NAME" \
    --java "$OUT/gen" --custom-package quest.montana.app -o "$OUT/base.apk" "$OUT/res/compiled.zip"

echo "2/6 R.java"
JAVA_SRC=(${(f)"$(find "$OUT/gen" -name '*.java')"})
"$JAVA_HOME/bin/javac" -nowarn --release 17 -cp "$ANDROID_JAR" -d "$OUT/classes" "${JAVA_SRC[@]}"

echo "3/6 Kotlin"
KT_SRC=(${(f)"$(find "$APP/kotlin" -name '*.kt')"})
"$KOTLINC" -nowarn -jvm-target 17 -no-reflect -no-stdlib \
    -cp "$ANDROID_JAR:$STDLIB:$OUT/classes" -d "$OUT/classes" \
    -Xjvm-default=all \
    "${KT_SRC[@]}" 2>&1 | grep -v '^warning:' || true
[[ -f "$OUT/classes/quest/montana/app/MainActivity.class" ]] || { echo "Kotlin failed"; exit 1; }

echo "4/6 dex"
CLASSES=(${(f)"$(find "$OUT/classes" -name '*.class')"})
"$BT/d8" --release --min-api 26 --lib "$ANDROID_JAR" --output "$OUT/dex" \
    "${CLASSES[@]}" "$STDLIB"

echo "5/6 package"
cp "$OUT/dex/classes.dex" "$OUT/apk/"
cp "$CORE_SO" "$OUT/apk/lib/arm64-v8a/"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/apk" && zip -q -r "$OUT/unsigned.apk" classes.dex && zip -q -r -0 "$OUT/unsigned.apk" lib)

echo "6/6 align + sign"
KS="$HOME/.android/debug.keystore"
[[ -f "$KS" ]] || "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
"$BT/zipalign" -f -P 16 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out "$OUT/Montana.apk" "$OUT/aligned.apk"
ls -lh "$OUT/Montana.apk" | awk '{print "   " $5 "  " $9}'

if [[ "${1:-}" == "--install" ]]; then
  "$SDK/platform-tools/adb" install -r "$OUT/Montana.apk"
  "$SDK/platform-tools/adb" shell am start -n quest.montana.app.dev/quest.montana.app.MainActivity
fi
