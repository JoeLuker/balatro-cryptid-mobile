#!/usr/bin/env bash
set -euo pipefail

# Pixel-reference capture: boot vanilla src/Balatro.love headless under Xvfb at 3840x2160, drive it to
# a known state, and screenshot the real Balatro+Cryptid frame — the ground-truth the Kotlin rebuild's
# pixel-diff gate (tools/uiref/deploy_diff.sh) compares against. The diff is element-wise (no resize),
# so the captured PNG MUST be 3840x2160 to match the emulator's `wm size 2160x3840` repro screencap.
#
# Usage:  test/ref.sh [autorun-module] [out.png] [shot-name]
#   autorun-module : lua module injected after main.lua (default: ref-autorun -> SELECTING_HAND)
#                    bref-autorun forces the bref_3 scoring state (the deploy_diff reference).
#   out.png        : where to copy the captured PNG (default: /tmp/ref.png)
#   shot-name      : filename the autorun encodes into the LÖVE save dir (default: ref.png)
# Needs love + xvfb-run + unzip (on ~/.nix-profile/bin or inside nix-shell). Log: build/ref/ref.log

AUTORUN="${1:-ref-autorun}"
OUT_PNG="${2:-/tmp/ref.png}"
SHOT="${3:-ref.png}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
LOVE_FILE="$PROJECT_DIR/src/Balatro.love"     # VANILLA — boots standalone (no SMODS), same HUD/board
OUT_DIR="$PROJECT_DIR/build/ref"

[[ -f "$LOVE_FILE" ]] || { echo "[ref] src/Balatro.love missing" >&2; exit 2; }
[[ -f "$SCRIPT_DIR/$AUTORUN.lua" ]] || { echo "[ref] $AUTORUN.lua missing" >&2; exit 2; }
for tool in love xvfb-run unzip; do
    command -v "$tool" >/dev/null || { echo "[ref] $tool not on PATH — run inside nix-shell" >&2; exit 2; }
done

mkdir -p "$OUT_DIR"
WORK="$(mktemp -d /tmp/balatro-ref-XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/game"
( cd "$WORK/game" && unzip -q "$LOVE_FILE" )

{
    cat "$WORK/game/main.lua"
    echo ""
    echo "do local ok, err = pcall(require, '$AUTORUN') if not ok then print('REF: FAIL autorun-load-failed: '..tostring(err)) end end"
} > "$WORK/game/main.lua.injected"
mv "$WORK/game/main.lua.injected" "$WORK/game/main.lua"
cp "$SCRIPT_DIR/$AUTORUN.lua" "$WORK/game/$AUTORUN.lua"
mkdir -p "$WORK/xdg"

LOG="$OUT_DIR/ref.log"
echo "[ref] booting vanilla src/Balatro.love under Xvfb 3840x2160 (software GL, dummy audio), autorun=$AUTORUN..."
set +e
XDG_DATA_HOME="$WORK/xdg" XDG_RUNTIME_DIR="$WORK/xdg" SDL_AUDIODRIVER=dummy LIBGL_ALWAYS_SOFTWARE=1 \
    timeout 300 xvfb-run -a -s "-screen 0 3840x2160x24" love "$WORK/game" >"$LOG" 2>&1
rc=$?
set -e

grep -a "^REF:" "$LOG" || true
if ! grep -aq "^REF: PASS" "$LOG"; then
    echo "[ref] FAIL rc=$rc — last 30 log lines:" >&2
    tail -30 "$LOG" >&2
    exit 1
fi

# The autorun encodes $SHOT into the LÖVE save dir (XDG_DATA_HOME/love/<identity>). Find + copy it out.
SHOT_PATH="$(find "$WORK/xdg" -name "$SHOT" -type f 2>/dev/null | head -1)"
[[ -n "$SHOT_PATH" ]] || { echo "[ref] PASS logged but $SHOT not found under save dir" >&2; exit 1; }
cp "$SHOT_PATH" "$OUT_PNG"
echo "[ref] captured $OUT_PNG ($(identify -format '%wx%h' "$OUT_PNG" 2>/dev/null || echo '?') ) — log: $LOG"
