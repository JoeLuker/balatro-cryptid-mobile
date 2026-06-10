#!/usr/bin/env bash
set -euo pipefail

# Telemetry gate test: the built game must emit and write NOTHING by default
# (the APK is shareable — Settings > Game toggles are off until flipped), and
# must emit + write once G.SETTINGS.telemetry_log is enabled, live, without a
# restart. Two boots of build/game under the same staging/OS-spoof tricks as
# test/smoke.sh (see comments there).
#
# Usage: test/telemetry-gate.sh        (inside nix-shell: needs love + xvfb-run)
# Exit:  0 pass; nonzero fail. Logs land at build/telemetry-gate/{off,on}.log.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
GAME_DIR="$PROJECT_DIR/build/game"
OUT_DIR="$PROJECT_DIR/build/telemetry-gate"

[[ -f "$GAME_DIR/main.lua" ]] || { echo "[telgate] build/game missing — run ./scripts/build.sh build first" >&2; exit 2; }
for tool in love xvfb-run; do
    command -v "$tool" >/dev/null || { echo "[telgate] $tool not on PATH — run inside nix-shell" >&2; exit 2; }
done

# Static preconditions on the built tree. The autorun sets G.SETTINGS directly,
# so without these the runtime checks could pass with the settings-menu wiring
# missing entirely; and the LONG DT assertion below is probabilistic (needs a
# slow frame), so the gate itself is verified statically here.
grep -q "ref_value = 'telemetry_log'" "$GAME_DIR/functions/UI_definitions.lua" \
    || { echo "[telgate] Debug Logging toggle missing from settings UI" >&2; exit 2; }
grep -q "ref_value = 'telemetry_home'" "$GAME_DIR/functions/UI_definitions.lua" \
    || { echo "[telgate] Phone Home toggle missing from settings UI" >&2; exit 2; }
grep -q "G.SETTINGS.telemetry_log and self.real_dt" "$GAME_DIR/game.lua" \
    || { echo "[telgate] LONG DT print not gated in build/game/game.lua" >&2; exit 2; }

# Coverage limit: BALATRO_FAKE_ANDROID disables the phone-home sender thread,
# so the telemetry_home gate is exercised only by the standalone mock test
# (see the gate-logic harness in git history / patches/android-telemetry.lua).
mkdir -p "$OUT_DIR"

run_mode() {
    local mode="$1"
    local WORK
    WORK="$(mktemp -d "/tmp/balatro-telgate-$mode-XXXXXX")"
    # shellcheck disable=SC2064
    trap "rm -rf '$WORK'" RETURN EXIT
    echo "[telgate] mode=$mode staging in $WORK"
    cp -r "$GAME_DIR" "$WORK/game"
    {
        echo "-- TELGATE: force Android code paths on desktop (test/telemetry-gate.sh)"
        echo "if os.getenv('BALATRO_FAKE_ANDROID') == '1' then love.system.getOS = function() return 'Android' end end"
        cat "$WORK/game/main.lua"
        echo ""
        echo "do local ok, err = pcall(require, 'telemetry-gate-autorun') if not ok then print('TELGATE: autorun-load-failed: '..tostring(err)) end end"
    } > "$WORK/game/main.lua.injected"
    mv "$WORK/game/main.lua.injected" "$WORK/game/main.lua"
    cp "$SCRIPT_DIR/telemetry-gate-autorun.lua" "$WORK/game/telemetry-gate-autorun.lua"
    mkdir -p "$WORK/xdg"

    local LOG="$OUT_DIR/$mode.log"
    set +e
    XDG_DATA_HOME="$WORK/xdg" \
    SDL_AUDIODRIVER=dummy \
    LIBGL_ALWAYS_SOFTWARE=1 \
    BALATRO_FAKE_ANDROID=1 \
    TELGATE_MODE="$mode" \
    timeout 240 xvfb-run -a -s "-screen 0 1600x900x24" love "$WORK/game" >"$LOG" 2>&1
    local rc=$?
    set -e

    grep -a "^TELGATE:" "$LOG" || true
    if ! grep -aq "^TELGATE: PASS" "$LOG"; then
        echo "[telgate] FAIL mode=$mode rc=$rc — last 30 log lines:" >&2
        tail -30 "$LOG" >&2
        return 1
    fi

    # shell-side stdout assertions: the in-game check covers the file sink,
    # these cover the logcat (print) sink and the gated vanilla LONG DT print
    if [[ "$mode" == "off" ]]; then
        if grep -aq "^\[TEL\]" "$LOG"; then
            echo "[telgate] FAIL mode=off: [TEL] output leaked to stdout" >&2
            grep -a "^\[TEL\]" "$LOG" | head -5 >&2
            return 1
        fi
        if grep -aq "^LONG DT" "$LOG"; then
            echo "[telgate] FAIL mode=off: LONG DT print not gated" >&2
            return 1
        fi
    else
        if ! grep -aq "^\[TEL\]" "$LOG"; then
            echo "[telgate] FAIL mode=on: no [TEL] output on stdout despite telemetry_log=true" >&2
            return 1
        fi
    fi
    echo "[telgate] PASS mode=$mode"
}

run_mode off
run_mode on
echo "[telgate] PASS (logs: $OUT_DIR/{off,on}.log)"
