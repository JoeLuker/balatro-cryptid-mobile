#!/usr/bin/env bash
set -euo pipefail

# Allocation bench: boot build/game on desktop with a real save, continue the
# run, and measure heap-allocation pressure for the Tier-1 perf targets
# (selection churn, one scored hand, texture memory). Staging mirrors
# test/smoke.sh / test/warp-repro.sh.
#
# Usage: test/perf/alloc-bench.sh <save-dir with M1/ and settings.jkr>
# Output: build/alloc-bench/bench.log + BENCH: lines on stdout

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$(dirname "$SCRIPT_DIR")")"
GAME_DIR="$PROJECT_DIR/build/game"
OUT_DIR="$PROJECT_DIR/build/alloc-bench"
SAVE_SRC="${1:?usage: test/perf/alloc-bench.sh <save-dir with M1/ and settings.jkr>}"

[[ -f "$GAME_DIR/main.lua" ]] || { echo "[bench] build/game missing" >&2; exit 2; }
[[ -d "$SAVE_SRC/M1" ]] || { echo "[bench] $SAVE_SRC has no M1/" >&2; exit 2; }
for tool in love xvfb-run; do
    command -v "$tool" >/dev/null || { echo "[bench] $tool not on PATH — run inside nix-shell" >&2; exit 2; }
done

WORK="$(mktemp -d /tmp/balatro-bench-XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
cp -r "$GAME_DIR" "$WORK/game"

{
    echo "if os.getenv('BALATRO_FAKE_ANDROID') == '1' then love.system.getOS = function() return 'Android' end end"
    cat "$WORK/game/main.lua"
    echo ""
    echo "do local ok, err = pcall(require, 'alloc-bench-autorun') if not ok then print('BENCH: autorun-load-failed: '..tostring(err)) end end"
} > "$WORK/game/main.lua.injected"
mv "$WORK/game/main.lua.injected" "$WORK/game/main.lua"
cp "$SCRIPT_DIR/alloc-bench-autorun.lua" "$WORK/game/alloc-bench-autorun.lua"

mkdir -p "$WORK/xdg/love/game"
cp -r "$SAVE_SRC"/. "$WORK/xdg/love/game/"

LOG="$WORK/love.log"
set +e
XDG_DATA_HOME="$WORK/xdg" \
SDL_AUDIODRIVER=dummy \
LIBGL_ALWAYS_SOFTWARE=1 \
BALATRO_FAKE_ANDROID=1 \
timeout 240 xvfb-run -a -s "-screen 0 1600x900x24" love "$WORK/game" >"$LOG" 2>&1
rc=$?
set -e

mkdir -p "$OUT_DIR"
cp "$LOG" "$OUT_DIR/bench.log"
grep -a "^BENCH:" "$LOG" || true

grep -aq "^BENCH: PASS" "$LOG" && exit 0
echo "[bench] FAIL rc=$rc — last 20 log lines:" >&2
tail -20 "$LOG" >&2
exit 1
