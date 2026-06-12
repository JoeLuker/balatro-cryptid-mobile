#!/usr/bin/env bash
set -euo pipefail

# Trigger-collapse validation: the affine property suite (pure luajit vs
# Amulet's real cdata Big), then the in-game differential (same cascade
# scored with collapse off and on must produce identical totals, the ON
# hand must actually collapse, and mismatches must be zero).
#
# Usage: test/collapse/run.sh   (inside nix-shell for the in-game part)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$(dirname "$SCRIPT_DIR")")"
cd "$PROJECT_DIR"

echo "[tcd] affine property suite..."
LUA_PATH="mods/Amulet/?.lua;;" luajit test/collapse/affine-test.lua 20000 | tail -2

GAME_DIR="$PROJECT_DIR/build/game"
SAVE_DIR="$PROJECT_DIR/build/save-pull/game"
OUT_DIR="$PROJECT_DIR/build/collapse-test"
[[ -f "$GAME_DIR/main.lua" ]] || { echo "[tcd] build/game missing" >&2; exit 2; }
[[ -d "$SAVE_DIR" ]] || { echo "[tcd] build/save-pull/game missing (profile save needed)" >&2; exit 2; }
for tool in love xvfb-run; do
    command -v "$tool" >/dev/null || { echo "[tcd] $tool not on PATH — run inside nix-shell" >&2; exit 2; }
done
grep -q "Trigger-cascade collapsing" "$GAME_DIR/main.lua" \
    || { echo "[tcd] trigger-collapse not wired in build tree" >&2; exit 2; }

mkdir -p "$OUT_DIR"
WORK="$(mktemp -d /tmp/balatro-tcd-XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

echo "[tcd] staging in $WORK"
cp -r "$GAME_DIR" "$WORK/game"
{
    echo "if os.getenv('BALATRO_FAKE_ANDROID') == '1' then love.system.getOS = function() return 'Android' end end"
    cat "$WORK/game/main.lua"
    echo ""
    echo "do local ok, err = pcall(require, 'ingame-autorun') if not ok then print('TCD: autorun-load-failed: '..tostring(err)) end end"
} > "$WORK/game/main.lua.injected"
mv "$WORK/game/main.lua.injected" "$WORK/game/main.lua"
cp "$SCRIPT_DIR/ingame-autorun.lua" "$WORK/game/ingame-autorun.lua"
# seed the save dir with the pulled profile (tutorial-complete, no intro)
mkdir -p "$WORK/xdg/love"
cp -r "$SAVE_DIR" "$WORK/xdg/love/game"

LOG="$OUT_DIR/ingame.log"
set +e
XDG_DATA_HOME="$WORK/xdg" \
SDL_AUDIODRIVER=dummy \
LIBGL_ALWAYS_SOFTWARE=1 \
BALATRO_FAKE_ANDROID=1 \
timeout 300 xvfb-run -a -s "-screen 0 1600x900x24" love "$WORK/game" >"$LOG" 2>&1
rc=$?
set -e

grep -a "^TCD:" "$LOG" || true
if ! grep -aq "^TCD: PASS" "$LOG"; then
    echo "[tcd] FAIL rc=$rc — last 30 log lines:" >&2
    tail -30 "$LOG" >&2
    exit 1
fi
echo "[tcd] PASS (log: $LOG)"
