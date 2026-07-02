#!/usr/bin/env bash
set -euo pipefail

# Headless Android-emulator test of the BUILT APK: boots an x86_64 AVD (with
# ARM->x86 translation for our arm64 libs), installs build/apk/*.apk, arms
# the baked-in emulator smoke-check (patches/emulator-smoke-check.lua - a
# battery of specific regression assertions, e.g. "is the sleeve RunSelect
# page actually registered and localized", dormant on every real
# device/player build), launches GameActivity, asserts the process survives,
# logcat is crash-free, and the smoke-check's own assertions all pass.
#
# This tests what the desktop smoke (test/smoke.sh) cannot: the APK itself —
# apktool repack, signing, manifest, the love-android runtime booting our
# game.love — plus specific in-game regressions, without a human doing
# manual on-device diagnosis for each one. It does NOT test Mali GPU
# behaviour (SwiftShader rendering) or touch feel.
#
# Usage: nix-shell test/emulator/shell.nix --run 'test/emulator/run.sh [--keep]'
#   --keep  leave the emulator running afterwards (reattach with
#           adb -s emulator-5560 ...)
#
# EVERY adb call is scoped with -s "$SERIAL": the phone is often connected on
# this machine and must never be touched by this script.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
APK="$PROJECT_DIR/build/apk/com.unofficial.balatro.cryptid.apk"
OUT="$PROJECT_DIR/build/emulator"
PKG="systems.shorty.lmm"
AVD_NAME="balatro-test"
PORT=5560                       # fixed, away from the default 5554
SERIAL="emulator-$PORT"
KEEP="${1:-}"

[[ -f "$APK" ]] || { echo "[emu] $APK missing — run ./scripts/build.sh build first" >&2; exit 2; }
command -v emulator >/dev/null || { echo "[emu] emulator not on PATH — run inside nix-shell test/emulator/shell.nix" >&2; exit 2; }

mkdir -p "$OUT"
# Keep all AVD/adb state inside the repo's build dir, not ~
export ANDROID_AVD_HOME="$OUT/avd"
export ANDROID_USER_HOME="$OUT/android-user"
mkdir -p "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME"

step() { echo "[emu] $*"; }

# ---------------------------------------------------------------- create AVD
if [[ ! -d "$ANDROID_AVD_HOME/$AVD_NAME.avd" ]]; then
    step "creating AVD $AVD_NAME ($BALATRO_EMU_SYSIMAGE)"
    echo no | avdmanager create avd -n "$AVD_NAME" -k "$BALATRO_EMU_SYSIMAGE" --force >/dev/null
    # phone-ish screen, generous resources
    cat >> "$ANDROID_AVD_HOME/$AVD_NAME.avd/config.ini" <<EOF
hw.lcd.width=1080
hw.lcd.height=2092
hw.lcd.density=420
hw.ramSize=4096
disk.dataPartition.size=6G
EOF
fi

# -------------------------------------------------------------------- boot
if ! adb -s "$SERIAL" get-state >/dev/null 2>&1; then
    step "booting emulator headless (KVM + SwiftShader)..."
    nohup emulator -avd "$AVD_NAME" -port "$PORT" \
        -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect \
        -memory 4096 -cores 4 -no-snapshot \
        >"$OUT/emulator.log" 2>&1 &
    echo $! > "$OUT/emulator.pid"
fi

step "waiting for device..."
adb -s "$SERIAL" wait-for-device
for i in $(seq 1 120); do
    boot="$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    [[ "$boot" == "1" ]] && break
    sleep 3
    [[ $i == 120 ]] && { echo "[emu] boot timed out — see $OUT/emulator.log" >&2; exit 1; }
done
step "booted: $(adb -s "$SERIAL" shell getprop ro.build.fingerprint | tr -d '\r')"
step "ARM translation: $(adb -s "$SERIAL" shell getprop ro.dalvik.vm.isa.arm64 | tr -d '\r')"

# ------------------------------------------------------------------ install
step "installing APK..."
# Drop any prior install first: a stale package signed with a different keystore
# (e.g. a previous build's) rejects -r with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
adb -s "$SERIAL" uninstall "$PKG" >/dev/null 2>&1 || true
adb -s "$SERIAL" install -r "$APK"

# MODS_BAKED_IN_ONLY (2026-07-01): mod code lives ONLY in the APK's embedded
# game.love now (see scripts/build.sh's deploy() and the commit that fixed
# it) - a save-dir Mods/ push here would be exactly the same code-shadowing
# hazard that cost a whole session chasing "why doesn't my patch show up"
# on the phone. Nothing to push: this is always a fresh uninstall+install,
# so there's no stale save-dir content to preserve either.
step "arming emulator smoke-check..."
# love.filesystem's own save identity is files/save/game/ (where
# telemetry.log, settings.jkr etc. already live) - NOT files/save/ directly
# (that's the separate Mods/ overlay path lovely.lua points mod_dir at).
adb -s "$SERIAL" shell "run-as $PKG mkdir -p files/save/game"
adb -s "$SERIAL" shell "run-as $PKG sh -c 'touch files/save/game/EMULATOR_SMOKE_TEST'"
adb -s "$SERIAL" shell "run-as $PKG sh -c 'rm -f files/save/game/emulator_smoke_results.txt'"

# ------------------------------------------------------------------- launch
step "launching..."
adb -s "$SERIAL" logcat -c || true
adb -s "$SERIAL" shell am start -n "$PKG/org.love2d.android.GameActivity" >/dev/null

