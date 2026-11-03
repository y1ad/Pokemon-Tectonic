#!/usr/bin/env bash
# Empaqueta los archivos del juego necesarios en Android (sin binarios de Windows/Linux).
# El primer entry es .game_version, que usa el launcher para saber si debe re-extraer.
set -euo pipefail
SRC="${1:?game dir}"
OUT="${2:?output zip}"
case "$OUT" in /*) ;; *) OUT="$PWD/$OUT" ;; esac

cd "$SRC"
rm -f "$OUT"

TMP=$(mktemp -d)
printf '%s' "${GAME_VERSION:-${GITHUB_SHA:-dev}}" > "$TMP/.game_version"
(cd "$TMP" && zip -q -0 "$OUT" .game_version)

ITEMS=()
for p in Audio Data Fonts Graphics PBS Plugins "Project Chasm.ini" mkxp.json soundfont.sf2 release_version.txt knownpoint.bmp selpoint.bmp; do
  if [ -e "$p" ]; then ITEMS+=("$p"); else echo "note: $p not found, skipping"; fi
done

zip -q -r -1 "$OUT" "${ITEMS[@]}" \
  -n .ogg:.mp3:.png:.jpg:.jpeg:.gif:.sf2:.zip \
  -x '*.bak' -x '*Thumbs.db' -x '*.DS_Store' -x 'Data/debuglog.txt'

ls -lh "$OUT"
unzip -l "$OUT" | tail -n 1
