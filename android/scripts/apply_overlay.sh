#!/usr/bin/env bash
# Aplica el overlay de Tectonic sobre un checkout de mkxp-z-android-reworked.
# Uso: apply_overlay.sh <engine_dir>
set -euo pipefail
ENGINE="$(cd "${1:?engine dir}" && pwd)"
OVERLAY="$(cd "$(dirname "$0")/../overlay" && pwd)"

need() { grep -q -- "$1" "$2" || { echo "::error::patch target not found: '$1' in $2"; exit 1; }; }

# --- Java / recursos / gradle ---
cp "$OVERLAY"/java/com/hatkid/mkxpz/*.java "$ENGINE/app/src/main/java/com/hatkid/mkxpz/"
cp "$OVERLAY/AndroidManifest.xml"        "$ENGINE/app/src/main/AndroidManifest.xml"
cp "$OVERLAY/res/values/strings.xml"     "$ENGINE/app/src/main/res/values/strings.xml"
cp "$OVERLAY/app.build.gradle"           "$ENGINE/app/build.gradle"
cp "$OVERLAY/root.build.gradle"          "$ENGINE/build.gradle"
cp "$OVERLAY/gradle-wrapper.properties"  "$ENGINE/gradle/wrapper/gradle-wrapper.properties"

# --- Nativo: solo arm64 y optimizado (el original compila Ruby con -O0 y APP_OPTIM=debug) ---
APPMK="$ENGINE/app/jni/Application.mk"
need '^APP_ABI' "$APPMK"; need '^APP_OPTIM' "$APPMK"
sed -i 's/^APP_ABI := .*/APP_ABI := arm64-v8a/; s/^APP_OPTIM := .*/APP_OPTIM := release/' "$APPMK"

MKF="$ENGINE/app/jni/Makefile"
need '^CFLAGS *:= -O0' "$MKF"
sed -i 's/^\(CFLAGS *:= \)-O0/\1-O2/' "$MKF"
grep -q '^CFLAGS *:= -O2' "$MKF"

# --- Permisos: el juego vive en almacenamiento interno, no hace falta WRITE_EXTERNAL_STORAGE ---
MAIN="$ENGINE/app/jni/mkxp-z/src/main.cpp"
need 'if (sdkVersion < 30) {' "$MAIN"
sed -i 's/if (sdkVersion < 30) {/if (false) {/' "$MAIN"

echo "Overlay applied to $ENGINE"
