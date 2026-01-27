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

# Push only mod files (no APK reinstall)
push-mods:
    #!/usr/bin/env bash
    set -euo pipefail
    PACKAGE_ID="com.unofficial.balatro.cryptid"
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
    adb shell am force-stop com.unofficial.balatro.cryptid

# Start the app
start:
    adb shell am start -n "com.unofficial.balatro.cryptid/org.love2d.android.GameActivity"

# Restart the app
restart: stop start

# Show what's in the app's save directory
ls-save:
    adb shell "run-as com.unofficial.balatro.cryptid ls -la files/save/"

# Show what mods are installed
ls-mods:
    adb shell "run-as com.unofficial.balatro.cryptid ls -la files/save/Mods/"

# Pull logs to local file
save-logs:
    adb logcat -d > build/logcat.txt
    @echo "Logs saved to build/logcat.txt"

# Interactive shell in app context
shell:
    adb shell "run-as com.unofficial.balatro.cryptid sh"

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
