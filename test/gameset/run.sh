#!/usr/bin/env bash
set -euo pipefail

# CRY_VANILLA_GAMESET test: two boots of build/game (same staging tricks as
# test/telemetry-gate.sh) — vanilla mode asserts all Cryptid content gates
# off through the existing disabled machinery; mainline mode is the
# regression guard that cry content still loads and plays.
#
# Usage: test/gameset/run.sh   (inside nix-shell: needs love + xvfb-run)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$(dirname "$SCRIPT_DIR")")"
GAME_DIR="$PROJECT_DIR/build/game"
OUT_DIR="$PROJECT_DIR/build/gameset-test"

[[ -f "$GAME_DIR/main.lua" ]] || { echo "[gset] build/game missing — run ./scripts/build.sh build first" >&2; exit 2; }
for tool in love xvfb-run; do
    command -v "$tool" >/dev/null || { echo "[gset] $tool not on PATH — run inside nix-shell" >&2; exit 2; }
done

grep -q "CRY_VANILLA_GAMESET" "$GAME_DIR/Mods/Cryptid/lib/gameset.lua" \
    || { echo "[gset] CRY_VANILLA_GAMESET not applied in build tree" >&2; exit 2; }

mkdir -p "$OUT_DIR"

run_mode() {
    local mode="$1"
    local WORK
    WORK="$(mktemp -d "/tmp/balatro-gset-$mode-XXXXXX")"
    # shellcheck disable=SC2064
    trap "rm -rf '$WORK'" RETURN EXIT
    echo "[gset] mode=$mode staging in $WORK"
    cp -r "$GAME_DIR" "$WORK/game"
    {
        echo "-- GSET: force Android code paths on desktop (test/gameset/run.sh)"
        echo "if os.getenv('BALATRO_FAKE_ANDROID') == '1' then love.system.getOS = function() return 'Android' end end"
        cat "$WORK/game/main.lua"
        echo ""
        echo "do local ok, err = pcall(require, 'vanilla-autorun') if not ok then print('GSET: autorun-load-failed: '..tostring(err)) end end"
    } > "$WORK/game/main.lua.injected"
    mv "$WORK/game/main.lua.injected" "$WORK/game/main.lua"
    cp "$SCRIPT_DIR/vanilla-autorun.lua" "$WORK/game/vanilla-autorun.lua"
    mkdir -p "$WORK/xdg"

    local LOG="$OUT_DIR/$mode.log"
    set +e
    XDG_DATA_HOME="$WORK/xdg" \
    SDL_AUDIODRIVER=dummy \
    LIBGL_ALWAYS_SOFTWARE=1 \
    BALATRO_FAKE_ANDROID=1 \
    GAMESET_MODE="$mode" \
    timeout 240 xvfb-run -a -s "-screen 0 1600x900x24" love "$WORK/game" >"$LOG" 2>&1
    local rc=$?
    set -e

    grep -a "^GSET:" "$LOG" || true
    if ! grep -aq "^GSET: PASS" "$LOG"; then
        echo "[gset] FAIL mode=$mode rc=$rc — last 30 log lines:" >&2
        tail -30 "$LOG" >&2
        return 1
    fi
    echo "[gset] PASS mode=$mode"
}

run_mode vanilla
run_mode mainline
echo "[gset] PASS (logs: $OUT_DIR/{vanilla,mainline}.log)"
