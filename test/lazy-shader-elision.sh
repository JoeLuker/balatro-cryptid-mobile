#!/usr/bin/env bash
set -euo pipefail

# Lazy-shader elision regression: verifies that DRAW_SHADER_NIL_RESET +
# LAZY_SHADER together produce meaningful GPU shader-bind elision.
#
# The patch moved the setShader(nil) reset from inside draw_shader() to each
# call site (Card:draw, Blind:draw, stake closure), so LAZY_SHADER no longer
# sees the S -> nil -> S ping-pong between consecutive same-shader sprite
# draws.  This test boots build/game, navigates to SELECTING_HAND on a seeded
# run, samples LAZY_SHADER.binds vs .calls over 3 seconds, and asserts:
#
#   elision_rate = 1 - (binds / calls) >= 0.30
#
# Also asserts statically that engine/sprite.lua in the built tree contains
# the DRAW_SHADER_NIL_RESET sentinel (catches regen-dump without re-patching).
#
# Usage: test/lazy-shader-elision.sh   (inside nix-shell: needs love + xvfb-run)
# Exit:  0 pass or skip; nonzero fail. Log: build/lazy-shader-elision/elision.log

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
GAME_DIR="$PROJECT_DIR/build/game"
OUT_DIR="$PROJECT_DIR/build/lazy-shader-elision"

[[ -f "$GAME_DIR/main.lua" ]] || {
    echo "[elide] build/game missing — run just build first" >&2
    exit 2
}
for tool in love xvfb-run; do
    command -v "$tool" >/dev/null || {
        echo "[elide] $tool not on PATH — run inside nix-shell" >&2
        exit 2
    }
done

# Static pre-condition: DRAW_SHADER_NIL_RESET sentinel must be present in the
# built sprite.lua.  The autorun also checks this at runtime, but a static
# check here gives a faster, clearer failure message when regen-dump fires
# without re-applying the patch.
if ! grep -q "DRAW_SHADER_NIL_RESET" "$GAME_DIR/engine/sprite.lua" 2>/dev/null; then
    echo "[elide] FAIL DRAW_SHADER_NIL_RESET sentinel missing from" \
         "build/game/engine/sprite.lua" >&2
    echo "[elide] Re-run just build to apply" \
         "apply_draw_shader_nil_reset" >&2
    exit 1
fi
echo "[elide] static sentinel check OK"

# Static pre-condition: lazy-shader.lua must be present in the built tree.
if [[ ! -f "$GAME_DIR/lazy-shader.lua" ]]; then
    echo "[elide] SKIP lazy-shader.lua not present in build/game" \
         "(not embedded by build.sh — non-fatal)" >&2
    exit 0
fi

mkdir -p "$OUT_DIR"
WORK="$(mktemp -d /tmp/balatro-elide-XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

echo "[elide] staging in $WORK"
cp -r "$GAME_DIR" "$WORK/game"

{
    echo "if os.getenv('BALATRO_FAKE_ANDROID') == '1' then love.system.getOS = function() return 'Android' end end"
    cat "$WORK/game/main.lua"
    echo ""
    echo "do local ok, err = pcall(require, 'lazy-shader-elision-autorun') if not ok then print('ELIDE: FAIL require-failed: '..tostring(err)) end end"
} > "$WORK/game/main.lua.injected"
mv "$WORK/game/main.lua.injected" "$WORK/game/main.lua"
cp "$SCRIPT_DIR/lazy-shader-elision-autorun.lua" \
   "$WORK/game/lazy-shader-elision-autorun.lua"

if [[ -d "$PROJECT_DIR/build/save-pull/game" ]]; then
    mkdir -p "$WORK/xdg/love"
    cp -r "$PROJECT_DIR/build/save-pull/game" "$WORK/xdg/love/game"
else
    mkdir -p "$WORK/xdg"
fi

LOG="$OUT_DIR/elision.log"
echo "[elide] booting under Xvfb..."
set +e
XDG_DATA_HOME="$WORK/xdg" \
SDL_AUDIODRIVER=dummy \
LIBGL_ALWAYS_SOFTWARE=1 \
BALATRO_FAKE_ANDROID=1 \
timeout 180 xvfb-run -a -s "-screen 0 1600x900x24" love "$WORK/game" \
    >"$LOG" 2>&1
rc=$?
set -e

grep -a "^ELIDE:" "$LOG" || true

# SKIP is a non-failure exit.
if grep -aq "^ELIDE: SKIP" "$LOG"; then
    echo "[elide] SKIP (log: $OUT_DIR/elision.log)"
    exit 0
fi

if grep -aq "^ELIDE: PASS" "$LOG"; then
    echo "[elide] PASS (log: $OUT_DIR/elision.log)"
    exit 0
fi

echo "[elide] FAIL rc=$rc — last 30 log lines:" >&2
tail -30 "$LOG" >&2
exit 1
