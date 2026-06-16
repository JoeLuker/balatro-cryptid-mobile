#!/usr/bin/env bash
set -euo pipefail

# oracle-check: run every baseline in test/score-oracle-baselines.txt through
# the score-oracle and fail if any score doesn't match.
#
# Usage (inside nix-shell — needs love + xvfb-run):
#   test/oracle-check.sh
#   test/oracle-check.sh --fast   # stop at first failure (default: run all)
#
# Exit: 0 if every baseline matches; 1 if any mismatch or oracle error.
# Each run appends to build/score-oracle/oracle.log (overwritten per invocation
# by score-oracle.sh, so we copy it out before the next run).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
BASELINES="$SCRIPT_DIR/score-oracle-baselines.txt"
ORACLE="$SCRIPT_DIR/score-oracle.sh"
LOG_DIR="$PROJECT_DIR/build/score-oracle/check"

STOP_EARLY=0
for arg in "$@"; do
    [[ "$arg" == "--fast" ]] && STOP_EARLY=1
done

[[ -f "$BASELINES" ]] || { echo "[ocheck] baselines file missing: $BASELINES" >&2; exit 2; }
[[ -f "$ORACLE"    ]] || { echo "[ocheck] oracle runner missing: $ORACLE" >&2; exit 2; }
[[ -f "$PROJECT_DIR/build/game/main.lua" ]] || {
    echo "[ocheck] build/game missing — run ./scripts/build.sh build first" >&2
    exit 2
}
for tool in love xvfb-run; do
    command -v "$tool" >/dev/null || {
        echo "[ocheck] $tool not on PATH — run inside nix-shell" >&2
        exit 2
    }
done

mkdir -p "$LOG_DIR"

pass=0
fail=0
skip=0
declare -a results=()  # "PASS|FAIL  seed  hand  jokers  expected  got"

linenum=0
while IFS= read -r line; do
    linenum=$((linenum + 1))
    # Strip leading whitespace; skip blank lines and comments
    trimmed="${line#"${line%%[![:space:]]*}"}"
    [[ -z "$trimmed" || "${trimmed:0:1}" == "#" ]] && continue

    # Parse: seed  hand  jokers  score  [hand-type  settled-state]
    # Fields are whitespace-separated; jokers may be "(none)"
    read -r seed hand jokers expected _rest <<< "$trimmed"

    # Normalise jokers: "(none)" -> empty string for the env var
    [[ "$jokers" == "(none)" ]] && jokers=""

    label="seed=$seed hand=$hand jokers=${jokers:-(none)}"
    echo "[ocheck] running: $label"

    # Run the oracle, capturing stdout; score-oracle.sh exits 1 on failure
    oracle_out=""
    oracle_rc=0
    oracle_out="$(
        ORACLE_SEED="$seed" \
        ORACLE_HAND="$hand" \
        ORACLE_JOKERS="$jokers" \
        bash "$ORACLE" 2>&1
    )" || oracle_rc=$?

    # Copy the oracle log (score-oracle.sh always writes build/score-oracle/oracle.log)
    src_log="$PROJECT_DIR/build/score-oracle/oracle.log"
    dest_log="$LOG_DIR/$(printf '%03d' "$linenum")-${seed}-${hand//,/_}.log"
    [[ -f "$src_log" ]] && cp "$src_log" "$dest_log"

    if [[ $oracle_rc -ne 0 ]]; then
        echo "[ocheck] FAIL (oracle error rc=$oracle_rc) — $label"
        echo "[ocheck]   log: $dest_log"
        results+=("FAIL  $seed  $hand  ${jokers:-(none)}  expected=$expected  got=ERROR(rc=$oracle_rc)")
        fail=$((fail + 1))
        [[ $STOP_EARLY -eq 1 ]] && break
        continue
    fi

    # Extract the score from "[oracle] PASS ... score=N" line.
    # number_format() may insert thousands separators (e.g. "1,208"), so
    # strip commas before comparing to the baseline integer.
    got="$(printf '%s\n' "$oracle_out" | grep -oP '(?<=score=)[\d,]+' | tail -1 | tr -d ',')"

    if [[ -z "$got" ]]; then
        echo "[ocheck] FAIL (no score in output) — $label"
        results+=("FAIL  $seed  $hand  ${jokers:-(none)}  expected=$expected  got=MISSING")
        fail=$((fail + 1))
        [[ $STOP_EARLY -eq 1 ]] && break
        continue
    fi

    if [[ "$got" == "$expected" ]]; then
        echo "[ocheck] PASS score=$got — $label"
        results+=("PASS  $seed  $hand  ${jokers:-(none)}  expected=$expected  got=$got")
        pass=$((pass + 1))
    else
        echo "[ocheck] FAIL score=$got (expected $expected) — $label"
        echo "[ocheck]   log: $dest_log"
        results+=("FAIL  $seed  $hand  ${jokers:-(none)}  expected=$expected  got=$got")
        fail=$((fail + 1))
        [[ $STOP_EARLY -eq 1 ]] && break
    fi
done < "$BASELINES"

total=$((pass + fail + skip))
echo ""
echo "[ocheck] ──────────────────────────────────────────────"
echo "[ocheck] Results: $pass/$total passed, $fail failed"
for r in "${results[@]}"; do
    echo "[ocheck]   $r"
done
echo "[ocheck] ──────────────────────────────────────────────"

[[ $fail -eq 0 ]]
