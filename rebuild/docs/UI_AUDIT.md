# Rebuild UI Audit — vanilla taxonomy vs. coverage vs. crutches

Authoritative reference for auditing the native-Compose rebuild against vanilla Balatro.
Counts extracted from `src/Balatro.love` (`game.lua`, `functions/UI_definitions.lua`, `resources/`).
Legend: ✅ faithful-ish · ◑ partial / hand-built-but-present · ❌ missing · ⚠️ present but **wrong / a crutch**.

> Honest framing: the session added **content rows** to a UI that is missing its **information layer**
> (tooltips), its **meta layer** (collection / options / scores), a **real main menu**, and proper
> **card-container behavior** for most board regions. "Faithful" was only ever pixel-checked for the HUD.

## 0. Teardown progress (2026-06-26, verified on emulator-5560)
| Crutch | Status |
|---|---|
| No detail tooltip (couldn't read cards) | ✅ FIXED #165/#167 — tap joker/consumable → name+ability+actions |
| Material dev launcher (main menu) | ✅ FIXED #168 — real extracted `main_menu_tree` on felt |
| Settings/Stats Material dialogs | ✅ FIXED #173 — Balatro Options overlay (audio + stats) |
| Consumables = name-on-cardback | ✅ FIXED #170 — real tarot/spectral sprites (full Moveable CardArea still TODO) |
| Collection = Material demo grid | ✅ FIXED #175 — felt overlay, tabs, real content sprites |
| `play` parity harness (deck-select regression) | ✅ FIXED #166 |

**Still crutches:** shop sell-strip · hand-built cash-out (EvalRow) · deck/stake picker (vs customize_deck) ·
tag-art placeholder · play/discard sub-label stubs · `onRunInfo` stub · some joker-art placeholders ·
editions don't render on playing cards · consumeables/jokers not full engine CardAreas (rendering fixed, physics not).

---

## 1. Content taxonomy (vanilla base; Cryptid layers hundreds more on top)

| Category | Vanilla | Rebuild | |
|---|---|---|---|
| Jokers | 150 | ~150 (parallel effort) | (not my lane) |
| Tarots | 22 | 19 | ◑ (3 are engine/joker-gated) |
| Planets | 12 | 12 | ✅ |
| Spectrals | 18 | 15 | ◑ |
| Vouchers | 32 | 23 | ◑ |
| Tags | 24 | 12 | ◑ |
| Bosses | 28 | 28 | ✅ |
| Decks | 15 | 14 | ◑ (Plasma = scoring) |
| Stakes | 8 | 8 | ✅ (effects), ⚠️ selector hand-built |
| Enhancements | 8 | 7 | ◑ (Lucky = engine) |
| Editions | 5 | 5 | ◑ shaders only on jokers, not playing cards |
| Seals | 4 | 4 | ✅ |
| Booster packs | 5 fam × 3 sizes | present | ◑ one generic pack-open screen |

## 2. Card model & poker hands
- 52-card base: 4 suits × 13 ranks; per-card enhancement + edition + seal — ✅ modeled
- 12 poker hands incl. 3 secret, each level-able — ✅

## 3. Rendering layer
- **Shaders: 7 / 19.** Have: dissolve, foil, holo, polychrome, negative, negative_shine, felt.
  Missing: **skew (card 3-D tilt)**, **debuff**, **flame**, flash, gold_seal, hologram, played, splash,
  vortex, voucher, booster. ❌
- **Particle system (`particles.lua`): none.** ❌ (bg particles, score particles, win confetti)
- **Animated sprites (`animatedsprite.lua`)**: not ported. ❌
- 80 sounds (17 SFX + 1 music bundled), 68 atlases, 8 fonts.

## 4. Screen structure — 6 board CardAreas

| CardArea | Vanilla | Rebuild |
|---|---|---|
| `hand` | CardArea | ✅ real CardArea/Moveable (the one faithful region) |
| `play` | CardArea | ◑ Moveable, partial |
| `jokers` | CardArea | ◑ render, not a verified CardArea |
| `consumeables` | CardArea | ⚠️ **label list, not a CardArea; held items show name-on-cardback, not the real sprite** |
| `deck` | CardArea | ⚠️ count only, no pile |
| `discard` | CardArea | ⚠️ count only, no pile |

## 5. Screen structure — 55 UIBox panels

**Play HUD:** `HUD` ✅ (pixel-checked) · `HUD_blind` ✅ · `buttons` ✅ · `current_hands` ❌ (only in run-info)

**Round flow:** `blind_select`/`blind_choice`/`blind_tag` ◑ · `blind_popup` ◑ · shop ◑ · `round_evaluation`/`round_scores_row` ⚠️ hand-built · `game_over` ◑ · `win` ◑ · arcana/celestial/standard/buffoon/spectral packs → ◑ one generic pack screen

**Overlays / popups — mostly missing:**
- `detailed_tooltip` ❌ **— no way to read a joker/card's ability (most damning gap)**
- `hand_tip` ❌ · `card_alert` ❌ · `notify_alert` ❌ · `card_unlock` ❌ · `deck_unlock` ❌
- `options` / `settings` / `generic_options` ⚠️ replaced by a launcher-only Material audio dialog
- `highlight` ◑

**Collection / meta — mostly missing:**
- `your_collection` **+ 12 sub-browsers** (jokers/tarots/planets/spectrals/vouchers/tags/blinds/boosters/decks/editions/enhancements/seals) ❌ — only a single joker grid
- `high_scores` / `online_high_scores` ⚠️ custom stats dialog ≠ this
- `main_menu_buttons` / `splash` / `profile_button` ⚠️ **replaced by a Material3 dev launcher ("Balatro Native") — fully non-faithful**
- `customize_deck` ⚠️ replaced by a hand-built deck/stake picker
- `tutorial` / `sandbox` / `demo_cta` ❌ (out of scope)

**States (`G.STATES`):** SELECTING_HAND, HAND_PLAYED, DRAW_TO_HAND, NEW_ROUND (round sub-states), PLAY_TAROT
(consumable targeting), SHOP, BLIND_SELECT, ROUND_EVAL, GAME_OVER, 5 pack states, MENU, SPLASH, TUTORIAL,
SANDBOX, DEMO_CTA.

---

## 6. Crutches to rip out (hand-built stand-ins, not faithful ports)

These mimic vanilla without being it; they should be replaced by the real extracted `create_UIBox_*`
trees / engine CardAreas, then deleted.

| Crutch | Where | Replace with |
|---|---|---|
| **Material3 dev launcher** ("Balatro Native", Manage/Play buttons) | `MainActivity.kt` whole `setContent` | port `create_UIBox_main_menu_buttons` + `splash` |
| **Settings / Stats AlertDialogs** (Material3) | `MainActivity.kt` | port `create_UIBox_options`/`settings` + `high_scores` |
| **Consumables = label list** (not a CardArea; name-on-cardback) | `RunScreen.kt` ~3108 / consumable tap render | a real CardArea with the consumable sprite |
| **Sell strip** ("not a Balatro UIBox; only way to offload jokers") | `RunScreen.kt:3552` | interactive jokers CardArea (sell via card click, vanilla) |
| **Cash-out hand-built panel + EvalRow/EvalRowView** | `RunScreen.kt:3304, ~3477` | the `round_evaluation` extracted tree end-to-end |
| **Deck/stake picker** (Compose Column + ◀▶ stepper) | `RunScreen.kt` DeckSelectScreen | `create_UIBox_customize_deck` + stake select |
| **Deck/discard = numbers** | HUD render | real pile CardAreas |
| **Tag art = tinted placeholder square** | `HudSpec.kt:587` | tag sprite atlas crop |
| **Shop card art box hand-built** | `RunScreen.kt:3568` | (no UIBox equiv — keep, but verify geometry) |
| **`onRunInfo`/`onOptions` = stubs** | `RunScreen.kt:2552` | wire to real panels |
| **play/discard sub-label = empty stub** | `RunScreen.kt:2737, 2754` | `SMODS.hand_limit_strings` |
| **Some joker art = name placeholder** | `JokerArt.kt:21,157,213` | bundle/crop the missing atlas cells |
| **Card editions don't render on playing cards** | `CardFace` | apply edition shaders to playing cards too |

## 7. Biggest gaps, ranked by "how badly it breaks the game"
1. **No detailed tooltip** — can't read what jokers/cards do → effectively unplayable.
2. **Non-faithful main menu / landing** — first thing you see is wrong.
3. **Board regions aren't real CardAreas** (consumeables/jokers/deck/discard) — board doesn't behave like Balatro.
4. **No Collection / Options / High-scores** — the entire meta layer.
5. **Rendering: 7/19 shaders, no particles, no card 3-D skew** — everything looks flat/static.
