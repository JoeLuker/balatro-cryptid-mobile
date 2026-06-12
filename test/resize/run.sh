#!/usr/bin/env bash
set -euo pipefail

# ANDROID_RESIZE_CONTAIN test: boot build/game with Android paths faked and
# drive love.resize through the foldable's surface geometries (inner/cover x
# landscape/portrait, half- and third-splits), asserting contain invariants
# and idempotence. Same staging tricks as test/telemetry-gate.sh.
#
# Usage: test/resize/run.sh   (inside nix-shell: needs love + xvfb-run)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$(dirname "$SCRIPT_DIR")")"
GAME_DIR="$PROJECT_DIR/build/game"
OUT_DIR="$PROJECT_DIR/build/resize-test"

[[ -f "$GAME_DIR/main.lua" ]] || { echo "[rsz] build/game missing — run ./scripts/build.sh build first" >&2; exit 2; }
for tool in love xvfb-run; do
    command -v "$tool" >/dev/null || { echo "[rsz] $tool not on PATH — run inside nix-shell" >&2; exit 2; }
done

grep -q "ANDROID_RESIZE_CONTAIN" "$GAME_DIR/main.lua" \
    || { echo "[rsz] ANDROID_RESIZE_CONTAIN not applied in build tree" >&2; exit 2; }

mkdir -p "$OUT_DIR"
WORK="$(mktemp -d /tmp/balatro-rsz-XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

echo "[rsz] staging in $WORK"
cp -r "$GAME_DIR" "$WORK/game"
{
    echo "-- RSZ: force Android code paths on desktop (test/resize/run.sh)"
    echo "if os.getenv('BALATRO_FAKE_ANDROID') == '1' then love.system.getOS = function() return 'Android' end end"
    cat "$WORK/game/main.lua"
    echo ""
    echo "do local ok, err = pcall(require, 'resize-autorun') if not ok then print('RSZ: autorun-load-failed: '..tostring(err)) end end"
} > "$WORK/game/main.lua.injected"
mv "$WORK/game/main.lua.injected" "$WORK/game/main.lua"
cp "$SCRIPT_DIR/resize-autorun.lua" "$WORK/game/resize-autorun.lua"
mkdir -p "$WORK/xdg"

LOG="$OUT_DIR/resize.log"
set +e
XDG_DATA_HOME="$WORK/xdg" \
SDL_AUDIODRIVER=dummy \
LIBGL_ALWAYS_SOFTWARE=1 \
BALATRO_FAKE_ANDROID=1 \
timeout 180 xvfb-run -a -s "-screen 0 2208x1840x24" love "$WORK/game" >"$LOG" 2>&1
rc=$?
set -e

grep -a "^RSZ:" "$LOG" || true
if ! grep -aq "^RSZ: PASS" "$LOG"; then
    echo "[rsz] FAIL rc=$rc — last 30 log lines:" >&2
    tail -30 "$LOG" >&2
    exit 1
fi
echo "[rsz] PASS (log: $LOG)"
