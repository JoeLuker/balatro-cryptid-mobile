#!/usr/bin/env bash
# nix/sign.sh — sign the (pure, reproducible) APK with the local debug keystore.
#
# Signing is IMPURE by design: keys/debug.keystore is a machine-local secret that
# preserves the app's identity across rebuilds and must never enter the Nix store
# or the repo (keys/ is gitignored). So the build derivation emits an
# unsigned-aligned APK (reproducible), and this thin step signs it.
#
# Usage: nix/sign.sh [output.apk]   (default: build/balatro-cryptid.apk)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/build/balatro-cryptid.apk}"
KS="$ROOT/keys/debug.keystore"
PKG="com.unofficial.balatro.cryptid"

if [[ ! -f "$KS" ]]; then
  mkdir -p "$ROOT/keys"
  echo "[sign] creating debug keystore (first run)…"
  nix-shell "$ROOT/shell.nix" --run "keytool -genkey -v -keystore '$KS' \
    -storepass android -alias androiddebugkey -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=Android,C=US'"
fi

echo "[sign] building reproducible APK…"
APKDIR="$(nix-build "$ROOT/nix/balatro-cryptid.nix" -A apk --no-out-link)"
ALIGNED="$APKDIR/$PKG.unsigned-aligned.apk"
[[ -f "$ALIGNED" ]] || { echo "[sign] aligned APK not found at $ALIGNED" >&2; exit 1; }

mkdir -p "$(dirname "$OUT")"
echo "[sign] signing → $OUT"
nix-shell "$ROOT/shell.nix" --run "apksigner sign --ks '$KS' --ks-pass pass:android --out '$OUT' '$ALIGNED'"
nix-shell "$ROOT/shell.nix" --run "apksigner verify --verbose '$OUT'" | head -5
echo "[sign] done: $OUT ($(du -h "$OUT" | cut -f1))"
