#!/usr/bin/env bash
set -euo pipefail

# Page-cycle regression: cycling the PAGE option-cycle widget in Cryptid's
# per-item toggle screen must NOT close the overlay. Verifies the
# parent = toggle_area fix in cry_item_toggle_page.
#
# The autorun opens item_toggle_UI, fires cry_item_toggle_page, then asserts:
#   - G.OVERLAY_MENU is still alive (not REMOVED)
#   - cry_item_toggle_area UIE is still findable
#   - the replacement UIBox has role.major == toggle_area and
#     config.parent == toggle_area (not nil, not self)
#
# Usage: test/page-cycle.sh   (inside nix-shell: needs love + xvfb-run)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
GAME_DIR="$PROJECT_DIR/build/game"
OUT_DIR="$PROJECT_DIR/build/page-cycle-test"

[[ -f "$GAME_DIR/main.lua" ]] || { echo "[pcycle] build/game missing — run ./scripts/build.sh build first" >&2; exit 2; }
for tool in love xvfb-run; do
    command -v "$tool" >/dev/null || { echo "[pcycle] $tool not on PATH — run inside nix-shell" >&2; exit 2; }
done

grep -q "cry_item_toggle_page" "$GAME_DIR/Mods/Cryptid/lib/gameset.lua" \
    || { echo "[pcycle] cry_item_toggle_page not found in build tree" >&2; exit 2; }

mkdir -p "$OUT_DIR"
WORK="$(mktemp -d /tmp/balatro-pcycle-XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

echo "[pcycle] staging in $WORK"
cp -r "$GAME_DIR" "$WORK/game"

{
    echo "if os.getenv('BALATRO_FAKE_ANDROID') == '1' then love.system.getOS = function() return 'Android' end end"
    cat "$WORK/game/main.lua"
    echo ""
    echo "do local ok, err = pcall(require, 'page-cycle-autorun') if not ok then print('PCYCLE: ERROR require-failed: '..tostring(err)) end end"
} > "$WORK/game/main.lua.injected"
mv "$WORK/game/main.lua.injected" "$WORK/game/main.lua"
cp "$SCRIPT_DIR/page-cycle-autorun.lua" "$WORK/game/page-cycle-autorun.lua"

if [[ -d "$PROJECT_DIR/build/save-pull/game" ]]; then
    mkdir -p "$WORK/xdg/love"
    cp -r "$PROJECT_DIR/build/save-pull/game" "$WORK/xdg/love/game"
else
    mkdir -p "$WORK/xdg"
fi

LOG="$OUT_DIR/page-cycle.log"
echo "[pcycle] booting under Xvfb..."
set +e
XDG_DATA_HOME="$WORK/xdg" \
SDL_AUDIODRIVER=dummy \
LIBGL_ALWAYS_SOFTWARE=1 \
BALATRO_FAKE_ANDROID=1 \
timeout 120 xvfb-run -a -s "-screen 0 1600x900x24" love "$WORK/game" >"$LOG" 2>&1
rc=$?
set -e

grep -a "^PCYCLE:" "$LOG" || true

if ! grep -aq "^PCYCLE: PASS" "$LOG"; then
    echo "[pcycle] FAIL rc=$rc — last 20 log lines:" >&2
    tail -20 "$LOG" >&2
    exit 1
fi
echo "[pcycle] PASS (log: $OUT_DIR/page-cycle.log)"
