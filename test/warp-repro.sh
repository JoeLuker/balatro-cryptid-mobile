#!/usr/bin/env bash
set -euo pipefail

# Warp-artifact repro: boot the BUILT game (build/game) on desktop with a save
# pulled from the phone, auto-continue the run, and dump joker transforms +
# move bookkeeping + a screenshot. Companion to test/smoke.sh (same staging
# and OS-spoof tricks — see comments there).
#
# Usage: test/warp-repro.sh <save-dir>
#   <save-dir> = directory containing M1/ and settings.jkr (e.g. the phone's
#   files/save/game pulled via run-as tar)
# Output: build/warp-repro/{warp.log,warp.png}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
GAME_DIR="$PROJECT_DIR/build/game"
OUT_DIR="$PROJECT_DIR/build/warp-repro"
SAVE_SRC="${1:?usage: test/warp-repro.sh <save-dir with M1/ and settings.jkr>}"

[[ -f "$GAME_DIR/main.lua" ]] || { echo "[warp] build/game missing — run just build first" >&2; exit 2; }
[[ -d "$SAVE_SRC/M1" ]] || { echo "[warp] $SAVE_SRC has no M1/ profile dir" >&2; exit 2; }
for tool in love xvfb-run; do
    command -v "$tool" >/dev/null || { echo "[warp] $tool not on PATH — run inside nix-shell" >&2; exit 2; }
done

WORK="$(mktemp -d /tmp/balatro-warp-XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
echo "[warp] staging game copy in $WORK"
cp -r "$GAME_DIR" "$WORK/game"

{
    echo "-- WARP: force Android code paths on desktop (test/warp-repro.sh)"
    echo "if os.getenv('BALATRO_FAKE_ANDROID') == '1' then love.system.getOS = function() return 'Android' end end"
    cat "$WORK/game/main.lua"
    echo ""
    echo "do local ok, err = pcall(require, 'warp-repro-autorun') if not ok then print('WARP: autorun-load-failed: '..tostring(err)) end end"
} > "$WORK/game/main.lua.injected"
mv "$WORK/game/main.lua.injected" "$WORK/game/main.lua"
# optional $2: alternate autorun script (always staged under the same name)
cp "${2:-$SCRIPT_DIR/warp-repro-autorun.lua}" "$WORK/game/warp-repro-autorun.lua"

# Pre-seed the love save dir with the phone save (identity dir is 'game')
mkdir -p "$WORK/xdg/love/game"
cp -r "$SAVE_SRC"/. "$WORK/xdg/love/game/"

LOG="$WORK/love.log"
echo "[warp] booting under Xvfb (software GL, dummy audio)..."
set +e
XDG_DATA_HOME="$WORK/xdg" \
SDL_AUDIODRIVER=dummy \
LIBGL_ALWAYS_SOFTWARE=1 \
BALATRO_FAKE_ANDROID=1 \
timeout 240 xvfb-run -a -s "-screen 0 ${WARP_SCREEN:-1600x900}x24" love "$WORK/game" >"$LOG" 2>&1
rc=$?
set -e

grep -a "^WARP:" "$LOG" || true

mkdir -p "$OUT_DIR"
cp "$LOG" "$OUT_DIR/warp.log"
savedir="$(grep -a '^WARP: savedir=' "$LOG" | head -1 | cut -d= -f2- || true)"
if [[ -n "${savedir:-}" && -f "$savedir/warp.png" ]]; then
    cp "$savedir/warp.png" "$OUT_DIR/warp.png"
    echo "[warp] screenshot: $OUT_DIR/warp.png"
fi

if grep -aq "^WARP: PASS" "$LOG"; then
    echo "[warp] PASS (log: $OUT_DIR/warp.log)"
    exit 0
fi
echo "[warp] FAIL rc=$rc — last 40 log lines:" >&2
tail -40 "$LOG" >&2
exit 1
