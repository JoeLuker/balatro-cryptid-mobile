#!/usr/bin/env bash
set -euo pipefail

# Kotlin rebuild oracle-parity check — compiles the FAITHFUL Score engine (the
# pure-Kotlin game/ + content/ sources, which carry no Android/Compose deps)
# and runs the systems.balatro.game.Oracle harness. Oracle asserts ~99 scored
# hands match the exact scores the original LÖVE build recorded. Exit 0 iff
# every case passes (Oracle.main exits 1 on any drift).
#
# This is the REBUILD-side counterpart to `just score-oracle` / `just
# oracle-check` (which boot the real LÖVE build to GENERATE/verify baselines and
# are slow): this checks the rebuilt Kotlin engine against them — fast, no
# emulator/phone. No kotlinc install needed; it's pulled transiently via
# nix-shell.
#
# Usage: just kt-oracle      (or: test/kt-oracle.sh)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
SRC="$PROJECT_DIR/rebuild/app/src/main/kotlin/systems/balatro"
OUT_DIR="$PROJECT_DIR/build/kt-oracle"
JAR="$OUT_DIR/oracle.jar"

if [[ ! -f "$SRC/game/Oracle.kt" ]]; then
    echo "[kt-oracle] $SRC/game/Oracle.kt missing" >&2
    exit 2
fi
if ! command -v nix-shell >/dev/null 2>&1; then
    echo "[kt-oracle] nix-shell not found — needed to provide the kotlin compiler" >&2
    exit 2
fi

mkdir -p "$OUT_DIR"
nix-shell -p kotlin --run \
    "kotlinc '$SRC'/game '$SRC'/content -include-runtime -d '$JAR' && kotlin -cp '$JAR' systems.balatro.game.Oracle"
