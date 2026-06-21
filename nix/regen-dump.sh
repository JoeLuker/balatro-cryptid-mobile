#!/usr/bin/env bash
# nix/regen-dump.sh — regenerate the lovely-merged dump FROM THE PINS.
#
# This is the dump's "updater" (analogous to update-sources.sh for mods): it
# stages the pinned Balatro.love + pinned mods, boots desktop LÖVE + lovely under
# Xvfb, and collects the dump lovely writes as each file loads. The output is
# then vendored (vendor/dump/) so the APK build is hermetic and provably
# consistent with nix/sources.json — no mod/dump drift.
#
# Impure by necessity (love needs a GL context → Xvfb, outside the Nix sandbox).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RIG="$ROOT/build/dump-rig-pinned"
OUT="$ROOT/vendor/dump"
LOVELY_SO="${LOVELY_SO:-$ROOT/tools/lovely-src/target/release/liblovely.so}"
BOOT_SECONDS="${BOOT_SECONDS:-90}"

[[ -f "$LOVELY_SO" ]] || { echo "[regen] liblovely.so missing — build tools/lovely-src first" >&2; exit 2; }

echo "[regen] resolving pinned Balatro.love + mods..."
BALATRO_LOVE="$(nix-build "$ROOT/nix/sources.nix" -A balatro_love --no-out-link)"

rm -rf "$RIG"; mkdir -p "$RIG/Mods"
cp "$BALATRO_LOVE" "$RIG/Balatro.love"
"$ROOT/nix/stage-mods.sh" "$RIG/Mods"

echo "[regen] booting under lovely (${BOOT_SECONDS}s)..."
# SMODS restarts LÖVE once after first load; the re-exec must still find the
# game and have a valid runtime dir. Seed the game at love's save-identity path
# and pin XDG_* so the restart survives to running state (full dump coverage).
mkdir -p "$RIG/xdg-data/love/Balatro" "$RIG/xdg-runtime"
chmod 700 "$RIG/xdg-runtime"
cp "$RIG/Balatro.love" "$RIG/xdg-data/love/Balatro/Balatro.love"
(cd "$RIG" && nix-shell "$ROOT/shell.nix" --run \
  "XDG_DATA_HOME='$RIG/xdg-data' XDG_RUNTIME_DIR='$RIG/xdg-runtime' LOVELY_MOD_DIR='$RIG/Mods' timeout $BOOT_SECONDS xvfb-run -a env LD_PRELOAD='$LOVELY_SO' love '$RIG/Balatro.love'" \
  > "$RIG/boot.log" 2>&1) || true

DUMP="$RIG/Mods/lovely/dump"
for marker in main.lua game.lua globals.lua engine/event.lua functions/state_events.lua; do
  if [[ ! -f "$DUMP/$marker" ]]; then
    echo "[regen] FAIL: $marker missing from dump — boot died early; tail boot.log:" >&2
    tail -40 "$RIG/boot.log" >&2
    exit 1
  fi
done

echo "[regen] vendoring dump → $OUT"
rm -rf "$OUT"; mkdir -p "$OUT"
(cd "$DUMP" && find . -name '*.lua' -exec cp --parents {} "$OUT/" \;)

# stamp the dump with the exact source revs it was generated from
jq -r 'to_entries|map("\(.key)\t\(.value.rev // .value.tag // .value.sha256)")|.[]' \
  "$ROOT/nix/sources.json" > "$OUT/.source-revs"
echo "[regen] dump: $(find "$OUT" -name '*.lua' | wc -l) lua files, stamped with pins"
