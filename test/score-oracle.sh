#!/usr/bin/env bash
set -euo pipefail

# Score-oracle: boot build/game headless, start a run on a fixed seed, shape
# a specific hand, play it, and print the exact chip score the CURRENT build
# produces. Use this to record "seed X + hand Y -> exact score N" baselines.
#
# Usage (inside nix-shell — needs love + xvfb-run):
#   test/score-oracle.sh
#   ORACLE_SEED=12345678 ORACLE_HAND=H_A,S_A,D_A,C_A,H_K test/score-oracle.sh
#   ORACLE_JOKERS=j_joker,j_mult ORACLE_HAND=S_A,H_A test/score-oracle.sh
#
# Environment variables (all optional — same defaults as the autorun):
#   ORACLE_SEED    8-char seed string  (default: AAAAAAAA)
#   ORACLE_HAND    comma-separated P_CARDS keys  (default: S_A,H_A,D_A,C_A,S_K)
#                  Card key format: <suit>_<rank>
#                    suits: S=Spades H=Hearts D=Diamonds C=Clubs
#                    ranks: 2-9, T=10, J=Jack, Q=Queen, K=King, A=Ace
#                  Examples: S_A H_K D_T C_2
#   ORACLE_JOKERS  comma-separated P_CENTERS joker keys  (default: empty)
#                  e.g. j_joker,j_greedy_joker
#
# Exit: 0 and prints "ORC: score=<N>" on success; nonzero on failure.
# Log:  build/score-oracle/oracle.log

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
GAME_DIR="$PROJECT_DIR/build/game"
OUT_DIR="$PROJECT_DIR/build/score-oracle"

ORACLE_SEED="${ORACLE_SEED:-AAAAAAAA}"
ORACLE_HAND="${ORACLE_HAND:-S_A,H_A,D_A,C_A,S_K}"
ORACLE_JOKERS="${ORACLE_JOKERS:-}"

[[ -f "$GAME_DIR/main.lua" ]] || {
    echo "[oracle] build/game missing — run ./scripts/build.sh build first" >&2
    exit 2
}
for tool in love xvfb-run; do
    command -v "$tool" >/dev/null || {
        echo "[oracle] $tool not on PATH — run inside nix-shell" >&2
        exit 2
    }
done

mkdir -p "$OUT_DIR"
WORK="$(mktemp -d /tmp/balatro-oracle-XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

echo "[oracle] seed=$ORACLE_SEED hand=$ORACLE_HAND jokers=${ORACLE_JOKERS:-(none)}"
echo "[oracle] staging in $WORK"
cp -r "$GAME_DIR" "$WORK/game"

# Inject: OS spoof as the very first line (before any getOS call), autorun
# require as the very last (after every wrapper has installed).
{
    echo "if os.getenv('BALATRO_FAKE_ANDROID') == '1' then love.system.getOS = function() return 'Android' end end"
    cat "$WORK/game/main.lua"
    echo ""
    echo "do local ok, err = pcall(require, 'score-oracle-autorun') if not ok then print('ORC: FAIL autorun-load-failed: '..tostring(err)) end end"
} > "$WORK/game/main.lua.injected"
mv "$WORK/game/main.lua.injected" "$WORK/game/main.lua"
cp "$SCRIPT_DIR/score-oracle-autorun.lua" "$WORK/game/score-oracle-autorun.lua"

# Seed the save dir with the pulled profile if one exists (same as nugc/run.sh).
# A pulled profile has tutorial_complete=true and no intro overlays, making the
# run more stable. Without it the autorun suppresses the tutorial itself.
if [[ -d "$PROJECT_DIR/build/save-pull/game" ]]; then
    mkdir -p "$WORK/xdg/love"
    cp -r "$PROJECT_DIR/build/save-pull/game" "$WORK/xdg/love/game"
else
    mkdir -p "$WORK/xdg"
fi

LOG="$OUT_DIR/oracle.log"
echo "[oracle] booting under Xvfb (software GL, dummy audio)..."
set +e
XDG_DATA_HOME="$WORK/xdg" \
SDL_AUDIODRIVER=dummy \
LIBGL_ALWAYS_SOFTWARE=1 \
BALATRO_FAKE_ANDROID=1 \
ORACLE_SEED="$ORACLE_SEED" \
ORACLE_HAND="$ORACLE_HAND" \
ORACLE_JOKERS="$ORACLE_JOKERS" \
timeout 300 xvfb-run -a -s "-screen 0 1600x900x24" love "$WORK/game" >"$LOG" 2>&1
rc=$?
set -e

grep -a "^ORC:" "$LOG" || true

if ! grep -aq "^ORC: PASS" "$LOG"; then
    echo "[oracle] FAIL rc=$rc — last 30 log lines:" >&2
    tail -30 "$LOG" >&2
    exit 1
fi

score="$(grep -a "^ORC: score=" "$LOG" | tail -1 | cut -d= -f2-)"
echo "[oracle] PASS seed=$ORACLE_SEED hand=$ORACLE_HAND jokers=${ORACLE_JOKERS:-(none)} score=$score"
echo "[oracle] log: $LOG"