# Verdict model (LÖVE writes NOTHING to emulator logcat — verified — and its
# crash screen keeps the process alive, so pidof/logcat alone cannot judge):
#   - process dies               -> FAIL (hard crash)
#   - frame static for 30s AND flat-coloured -> FAIL (the "Oops!" crash screen
#     is the only fully-static screen; loading/menu always animate)
#   - frame with high colour diversity and no dominant colour -> PASS (menu)
#   - budget exhausted while still animating  -> FAIL (boot too slow / stuck)
# Boot under ARM->x86 translation is SLOW (LuaJIT can't JIT) — allow minutes.
BOOT_BUDGET="${EMU_BOOT_BUDGET:-420}"
POLL=30
pass=true
verdict=""
prev_sig=""
step "polling every ${POLL}s (budget ${BOOT_BUDGET}s)..."
elapsed=0
while (( elapsed < BOOT_BUDGET )); do
    sleep "$POLL"; elapsed=$(( elapsed + POLL ))
    if ! adb -s "$SERIAL" shell pidof "$PKG" >/dev/null 2>&1; then
        sleep 2
        adb -s "$SERIAL" shell pidof "$PKG" >/dev/null 2>&1 || { verdict="FAIL process died after ~${elapsed}s"; pass=false; break; }
    fi
    adb -s "$SERIAL" shell screencap -p /data/local/tmp/emu.png >/dev/null 2>&1 || continue
    adb -s "$SERIAL" pull /data/local/tmp/emu.png "$OUT/emu.png" >/dev/null 2>&1 || continue
    sig=$(magick "$OUT/emu.png" -resize 200x -depth 8 rgba:- 2>/dev/null | sha1sum | cut -d' ' -f1)
    dominant_pct=$(magick "$OUT/emu.png" -resize 200x -format %c histogram:info:- 2>/dev/null \
        | awk -F: '{gsub(/ /,"",$1); if ($1+0 > m) m = $1+0} END {print m+0}')
    uniq_colors=$(magick "$OUT/emu.png" -resize 200x -format %k info: 2>/dev/null)
    total_px=$(magick "$OUT/emu.png" -resize 200x -format "%[fx:w*h]" info: 2>/dev/null)
    share=$(( ${dominant_pct:-0} * 100 / ${total_px:-1} ))
    step "t=${elapsed}s dominant=${share}% colours=${uniq_colors:-?}"
    if [[ -n "$prev_sig" && "$sig" == "$prev_sig" ]]; then
        if (( share > 40 )); then
            verdict="FAIL static flat screen (crash screen signature) at ~${elapsed}s"; pass=false; break
        fi
    fi
    if (( share < 40 )) && (( ${uniq_colors:-0} > 2000 )); then
        verdict="PASS menu-like frame at ~${elapsed}s (dominant ${share}%, ${uniq_colors} colours)"; break
    fi
    prev_sig="$sig"
done
if [[ -z "$verdict" ]]; then
    verdict="FAIL no menu within ${BOOT_BUDGET}s (last frame: dominant ${share:-?}%)"; pass=false
fi
step "$verdict"

adb -s "$SERIAL" logcat -d > "$OUT/logcat.txt" 2>/dev/null
if grep -aiE "FATAL EXCEPTION|AndroidRuntime.*$PKG" "$OUT/logcat.txt" | head -3 | grep -q .; then
    echo "[emu] FAIL: crash markers in logcat:" >&2
    grep -aiE "FATAL EXCEPTION" "$OUT/logcat.txt" | head -5 >&2
    pass=false
fi
step "final screenshot: $OUT/emu.png"

# ---------------------------------------------------------- smoke-check
# emulator-smoke-check.lua writes its results ~5s after reaching the menu;
# the poll loop above already waited at least one 30s tick past that once it
# broke on a menu-like frame, so this should already exist - a short retry
# covers the case where the menu-verdict frame landed right at the 5s edge.
step "reading smoke-check results..."
smoke_ok=false
for _ in 1 2 3; do
    if adb -s "$SERIAL" shell "run-as $PKG cat files/save/game/emulator_smoke_results.txt" \
        > "$OUT/smoke_results.txt" 2>/dev/null && [[ -s "$OUT/smoke_results.txt" ]]; then
        smoke_ok=true
        break
    fi
    sleep 3
done
if $smoke_ok; then
    sed 's/^/[emu][smoke] /' "$OUT/smoke_results.txt"
    if ! grep -q "^OVERALL PASS" "$OUT/smoke_results.txt"; then
        echo "[emu] FAIL: smoke-check assertions failed (see $OUT/smoke_results.txt)" >&2
        pass=false
    fi
else
    echo "[emu] FAIL: emulator_smoke_results.txt never appeared (smoke-check didn't run or never reached menu)" >&2
    pass=false
fi

# ------------------------------------------------------------------ cleanup
if [[ "$KEEP" != "--keep" ]]; then
    step "shutting emulator down (pass --keep to leave it running)"
    adb -s "$SERIAL" emu kill >/dev/null 2>&1 || true
else
    step "emulator left running at $SERIAL"
fi

if $pass; then
    echo "[emu] PASS — APK installed, booted, reached menu at ~${elapsed:-?}s, no crash markers, smoke-check assertions all passed (log: $OUT/logcat.txt, smoke: $OUT/smoke_results.txt)"
    exit 0
fi
echo "[emu] FAIL (log: $OUT/logcat.txt, emulator log: $OUT/emulator.log)" >&2
exit 1
