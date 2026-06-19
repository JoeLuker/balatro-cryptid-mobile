#!/usr/bin/env bash
set -euo pipefail

# Card-position oracle: boot build/game headless, start a fixed-seed run, select the Small Blind,
# and at SELECTING_HAND dump the REAL engine transforms of the hand/joker/play areas + their cards
# (G.<area>.T and each card's .T/.VT). Ground truth for the rebuild's CardArea align_cards port.
#
# Usage (inside nix-shell — needs love + xvfb-run):  test/cardpos.sh
# Exit 0 + prints the "CPS:" dump on success; nonzero on failure. Log: build/cardpos/cardpos.log

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
LOVE_FILE="$PROJECT_DIR/src/Balatro.love"     # VANILLA — no SMODS, so it boots standalone; align_cards is identical
OUT_DIR="$PROJECT_DIR/build/cardpos"

[[ -f "$LOVE_FILE" ]] || { echo "[cardpos] src/Balatro.love missing" >&2; exit 2; }
for tool in love xvfb-run unzip; do
    command -v "$tool" >/dev/null || { echo "[cardpos] $tool not on PATH — run inside nix-shell" >&2; exit 2; }
done

mkdir -p "$OUT_DIR"
WORK="$(mktemp -d /tmp/balatro-cardpos-XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/game"
( cd "$WORK/game" && unzip -q "$LOVE_FILE" )

{
    cat "$WORK/game/main.lua"
    echo ""
    echo "do local ok, err = pcall(require, 'cardpos-autorun') if not ok then print('CPS: FAIL autorun-load-failed: '..tostring(err)) end end"
} > "$WORK/game/main.lua.injected"
mv "$WORK/game/main.lua.injected" "$WORK/game/main.lua"
cp "$SCRIPT_DIR/cardpos-autorun.lua" "$WORK/game/cardpos-autorun.lua"
mkdir -p "$WORK/xdg"

LOG="$OUT_DIR/cardpos.log"
echo "[cardpos] booting vanilla src/Balatro.love under Xvfb (software GL, dummy audio)..."
set +e
XDG_DATA_HOME="$WORK/xdg" XDG_RUNTIME_DIR="$WORK/xdg" SDL_AUDIODRIVER=dummy LIBGL_ALWAYS_SOFTWARE=1 \
    timeout 300 xvfb-run -a -s "-screen 0 1600x900x24" love "$WORK/game" >"$LOG" 2>&1
rc=$?
set -e

grep -a "^CPS:" "$LOG" || true
if ! grep -aq "^CPS: PASS" "$LOG"; then
    echo "[cardpos] FAIL rc=$rc — last 30 log lines:" >&2
    tail -30 "$LOG" >&2
    exit 1
fi
echo "[cardpos] log: $LOG"
