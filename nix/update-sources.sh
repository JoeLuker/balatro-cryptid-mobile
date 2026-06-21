#!/usr/bin/env bash
# nix/update-sources.sh — pin updater for the LÖVE Cryptid build (niv/npins-style).
#
# Resolves every upstream input to an exact rev / release-asset + content hash
# and writes nix/sources.json (the lockfile). This is the single source of truth
# for "what goes into the build" — re-run it to bump a pin, commit the diff.
#
# Pin kinds:
#   github  — git tree at a commit (a tag/branch ref is resolved to a sha)
#   release — one specific release-asset zip, pinned by URL + sha256
#   url     — any URL, pinned by sha256
#   file    — a local, non-redistributable blob (Balatro.love); sha256 only,
#             consumed via requireFile so the bytes never enter the repo
#
# Needs: gh, jq, nix-prefetch-url, network.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/nix/sources.json"

# The non-redistributable game blob (© LocalThunk). Pinned by hash only — never
# vendored. Override with BALATRO_LOVE=/path if it lives elsewhere.
BALATRO_LOVE="${BALATRO_LOVE:-/home/jluker/balatro-cryptid-mobile/src/Balatro.love}"

prefetch_unpack() { nix-prefetch-url --unpack "$1" 2>/dev/null | tail -1; }
prefetch_file()   { nix-prefetch-url "$1"           2>/dev/null | tail -1; }
resolve_sha()     { gh api "repos/$1/commits/$2" --jq '.sha'; }

json='{}'
add() { json="$(jq --arg k "$1" --argjson e "$2" '.[$k]=$e' <<<"$json")"; }

gh_pin() { # name owner repo ref
  local name=$1 owner=$2 repo=$3 ref=$4 sha h
  echo "[pin] $name  ($owner/$repo @ $ref)" >&2
  sha=$(resolve_sha "$owner/$repo" "$ref")
  h=$(prefetch_unpack "https://github.com/$owner/$repo/archive/$sha.tar.gz")
  add "$name" "$(jq -n --arg kind github --arg o "$owner" --arg r "$repo" \
        --arg rev "$sha" --arg ref "$ref" --arg h "$h" \
        '{kind:$kind,owner:$o,repo:$r,rev:$rev,ref:$ref,sha256:$h}')"
}

release_pin() { # name owner repo tag asset
  local name=$1 owner=$2 repo=$3 tag=$4 asset=$5 url h
  url="https://github.com/$owner/$repo/releases/download/$tag/$asset"
  echo "[pin] $name  ($owner/$repo $tag :: $asset)" >&2
  h=$(prefetch_file "$url")
  add "$name" "$(jq -n --arg kind release --arg url "$url" --arg tag "$tag" \
        --arg asset "$asset" --arg h "$h" \
        '{kind:$kind,url:$url,tag:$tag,asset:$asset,sha256:$h}')"
}

url_pin() { # name url
  local name=$1 url=$2 h
  echo "[pin] $name  ($url)" >&2
  h=$(prefetch_file "$url")
  add "$name" "$(jq -n --arg kind url --arg url "$url" --arg h "$h" \
        '{kind:$kind,url:$url,sha256:$h}')"
}

file_pin() { # name path
  local name=$1 path=$2 h
  if [[ ! -f "$path" ]]; then
    echo "[skip] $name — local blob not found at $path (pin pending)" >&2
    return 0
  fi
  echo "[pin] $name  (local: $path)" >&2
  h=$(prefetch_file "file://$path")
  add "$name" "$(jq -n --arg kind file --arg name "$(basename "$path")" --arg h "$h" \
        '{kind:$kind,name:$name,sha256:$h,note:"requireFile — © LocalThunk, supply locally"}')"
}

# ── the pins ──────────────────────────────────────────────────────────────
# Steamodded: the build fetched releases/latest, which has no asset, so it fell
# back to main HEAD — i.e. it was rolling/unpinned. Pin to current main.
gh_pin      steamodded     Steamodded     smods             main
gh_pin      cryptid        MathIsFun0     Cryptid           v0.5.16a
gh_pin      sticky_fingers eramdam        sticky-fingers    main
release_pin amulet         frostice482    amulet            3.5.2   Amulet.zip
release_pin cardsleeves    larswijn       CardSleeves       v1.9.2  CardSleeves-1.9.2.zip
release_pin debugplus      WilsontheWolf  DebugPlus         v1.5.2  DebugPlus.zip
url_pin     base_apk       "https://lmm.shorty.systems/base.apk"
file_pin    balatro_love   "$BALATRO_LOVE"

mkdir -p "$ROOT/nix"
jq -S . <<<"$json" > "$OUT"
echo "[ok] wrote $OUT" >&2
