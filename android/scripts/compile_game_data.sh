#!/usr/bin/env bash
# Compila PBS + plugins de Tectonic de forma headless usando el binario Linux incluido en el repo.
# Genera Data/*.dat y Data/PluginScripts.rxdata (ignorados por git).
set -uo pipefail
cd "${1:-.}"

BIN="./Game Linux.x86_64"
chmod +x "$BIN"
export LD_LIBRARY_PATH="$PWD/lib64:${LD_LIBRARY_PATH:-}"
export ALSOFT_DRIVERS=null
export SDL_AUDIODRIVER=dummy
export LIBGL_ALWAYS_SOFTWARE=1
export MESA_GL_VERSION_OVERRIDE=3.3
export MESA_GLSL_VERSION_OVERRIDE=330

echo "--- ldd (missing libs) ---"
ldd "$BIN" | grep -i "not found" || echo "all shared libs resolved"
echo "---------------------------"

start=$(date +%s)
deadline=$((start + 1800))

xvfb-run -a -s "-screen 0 1280x960x24" "$BIN" debug compile > /tmp/compile.log 2>&1 &

while sleep 15; do
  now=$(date +%s)
  if ! pgrep -f "Game Linux.x86_64" > /dev/null; then
    echo "game process exited"
    break
  fi
  if [ -f Data/PluginScripts.rxdata ]; then
    n=$(find Data -maxdepth 1 -name '*.dat' -newermt "@$start" | wc -l)
    if [ "$n" -ge 3 ]; then
      last=$(find Data -maxdepth 1 -type f -printf '%T@\n' | sort -n | tail -1 | cut -d. -f1)
      if [ $((now - last)) -gt 60 ]; then
        echo "compile output stable ($n new .dat files)"
        break
      fi
    fi
  fi
  if [ "$now" -gt "$deadline" ]; then
    echo "timeout waiting for compile"
    break
  fi
done

pkill -f "Game Linux.x86_64" || true
sleep 2
pkill Xvfb || true

echo "--- game log (tail) ---"
tail -n 80 /tmp/compile.log || true
echo "-----------------------"

new_dat=$(find Data -maxdepth 1 -name '*.dat' -newermt "@$start" | wc -l)
if [ -f Data/PluginScripts.rxdata ] && [ "$new_dat" -ge 3 ]; then
  echo "Compiled game data OK ($new_dat .dat files generated)"
  exit 0
fi
echo "::warning::Headless compile did not produce data; the app will compile on first launch on-device."
exit 1
