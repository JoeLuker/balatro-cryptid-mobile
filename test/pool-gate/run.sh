#!/usr/bin/env bash
set -euo pipefail

# CRY_DISABLED_POOL_GATE + FORCED_KEY_GUARD regression (field crash
# 2026-06-12: Spectral soul-roll forced gameset-disabled c_cry_gateway,
# create_card indexed the scrubbed center). Boots the pulled modest-gameset
# profile, starts a run, and asserts gate + fallback + a soulable storm.
#
# Usage: test/pool-gate/run.sh   (inside nix-shell: needs love + xvfb-run)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$(dirname "$SCRIPT_DIR")")"
GAME_DIR="$PROJECT_DIR/build/game"
SAVE_DIR="$PROJECT_DIR/build/save-pull/game"
OUT_DIR="$PROJECT_DIR/build/poolgate-test"

[[ -f "$GAME_DIR/main.lua" ]] || { echo "[pgt] build/game missing — run ./scripts/build.sh build first" >&2; exit 2; }
[[ -d "$SAVE_DIR" ]] || { echo "[pgt] build/save-pull/game missing (profile save needed)" >&2; exit 2; }
for tool in love xvfb-run; do
    command -v "$tool" >/dev/null || { echo "[pgt] $tool not on PATH — run inside nix-shell" >&2; exit 2; }
done

grep -q "FORCED_KEY_GUARD" "$GAME_DIR/functions/common_events.lua" \
    || { echo "[pgt] FORCED_KEY_GUARD not applied in build tree" >&2; exit 2; }
grep -q "CRY_DISABLED_POOL_GATE" "$GAME_DIR/Mods/Steamodded/src/utils.lua" \
    || { echo "[pgt] CRY_DISABLED_POOL_GATE not applied in build tree" >&2; exit 2; }

mkdir -p "$OUT_DIR"
WORK="$(mktemp -d /tmp/balatro-pgt-XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

echo "[pgt] staging in $WORK"
cp -r "$GAME_DIR" "$WORK/game"
{
    echo "if os.getenv('BALATRO_FAKE_ANDROID') == '1' then love.system.getOS = function() return 'Android' end end"
    cat "$WORK/game/main.lua"
    echo ""
    echo "do local ok, err = pcall(require, 'poolgate-autorun') if not ok then print('PGT: load-failed: '..tostring(err)) end end"
} > "$WORK/game/main.lua.injected"
mv "$WORK/game/main.lua.injected" "$WORK/game/main.lua"
cp "$SCRIPT_DIR/poolgate-autorun.lua" "$WORK/game/poolgate-autorun.lua"
mkdir -p "$WORK/xdg/love"
cp -r "$SAVE_DIR" "$WORK/xdg/love/game"

LOG="$OUT_DIR/poolgate.log"
set +e
XDG_DATA_HOME="$WORK/xdg" \
SDL_AUDIODRIVER=dummy \
LIBGL_ALWAYS_SOFTWARE=1 \
BALATRO_FAKE_ANDROID=1 \
timeout 180 xvfb-run -a -s "-screen 0 1600x900x24" love "$WORK/game" >"$LOG" 2>&1
rc=$?
set -e

grep -a "^PGT:" "$LOG" || true
if ! grep -aq "^PGT: PASS" "$LOG"; then
    echo "[pgt] FAIL rc=$rc — last 15 log lines:" >&2
    tail -15 "$LOG" >&2
    exit 1
fi
echo "[pgt] PASS (log: $LOG)"
