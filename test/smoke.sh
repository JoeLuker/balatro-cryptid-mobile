#!/usr/bin/env bash
set -euo pipefail

# Local smoke test: boot the BUILT game (build/game — the exact tree that ships
# inside the APK) on this machine instead of deploying to the phone.
#
# Fidelity trick: we prepend a love.system.getOS() spoof returning 'Android',
# so every Android-only code path (nativefs love.filesystem shim, hardcoded
# SMODS path, telemetry, Talisman config read-only branches) runs exactly as
# on device. What this CANNOT test: the Mali GPU (desktop renders via Mesa
# llvmpipe — fp32, no mediump), real touch input, and performance numbers.
#
# Usage: test/smoke.sh            (run inside nix-shell: needs love + xvfb-run)
# Exit:  0 pass; nonzero fail. Screenshot lands at build/smoke/smoke.png.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
GAME_DIR="$PROJECT_DIR/build/game"
OUT_DIR="$PROJECT_DIR/build/smoke"

if [[ ! -f "$GAME_DIR/main.lua" ]]; then
    echo "[smoke] build/game missing — run ./scripts/build.sh build first" >&2
    exit 2
fi
for tool in love xvfb-run; do
    command -v "$tool" >/dev/null || { echo "[smoke] $tool not on PATH — run inside nix-shell" >&2; exit 2; }
done

WORK="$(mktemp -d /tmp/balatro-smoke-XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
echo "[smoke] staging game copy in $WORK"
cp -r "$GAME_DIR" "$WORK/game"

# Inject: OS spoof as the very first line (before any getOS call), autorun
# require as the very last (after every wrapper has installed).
{
    echo "-- SMOKE: force Android code paths on desktop (test/smoke.sh)"
    echo "if os.getenv('BALATRO_FAKE_ANDROID') == '1' then love.system.getOS = function() return 'Android' end end"
    cat "$WORK/game/main.lua"
    echo ""
    echo "do local ok, err = pcall(require, 'smoke-autorun') if not ok then print('SMOKE: autorun-load-failed: '..tostring(err)) end end"
} > "$WORK/game/main.lua.injected"
mv "$WORK/game/main.lua.injected" "$WORK/game/main.lua"
cp "$SCRIPT_DIR/smoke-autorun.lua" "$WORK/game/smoke-autorun.lua"

mkdir -p "$WORK/xdg"
LOG="$WORK/love.log"

echo "[smoke] booting under Xvfb (software GL, dummy audio)..."
set +e
XDG_DATA_HOME="$WORK/xdg" \
SDL_AUDIODRIVER=dummy \
LIBGL_ALWAYS_SOFTWARE=1 \
BALATRO_FAKE_ANDROID=1 \
timeout 180 xvfb-run -a -s "-screen 0 1600x900x24" love "$WORK/game" >"$LOG" 2>&1
rc=$?
set -e

grep -a "^SMOKE:" "$LOG" || true

mkdir -p "$OUT_DIR"
cp "$LOG" "$OUT_DIR/smoke.log"
savedir="$(grep -a '^SMOKE: savedir=' "$LOG" | head -1 | cut -d= -f2- || true)"
if [[ -n "${savedir:-}" && -f "$savedir/smoke.png" ]]; then
    cp "$savedir/smoke.png" "$OUT_DIR/smoke.png"
    echo "[smoke] screenshot: $OUT_DIR/smoke.png"
fi

if grep -aq "^SMOKE: PASS" "$LOG"; then
    echo "[smoke] PASS (log: $OUT_DIR/smoke.log)"
    exit 0
fi
echo "[smoke] FAIL rc=$rc — last 40 log lines:" >&2
tail -40 "$LOG" >&2
exit 1
