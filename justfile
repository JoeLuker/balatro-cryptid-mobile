# Balatro Cryptid Mobile - Build Commands
# Usage: just <command>
# Run 'just --list' to see all commands

set shell := ["bash", "-euc"]

# Default recipe - show help
default:
    @just --list

# Check legacy tools (the Nix build supplies its own toolchain; deploy still uses adb)
check:
    ./scripts/build.sh check

# Sources are pinned in nix/sources.json and fetched by Nix at build time.
fetch:
    @echo "Sources are pinned in nix/sources.json — Nix fetches them on build."
    @echo "Re-pin to newer upstreams with: nix/update-sources.sh"

# Build game.love + signed APK via Nix (pinned, reproducible), then stage build/
# for the local tests (build/game) and deploy (build/apk + build/phone-transfer).
build:
    #!/usr/bin/env bash
    set -euo pipefail
    echo "[build] game.love (Nix, pinned sources)…"
    gl="$(nix-build nix/balatro-cryptid.nix -A gameLove --no-out-link)"
    rm -rf build/game; mkdir -p build/game; unzip -q "$gl" -d build/game
    echo "[build] signing APK…"
    nix/sign.sh build/balatro-cryptid.apk
    mkdir -p build/apk; cp build/balatro-cryptid.apk build/apk/com.unofficial.balatro.cryptid.apk
    echo "[build] staging save-dir mods (build/phone-transfer)…"
    rm -rf build/phone-transfer; mkdir -p build/phone-transfer
    cp -r build/game/Mods build/phone-transfer/Mods
    [ -f build/game/lovely.lua ] && cp build/game/lovely.lua build/phone-transfer/lovely.lua || true
    echo "[build] done → build/balatro-cryptid.apk, build/game, build/phone-transfer"

# Deploy APK and mods to connected phone (installs build/apk + pushes build/phone-transfer)
deploy:
    ./scripts/build.sh deploy

# Full pipeline: build (Nix) + deploy
all: build deploy

# Watch app logs from connected phone
logs:
    ./scripts/build.sh logs

# Clean all build artifacts
clean:
    ./scripts/build.sh clean

# Alias: build + deploy
quick: build deploy

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

# OBS — unified observability suite (telemetry + crashes). One entry point:
#   just obs           dashboard: component registry + per-state fps + crash count (pulls first)
#   just obs pull      pull telemetry.log (+ .1) and crash.log from the device
#   just obs crashes   pull + print every crash (crash.log is ALWAYS captured, even with logging off)
#   just obs perf      per-state fps / worst-frame summary
#   just obs watch     live telemetry over logcat
#   just obs home      run the phone-home receiver
# Gate: Settings > Game > Debug Logging (telemetry) / Phone Home Telemetry.
# Crashes are captured to crash.log regardless of the gate. Sinks self-announce
# in the log (OBS_INIT) and list component health (OBS_REGISTRY). See docs/OBSERVABILITY.md.
obs cmd="dashboard":
    #!/usr/bin/env bash
    set -uo pipefail
    APP=systems.shorty.lmm; SD=files/save/game; OUT=build/telemetry
    _pull() {
      mkdir -p "$OUT"
      adb exec-out "run-as $APP cat $SD/telemetry.log"   > "$OUT/telemetry.log"   2>/dev/null || true
      adb exec-out "run-as $APP cat $SD/telemetry.log.1" > "$OUT/telemetry.log.1" 2>/dev/null || true
      adb exec-out "run-as $APP cat $SD/crash.log"       > "$OUT/crash.log"       2>/dev/null || true
    }
    _perf() {
      awk '$3=="PERF_SNAPSHOT" {for(i=4;i<=NF;i++){split($i,a,"="); v[a[1]]=a[2]}; n[v["state"]]++; fps[v["state"]]+=v["fps"]; dtm[v["state"]]=(v["dt_max_ms"]>dtm[v["state"]])?v["dt_max_ms"]:dtm[v["state"]]} END {printf "%-22s %6s %8s %10s\n","state","snaps","avg_fps","worst_ms"; for(s in n) printf "%-22s %6d %8.1f %10.1f\n", s, n[s], fps[s]/n[s], dtm[s]}' "$OUT/telemetry.log" 2>/dev/null || echo "  (no telemetry.log — run: just obs pull)"
    }
    case "{{cmd}}" in
      pull)    _pull; echo "pulled → $OUT/  (telemetry $(wc -l < "$OUT/telemetry.log" 2>/dev/null || echo 0) lines, crash.log $(wc -l < "$OUT/crash.log" 2>/dev/null || echo 0) lines)";;
      crashes) _pull
               if [ -s "$OUT/crash.log" ]; then echo "=== crash.log (always-on, gate-independent) ==="; cat "$OUT/crash.log"; else echo "no crashes in crash.log 🎉"; fi
               grep -a CRASH "$OUT/telemetry.log" 2>/dev/null || true;;
      perf)    _pull; _perf;;
      watch)   adb logcat -s SDL/APP:I | grep --line-buffered TEL;;
      home)    python3 scripts/telemetry-home.py;;
      dashboard|*)
               _pull
               echo "=== OBS registry (component health) ==="
               grep -a OBS_INIT     "$OUT/telemetry.log" 2>/dev/null | tail -1 || true
               grep -a OBS_REGISTRY "$OUT/telemetry.log" 2>/dev/null | tail -1 || echo "  (no OBS_REGISTRY — enable Debug Logging + boot, then: just obs)"
               echo "=== per-state perf ==="; _perf
               echo "=== crashes ==="
               if [ -s "$OUT/crash.log" ]; then echo "  crash.log lines: $(wc -l < "$OUT/crash.log")  (just obs crashes to view)"; else echo "  none 🎉"; fi;;
    esac

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

# Kotlin rebuild oracle-parity — compiles the pure-Kotlin Score engine and runs
# the systems.balatro.game.Oracle harness (~99 scored-hand baselines) against
# the scores recorded by the LÖVE build. Fast, no emulator/phone; checks the
# REBUILT engine, complementing score-oracle/oracle-check (which generate and
# verify baselines from the LÖVE build). Needs nix-shell (kotlin). ~30-60 s.
kt-oracle:
    test/kt-oracle.sh
