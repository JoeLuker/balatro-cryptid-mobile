#!/usr/bin/env bash
set -euo pipefail

# NUGC v2 regression: heap >200MB + breath-state entry must trigger an
# opportunistic full collect (debounced 30s), keeping the 300MB mid-hand
# emergency ceiling unreachable. The autorun balloons the heap, drives an
# inert 999 -> MENU state transition, and asserts collect-then-debounce.
#
# Usage: test/nugc/run.sh   (inside nix-shell: needs love + xvfb-run)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$(dirname "$SCRIPT_DIR")")"
GAME_DIR="$PROJECT_DIR/build/game"
OUT_DIR="$PROJECT_DIR/build/nugc-test"

[[ -f "$GAME_DIR/main.lua" ]] || { echo "[ngc] build/game missing — run ./scripts/build.sh build first" >&2; exit 2; }
for tool in love xvfb-run; do
    command -v "$tool" >/dev/null || { echo "[ngc] $tool not on PATH — run inside nix-shell" >&2; exit 2; }
done

grep -q "NUGC_ST" "$GAME_DIR/functions/misc_functions.lua" \
    || { echo "[ngc] NUGC v2 not applied in build tree" >&2; exit 2; }

mkdir -p "$OUT_DIR"
WORK="$(mktemp -d /tmp/balatro-ngc-XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

echo "[ngc] staging in $WORK"
cp -r "$GAME_DIR" "$WORK/game"
{
    echo "if os.getenv('BALATRO_FAKE_ANDROID') == '1' then love.system.getOS = function() return 'Android' end end"
    cat "$WORK/game/main.lua"
    echo ""
    echo "do local ok, err = pcall(require, 'nugc-autorun') if not ok then print('NGC: load-failed: '..tostring(err)) end end"
} > "$WORK/game/main.lua.injected"
mv "$WORK/game/main.lua.injected" "$WORK/game/main.lua"
cp "$SCRIPT_DIR/nugc-autorun.lua" "$WORK/game/nugc-autorun.lua"
mkdir -p "$WORK/xdg/love"
cp -r "$PROJECT_DIR/build/save-pull/game" "$WORK/xdg/love/game"

LOG="$OUT_DIR/nugc.log"
set +e
XDG_DATA_HOME="$WORK/xdg" \
SDL_AUDIODRIVER=dummy \
LIBGL_ALWAYS_SOFTWARE=1 \
BALATRO_FAKE_ANDROID=1 \
timeout 120 xvfb-run -a -s "-screen 0 1600x900x24" love "$WORK/game" >"$LOG" 2>&1
rc=$?
set -e

grep -a "^NGC:" "$LOG" || true
if ! grep -aq "^NGC: PASS" "$LOG"; then
    echo "[ngc] FAIL rc=$rc — last 15 log lines:" >&2
    tail -15 "$LOG" >&2
    exit 1
fi
echo "[ngc] PASS (log: $LOG)"
