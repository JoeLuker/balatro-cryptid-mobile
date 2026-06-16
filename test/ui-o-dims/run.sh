#!/usr/bin/env bash
set -euo pipefail

# UI_O_DETACHED regression: a UIT.O node whose config.object is detached
# (mid-teardown UI) must lay out 0x0 on recalculate — not write T.w/T.h=nil
# and crash move_wh a frame later (the fold-close field crash of 2026-06-12,
# dying words who=UIElement T(x,y,nil,nil)). The autorun detaches an object,
# recalculates like the resize handler does, and survives frames after.
#
# Usage: test/ui-o-dims/run.sh   (inside nix-shell: needs love + xvfb-run)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$(dirname "$SCRIPT_DIR")")"
GAME_DIR="$PROJECT_DIR/build/game"
OUT_DIR="$PROJECT_DIR/build/uio-test"

[[ -f "$GAME_DIR/main.lua" ]] || { echo "[uio] build/game missing — run ./scripts/build.sh build first" >&2; exit 2; }
for tool in love xvfb-run; do
    command -v "$tool" >/dev/null || { echo "[uio] $tool not on PATH — run inside nix-shell" >&2; exit 2; }
done

grep -q "UI_O_DETACHED" "$GAME_DIR/engine/ui.lua" \
    || { echo "[uio] UI_O_DETACHED not applied in build tree" >&2; exit 2; }

mkdir -p "$OUT_DIR"
WORK="$(mktemp -d /tmp/balatro-uio-XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

echo "[uio] staging in $WORK"
cp -r "$GAME_DIR" "$WORK/game"
{
    echo "if os.getenv('BALATRO_FAKE_ANDROID') == '1' then love.system.getOS = function() return 'Android' end end"
    cat "$WORK/game/main.lua"
    echo ""
    echo "do local ok, err = pcall(require, 'uio-autorun') if not ok then print('UIO: load-failed: '..tostring(err)) end end"
} > "$WORK/game/main.lua.injected"
mv "$WORK/game/main.lua.injected" "$WORK/game/main.lua"
cp "$SCRIPT_DIR/uio-autorun.lua" "$WORK/game/uio-autorun.lua"
mkdir -p "$WORK/xdg/love"
cp -r "$PROJECT_DIR/build/save-pull/game" "$WORK/xdg/love/game"

LOG="$OUT_DIR/uio.log"
set +e
XDG_DATA_HOME="$WORK/xdg" \
SDL_AUDIODRIVER=dummy \
LIBGL_ALWAYS_SOFTWARE=1 \
BALATRO_FAKE_ANDROID=1 \
timeout 90 xvfb-run -a -s "-screen 0 1600x900x24" love "$WORK/game" >"$LOG" 2>&1
rc=$?
set -e

grep -a "^UIO:" "$LOG" || true
if ! grep -aq "^UIO: PASS" "$LOG"; then
    echo "[uio] FAIL rc=$rc — last 15 log lines:" >&2
    tail -15 "$LOG" >&2
    exit 1
fi
echo "[uio] PASS (log: $LOG)"
