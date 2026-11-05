#!/usr/bin/env bash
# Build and sign the TV Ekran screenshot accessibility service without Gradle.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_SDK:-$HOME/android-sdk}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
JAR="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar"
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex"

"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -o "$OUT/unsigned.apk" "$OUT/res.zip"
javac -source 11 -target 11 -Xlint:-options -cp "$JAR" -d "$OUT/classes" $(find src -name '*.java')
"$BT/d8" --min-api 30 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -q -j ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
# Signing config stays outside the repo: copy signing.env.example to signing.env.
[ -f signing.env ] || { echo "signing.env missing: cp signing.env.example signing.env and fill it in" >&2; exit 1; }
# shellcheck source=/dev/null
. ./signing.env
: "${KEYSTORE:?set KEYSTORE in signing.env}" "${KEY_ALIAS:?set KEY_ALIAS in signing.env}" "${KS_PASS:?set KS_PASS in signing.env}"
export KS_PASS KEY_PASS="${KEY_PASS:-$KS_PASS}"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass env:KS_PASS \
    --ks-key-alias "$KEY_ALIAS" --key-pass env:KEY_PASS \
    --out "$OUT/TvEkran.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/TvEkran.apk"
echo "APK: $PWD/$OUT/TvEkran.apk"
