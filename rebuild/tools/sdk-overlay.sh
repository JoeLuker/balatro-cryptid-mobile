#!/usr/bin/env bash
# Durable, GC-rooted Android SDK overlay for the rebuild.
#
# Why this exists: AGP needs a WRITABLE sdk.dir (it probes/installs components),
# but the nix SDK is a read-only store path. The overlay is real directories
# with symlinks into the nix SDK, plus a pre-accepted license file.
#
# Why it looks like this: a previous incarnation lived at /tmp/android-sdk-overlay
# with UN-rooted store symlinks — a nix GC collected the platform out from under
# it and every gradle build broke with "Failed to install platforms;android-34".
# This one is GC-proof (`nix-build -o .sdk-gcroot` registers a root) and lives
# in a durable path. Re-run any time; it is idempotent.
#
# Usage: rebuild/tools/sdk-overlay.sh [dest]   (default: ~/.local/share/balatro-android-sdk)
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"                      # rebuild/
DEST="${1:-$HOME/.local/share/balatro-android-sdk}"

echo "[sdk-overlay] realizing the nix SDK (GC-rooted at $DEST/.sdk-gcroot)…"
mkdir -p "$DEST"
out="$(nix-build "$HERE/nix/android-sdk.nix" -o "$DEST/.sdk-gcroot")"
SDK="$out/libexec/android-sdk"

echo "[sdk-overlay] building overlay dirs…"
rm -rf "$DEST/platforms" "$DEST/build-tools" "$DEST/platform-tools" "$DEST/licenses"
mkdir -p "$DEST/platforms" "$DEST/build-tools" "$DEST/licenses"
for p in "$SDK/platforms"/*;   do ln -sT "$p" "$DEST/platforms/$(basename "$p")"; done
for b in "$SDK/build-tools"/*; do ln -sT "$b" "$DEST/build-tools/$(basename "$b")"; done
# platform-tools copied for real (adb & friends want a writable, executable home)
cp -r --no-preserve=mode "$SDK/platform-tools" "$DEST/platform-tools"
chmod +x "$DEST/platform-tools"/* 2>/dev/null || true
printf '24333f8a63b6825ea9c5514f83c2829b004d1fee\n' > "$DEST/licenses/android-sdk-license"

echo "sdk.dir=$DEST" > "$HERE/local.properties"
echo "[sdk-overlay] done — sdk.dir=$DEST (root: $DEST/.sdk-gcroot → $out)"
