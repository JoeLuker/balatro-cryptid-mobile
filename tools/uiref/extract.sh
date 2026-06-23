#!/usr/bin/env bash
set -euo pipefail
# Regenerate the real Balatro HUD tree as JSON (hud_tree.json): stage the VANILLA
# create_UIBox_HUD source from the base-game archive (Cryptid doesn't touch the HUD), then run the
# capture-stub extractor. The Kotlin UIBox interpreter loads the JSON so the HUD is Balatro's ACTUAL
# definition, not a hand-transcription.
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$DIR/../.." && pwd)"
unzip -o -j "$ROOT/src/Balatro.love" functions/UI_definitions.lua -d "$DIR" >/dev/null
mv -f "$DIR/UI_definitions.lua" "$DIR/UI_definitions.vanilla.lua"   # derived, gitignored
lua "$DIR/extract.lua"
# Stage every extracted tree into the app assets the Kotlin interpreter loads from.
ASSETS="$ROOT/rebuild/app/src/main/assets/ui"
mkdir -p "$ASSETS"
for j in hud_tree shop_tree shop_card_ui \
              pack_arcana_tree pack_spectral_tree pack_standard_tree pack_buffoon_tree pack_celestial_tree \
              round_eval_tree game_over_tree win_tree \
              blind_small_tree blind_big_tree blind_boss_tree; do
  cp -f "$DIR/$j.json" "$ASSETS/$j.json"
done
echo "staged trees -> $ASSETS"
