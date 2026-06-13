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

# Telemetry gate test — boots build/game twice (toggles off, then on) and
# asserts zero telemetry output/files by default, live output when enabled.
# Needs nix-shell (love + xvfb-run), same as smoke. ~4 min.
telgate:
    test/telemetry-gate.sh

# Vanilla gameset test — boots build/game twice (vanilla, then mainline) and
# asserts all Cryptid content gates off / stays on respectively.
# Needs nix-shell, same as smoke. ~5 min.
gameset:
    test/gameset/run.sh

# Resize test — boots build/game once and drives love.resize through the
# foldable's surface geometries (inner/cover x landscape/portrait, splits),
# asserting contain invariants and idempotence. Needs nix-shell. ~1 min.
resize:
    test/resize/run.sh

# UI_O_DETACHED regression — recalculating a UI tree with a detached UIT.O
# object must lay out 0x0, not nil dims (fold-close field crash 2026-06-12).
# Needs nix-shell. ~30 s.
uio:
    test/ui-o-dims/run.sh

# Disabled-item pool gate — gameset-disabled items must be non-spawnable
# (add_to_pool) and forced missing keys must fall through to the pool roll
# (Spectral soul-roll field crash 2026-06-12). Needs nix-shell. ~1 min.
poolgate:
    test/pool-gate/run.sh

# NUGC v2 regression — heap >200MB + breath-state entry must full-collect
# (debounced 30s), keeping the 300MB mid-hand emergency cliff unreachable.
# Needs nix-shell. ~30 s.
nugc:
    test/nugc/run.sh

# EventManager differential soak — original vs EVQ_COMPACT implementation,
# mirrored random event scripts; pure luajit, no love needed. ~1 min.
evq-diff:
    luajit test/event/diff.lua 150 100

# Trigger-collapse validation — affine property suite vs Amulet's real cdata
# Big, then the in-game differential (same cascade scored with collapse off
# and on must match exactly). Needs nix-shell. ~4 min.
collapse:
    test/collapse/run.sh

# Headless Android-emulator test of the BUILT APK (KVM + ARM translation):
# installs build/apk/*.apk, mirrors the phone deploy, polls screenshots until
# a menu-like frame (PASS) or crash-screen signature (FAIL). ~5-10 min.
# All adb calls are serial-scoped — never touches a connected phone.
emu-test:
    nix-shell test/emulator/shell.nix --run 'test/emulator/run.sh'

# All local tests (run before deploying to the phone)
test: evq-diff test-controller smoke telgate gameset resize uio nugc poolgate collapse

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
# save dir, flushed every 5s and on crash/background, rotated at 1MB).
# Requires Settings > Game > Debug Logging ON (default OFF — shareable APK).
perf-pull:
    @mkdir -p build/telemetry
    adb exec-out "run-as systems.shorty.lmm cat files/save/game/telemetry.log" > build/telemetry/telemetry.log 2>/dev/null || echo "no telemetry.log on device yet"
    -adb exec-out "run-as systems.shorty.lmm cat files/save/game/telemetry.log.1" > build/telemetry/telemetry.log.1 2>/dev/null
    @echo "pulled to build/telemetry/ — lines: $(wc -l < build/telemetry/telemetry.log)"

# Run the phone-home telemetry receiver in the foreground (the app POSTs its
# flushed telemetry here over the tailnet — lands in ~/balatro-telemetry/phone.log
# the moment it happens, no adb needed). --print-unit emits a systemd user unit.
# Requires Settings > Game > Phone Home Telemetry ON (default OFF).
tel-home:
    python3 scripts/telemetry-home.py

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

# Page-cycle regression — cycling the PAGE option-cycle in Cryptid's per-item
# toggle screen must keep the overlay open (parent = toggle_area fix).
# Needs nix-shell. ~30 s.
page-cycle:
    test/page-cycle.sh

# Lazy-shader elision regression — DRAW_SHADER_NIL_RESET + LAZY_SHADER must
# produce >= 30 % GPU shader-bind elision at SELECTING_HAND on seed AAAAAAAA.
# Also asserts the DRAW_SHADER_NIL_RESET sentinel in engine/sprite.lua.
# Needs nix-shell. ~90 s.
lazy-shader-elision:
    test/lazy-shader-elision.sh

# Score oracle — boot build/game headless on a fixed seed, shape a specific
# hand, play it, and print the exact chip score the current build produces.
# Use to record "seed X + hand Y -> exact score N" baselines for parity tests.
# Env vars: ORACLE_SEED (default: AAAAAAAA), ORACLE_HAND (default: S_A,H_A,D_A,C_A,S_K)
# Needs nix-shell. ~60-90 s.
score-oracle:
    test/score-oracle.sh

# Oracle regression check — runs every baseline in test/score-oracle-baselines.txt
# through score-oracle and fails if any score drifts. Use after scoring-pipeline
# changes to verify no regressions.
# Pass --fast to stop at first failure.
# Needs nix-shell. ~10-15 min for all 10 baselines.
oracle-check *args:
    test/oracle-check.sh {{args}}
