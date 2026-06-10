# Balatro Cryptid Mobile - Build Commands
# Usage: just <command>
# Run 'just --list' to see all commands

set shell := ["bash", "-euc"]

# Default recipe - show help
default:
    @just --list

# Check that all required tools are available
check:
    ./scripts/build.sh check

# Fetch all sources (base.apk, Balatro.love, mods)
fetch:
    ./scripts/build.sh fetch

# Build the APK and prepare transfer files
build:
    ./scripts/build.sh build

# Deploy APK and mods to connected phone
deploy:
    ./scripts/build.sh deploy

# Full pipeline: fetch, build, deploy
all:
    ./scripts/build.sh all

# Watch app logs from connected phone
logs:
    ./scripts/build.sh logs

# Clean all build artifacts
clean:
    ./scripts/build.sh clean

# Quick rebuild and deploy (skip fetch)
quick:
    ./scripts/build.sh build
    ./scripts/build.sh deploy

# Local controller gesture tests — runs the REAL built controller.lua with
# scripted touch gestures (tap/hold/drag/slide) in <1s. No phone, no display.
test-controller:
    luajit test/controller/run.lua

# Local boot smoke test — boots build/game on THIS machine (Android code paths
# spoofed, software GL under Xvfb), asserts it reaches the main menu, saves a
# screenshot to build/smoke/smoke.png. No phone needed. ~1 min.
smoke:
    test/smoke.sh

# Headless Android-emulator test of the BUILT APK (KVM + ARM translation):
# installs build/apk/*.apk, mirrors the phone deploy, polls screenshots until
# a menu-like frame (PASS) or crash-screen signature (FAIL). ~5-10 min.
# All adb calls are serial-scoped — never touches a connected phone.
emu-test:
    nix-shell test/emulator/shell.nix --run 'test/emulator/run.sh'

# All local tests (run before deploying to the phone)
test: test-controller smoke

# Push only mod files (no APK reinstall)
push-mods:
    #!/usr/bin/env bash
    set -euo pipefail
    PACKAGE_ID="systems.shorty.lmm"
    TRANSFER_DIR="build/phone-transfer"
    TEMP_DIR="/data/local/tmp/balatro_mods"

    adb shell "rm -rf $TEMP_DIR" 2>/dev/null || true
    adb push "$TRANSFER_DIR" "$TEMP_DIR"
    adb shell "run-as $PACKAGE_ID rm -rf files/save/*" 2>/dev/null || true
    adb shell "run-as $PACKAGE_ID mkdir -p files/save"
    adb shell "run-as $PACKAGE_ID cp -r $TEMP_DIR/* files/save/"

    echo "Mods pushed. Restarting app..."
    adb shell am force-stop $PACKAGE_ID
    adb shell am start -n "$PACKAGE_ID/org.love2d.android.GameActivity"

# Force stop the app
stop:
    adb shell am force-stop systems.shorty.lmm

# Start the app
start:
    adb shell am start -n "systems.shorty.lmm/org.love2d.android.GameActivity"

# Restart the app — force-stop, wait for the process to actually die, then start.
# (Plain stop+start races: LÖVE aborts with "filesystem already initialized" if a
#  second instance starts before the first finishes tearing down. Closing the app
#  from the phone's recents does NOT kill it, so changes won't load — use this.)
restart:
    #!/usr/bin/env bash
    set -euo pipefail
    PKG="systems.shorty.lmm"
    adb shell am force-stop "$PKG"
    for i in $(seq 1 20); do
        adb shell pidof "$PKG" >/dev/null 2>&1 || break
        sleep 0.3
    done
    adb shell am start -n "$PKG/org.love2d.android.GameActivity"
    echo "Restarted $PKG"

# Show what's in the app's save directory
ls-save:
    adb shell "run-as systems.shorty.lmm ls -la files/save/"

# Show what mods are installed
ls-mods:
    adb shell "run-as systems.shorty.lmm ls -la files/save/Mods/"

# Pull logs to local file
save-logs:
    adb logcat -d > build/logcat.txt
    @echo "Logs saved to build/logcat.txt"

# Watch telemetry events (live)
tel:
    adb logcat -s SDL/APP:I | grep --line-buffered TEL

# Pull the persistent telemetry log from the device (no live observer needed —
# the app appends events + PERF_SNAPSHOT frame stats to telemetry.log in its
# save dir, flushed every 5s and on crash/background, rotated at 1MB)
perf-pull:
    @mkdir -p build/telemetry
    adb exec-out "run-as systems.shorty.lmm cat files/save/game/telemetry.log" > build/telemetry/telemetry.log 2>/dev/null || echo "no telemetry.log on device yet"
    -adb exec-out "run-as systems.shorty.lmm cat files/save/game/telemetry.log.1" > build/telemetry/telemetry.log.1 2>/dev/null
    @echo "pulled to build/telemetry/ — lines: $(wc -l < build/telemetry/telemetry.log)"

# Summarize pulled telemetry (fps/frame-time per state, crashes, session list)
perf-summary:
    @awk '$3=="PERF_SNAPSHOT" {for(i=4;i<=NF;i++){split($i,a,"="); v[a[1]]=a[2]}; n[v["state"]]++; fps[v["state"]]+=v["fps"]; dtm[v["state"]]=(v["dt_max_ms"]>dtm[v["state"]])?v["dt_max_ms"]:dtm[v["state"]]} $3=="CRASH" {print "CRASH:", $0} END {printf "%-22s %6s %8s %10s\n","state","snaps","avg_fps","worst_ms"; for(s in n) printf "%-22s %6d %8.1f %10.1f\n", s, n[s], fps[s]/n[s], dtm[s]}' build/telemetry/telemetry.log

# Watch telemetry from a specific device (pass serial)
tel-device serial:
    adb -s {{serial}} logcat -s SDL/APP:I | grep --line-buffered TEL

# Interactive shell in app context
shell:
    adb shell "run-as systems.shorty.lmm sh"

# List config overrides
list-configs:
    @echo "Config overrides (edit these, they persist across fetches):"
    @ls -la config-overrides/*/

# Edit Cryptid config
edit-cryptid:
    ${EDITOR:-vim} config-overrides/Cryptid/config.lua

# Edit Steamodded config
edit-steamodded:
    ${EDITOR:-vim} config-overrides/Steamodded/config.lua
