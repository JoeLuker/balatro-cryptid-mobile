#!/usr/bin/env bash
set -euo pipefail

# Headless emulator capture of the REBUILD app's run HUD (systems.balatro.rebuild).
# Distinct from run.sh, which boots the LÖVE build (systems.shorty.lmm). This one
# installs the Compose rebuild APK and uses the --ez run true deep-link to land
# directly in RunScreen, then screencaps it — deterministic visual verification of
# the interpreter-driven HUD with NO keyguard and NO foldable rotation ambiguity
# (the emulator is a single 1080x2092 display we fully control).
#
# SwiftShader (software GL) renders Compose layout/colour/sprites faithfully; it
# will NOT reproduce Mali shader-precision bugs — that remains phone territory.
#
# Usage: nix-shell test/emulator/shell.nix --run 'test/emulator/run-rebuild.sh [--keep]'
#   --keep  leave the emulator running afterwards (default: leave running, since
#           iterating on UI wants a warm emulator; pass --kill to shut it down).
#
# EVERY adb call is scoped with -s "$SERIAL": Joe's phone is usually connected on
# this machine and must NEVER be touched by this script.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
APK="$PROJECT_DIR/rebuild/app/build/outputs/apk/debug/app-debug.apk"
OUT="$PROJECT_DIR/build/emulator"
PKG="systems.balatro.rebuild"
ACTIVITY="$PKG/systems.balatro.ui.MainActivity"
AVD_NAME="balatro-test"
PORT=5560
SERIAL="emulator-$PORT"
MODE="${1:-}"

[[ -f "$APK" ]] || { echo "[emu] $APK missing — build the rebuild APK first" >&2; exit 2; }
command -v emulator >/dev/null || { echo "[emu] emulator not on PATH — run inside nix-shell test/emulator/shell.nix" >&2; exit 2; }

mkdir -p "$OUT"
export ANDROID_AVD_HOME="$OUT/avd"
export ANDROID_USER_HOME="$OUT/android-user"
mkdir -p "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME"

step() { echo "[emu] $*"; }

# ---------------------------------------------------------------- create AVD
if [[ ! -d "$ANDROID_AVD_HOME/$AVD_NAME.avd" ]]; then
    step "creating AVD $AVD_NAME ($BALATRO_EMU_SYSIMAGE)"
    echo no | avdmanager create avd -n "$AVD_NAME" -k "$BALATRO_EMU_SYSIMAGE" --force >/dev/null
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
        >"$OUT/emulator-rebuild.log" 2>&1 &
    echo $! > "$OUT/emulator.pid"
fi

step "waiting for device..."
adb -s "$SERIAL" wait-for-device
for i in $(seq 1 120); do
    boot="$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    [[ "$boot" == "1" ]] && break
    sleep 3
    [[ $i == 120 ]] && { echo "[emu] boot timed out — see $OUT/emulator-rebuild.log" >&2; exit 1; }
done
step "booted: $(adb -s "$SERIAL" shell getprop ro.build.fingerprint | tr -d '\r')"

# keep the screen on + unlocked for the capture (emulator has no real keyguard)
adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb -s "$SERIAL" shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb -s "$SERIAL" shell settings put system screen_off_timeout 600000 >/dev/null 2>&1 || true

# ------------------------------------------------------------------ install
step "installing rebuild APK..."
adb -s "$SERIAL" install -r "$APK"

# ------------------------------------------------------------------- launch
step "launching run HUD via deep-link (--ez run true)..."
adb -s "$SERIAL" shell am force-stop "$PKG" >/dev/null 2>&1 || true
adb -s "$SERIAL" logcat -c || true
adb -s "$SERIAL" shell am start -n "$ACTIVITY" --ez run true >/dev/null

# give Compose its first frame + the off-thread boot work (oracle self-check + board)
for i in $(seq 1 20); do
    sleep 2
    adb -s "$SERIAL" shell pidof "$PKG" >/dev/null 2>&1 || { echo "[emu] FAIL: $PKG died after launch" >&2; adb -s "$SERIAL" logcat -d | grep -aiE "FATAL|AndroidRuntime" | head -10 >&2; exit 1; }
    foc="$(adb -s "$SERIAL" shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | tr -d '\r')"
    step "t=$((i*2))s focus=$foc"
    [[ "$foc" == *"$PKG"* ]] && (( i >= 3 )) && break
done

# ------------------------------------------------------------------ capture
step "capturing..."
adb -s "$SERIAL" shell screencap -p /data/local/tmp/rebuild-hud.png >/dev/null
adb -s "$SERIAL" pull /data/local/tmp/rebuild-hud.png "$OUT/rebuild-hud.png" >/dev/null
step "screenshot: $OUT/rebuild-hud.png"

adb -s "$SERIAL" logcat -d > "$OUT/logcat-rebuild.txt" 2>/dev/null || true
if grep -aiE "FATAL EXCEPTION|AndroidRuntime.*$PKG" "$OUT/logcat-rebuild.txt" | head -3 | grep -q .; then
    echo "[emu] WARN: crash markers in logcat:" >&2
    grep -aiE "FATAL EXCEPTION" "$OUT/logcat-rebuild.txt" | head -5 >&2
fi

# ------------------------------------------------------------------ cleanup
if [[ "$MODE" == "--kill" ]]; then
    step "shutting emulator down"
    adb -s "$SERIAL" emu kill >/dev/null 2>&1 || true
else
    step "emulator left running at $SERIAL (pass --kill to shut down)"
fi
echo "[emu] DONE — $OUT/rebuild-hud.png"
