#!/usr/bin/env bash
# Regenerate the lovely-merged dump LOCALLY (no othaos/Mac dependency).
#
# Stages src/Balatro.love + the vendored mods/ set into build/dump-rig, boots
# it under desktop LÖVE + lovely-injector (built from source in
# tools/lovely-src — the prebuilt release lib aborts on NixOS) inside Xvfb,
# and collects the dump lovely writes while patching each file as it loads.
#
# Key invariants learned the hard way:
#   - LD_PRELOAD must reach ONLY the love process (via `env` prefix):
#     preloading into xvfb-run's shells aborts in lovely's ctor, which
#     dlopens libluajit-5.1.so.2 and unwrap-panics in processes without it.
#   - Mod set parity: the rig copies from mods/ (what the APK embeds), so the
#     dump can never drift from the shipped mod versions.
#   - Dumps are written during the patch phase as files load; reaching the
#     running state covers every file the build consumes. The boot is
#     timeout-killed; EXIT 124 is the expected success shape, verified by the
#     marker file set below.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RIG="$ROOT/build/dump-rig"
LOVELY_SO="$ROOT/tools/lovely-src/target/release/liblovely.so"
MODS=(Steamodded Cryptid Amulet sticky-fingers CardSleeves DebugPlus)
BOOT_SECONDS="${BOOT_SECONDS:-90}"

if [[ ! -f "$LOVELY_SO" ]]; then
    echo "[regen-dump] building lovely-injector from source..."
    (cd "$ROOT/tools/lovely-src" \
        && git submodule update --init --recursive --depth 1 \
        && RUSTUP_TOOLCHAIN=stable nix-shell -p gcc cmake clang \
            --run "cargo build --release -p lovely-unix")
fi

echo "[regen-dump] staging rig..."
rm -rf "$RIG"
mkdir -p "$RIG/Mods"
cp "$ROOT/src/Balatro.love" "$RIG/"
for m in "${MODS[@]}"; do
    cp -r "$ROOT/mods/$m" "$RIG/Mods/"
done

echo "[regen-dump] booting under lovely (${BOOT_SECONDS}s)..."
(cd "$RIG" && nix-shell "$ROOT/shell.nix" --run \
    "LOVELY_MOD_DIR='$RIG/Mods' timeout $BOOT_SECONDS xvfb-run -a env LD_PRELOAD='$LOVELY_SO' love Balatro.love" \
    > "$RIG/boot.log" 2>&1) || true

DUMP="$RIG/Mods/lovely/dump"
for marker in main.lua game.lua globals.lua engine/event.lua functions/state_events.lua; do
    if [[ ! -f "$DUMP/$marker" ]]; then
        echo "[regen-dump] FAIL: $marker missing from dump — boot died early; see $RIG/boot.log" >&2
        exit 1
    fi
done

echo "[regen-dump] dump complete: $(find "$DUMP" -name '*.lua' | wc -l) lua files"
echo "[regen-dump] install with:"
echo "  rm -rf src/dump && mkdir src/dump && (cd '$DUMP' && find . -name '*.lua' -exec cp --parents {} '$ROOT/src/dump/' \;)"
