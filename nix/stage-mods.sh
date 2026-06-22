#!/usr/bin/env bash
# nix/stage-mods.sh — materialise the PINNED mods (nix/sources.json) into a
# desktop Mods/ layout, lovely/ INTACT.
#
# Used by the dump rig: lovely needs each mod's lovely/*.toml present to generate
# the merged dump. Unlike the APK embed (which strips lovely/ and root-mounts
# Amulet), this is the *desktop* layout — every mod a subdir of Mods/, including
# Amulet (Mods/Amulet/{talisman,big-num,lovely,...}), which the current on-disk
# mods/ gets wrong (Amulet flat at root → regen-dump's `cp mods/Amulet` is dead).
#
# Usage: nix/stage-mods.sh <dest-Mods-dir>
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${1:?usage: stage-mods.sh <dest Mods dir>}"
mkdir -p "$DEST"

store() { nix-build "$ROOT/nix/sources.nix" -A "$1" --no-out-link 2>/dev/null; }

# git-tree mods → Mods/<Name>
for pair in steamodded:Steamodded cryptid:Cryptid sticky_fingers:sticky-fingers; do
  key="${pair%%:*}"; name="${pair##*:}"
  rm -rf "$DEST/$name"
  cp -r --no-preserve=mode "$(store "$key")" "$DEST/$name"
done

# zip mods → Mods/<Name> (Amulet flat zip lands directly under Mods/Amulet/)
unzip_to() {
  local src tmp inner; src="$(store "$1")"; tmp="$(mktemp -d)"
  unzip -q "$src" -d "$tmp"
  rm -rf "$DEST/$2"; mkdir -p "$DEST/$2"
  inner="$(cd "$tmp" && ls -1)"
  if [ "$(printf '%s\n' "$inner" | wc -l)" = 1 ] && [ -d "$tmp/$inner" ]; then
    cp -r --no-preserve=mode "$tmp/$inner"/. "$DEST/$2/"
  else
    cp -r --no-preserve=mode "$tmp"/. "$DEST/$2/"
  fi
  rm -rf "$tmp"
}
unzip_to amulet      Amulet
unzip_to cardsleeves CardSleeves
unzip_to debugplus   DebugPlus

echo "[stage-mods] staged into $DEST:"; ls -1 "$DEST"
