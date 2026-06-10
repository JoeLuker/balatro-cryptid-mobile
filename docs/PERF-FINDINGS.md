# Performance findings — verified, ranked, measurement-first

From the six-path optimization hunt (draw, update, scoring, memory-alloc,
platform, UI), every finding adversarially spot-checked against the code.
Baseline reality: 63–76 FPS in-run, e_manager ~0.2ms, thermal 0 at idle —
targets are **headroom, battery, scoring throughput, and GC-spike avoidance**
(GC here is manual+budgeted, so allocation churn = deferred frame spikes).

**Rules of engagement** (learned the hard way today):
1. Nothing ships without a before/after measurement. Alloc-pressure findings
   can be measured **on this machine** (desktop smoke runs identical Lua —
   `collectgarbage('count')` deltas are platform-independent); only GPU/frame
   findings need the phone, and the phone is touched only when Joe says so.
2. Changes go through `build.sh` appliers or tracked files — never raw edits
   to untracked `src/dump/` (unrevertable; see the setShader incident).
3. `just test && ` smoke before anything else.

## Already applied (verified in code — no action)

| Finding | Marker/evidence |
|---|---|
| Dead `copy_table` in Cryptid calculate | `CRY_DEAD_COPY_FIXED` |
| Flip Side `find_joker` per-pass cache | `G._cry_flip_side_active` (overrides.lua:154) |
| SMODS.Events loop guards | `CRY_EVENTS_GUARDED` |
| `get_card_areas` hoist in state_events | `SCORING_AREAS_CACHED` |
| Talisman 1GB `collectgarbage('count')` guard removed | `TAL_GC_DEAD_REMOVED` |
| CARD_CALC_COUNTS incremental counter + plain time format | talisman.lua:741/745 |
| Scoring context-table hoist | `OTHER_KEY_HOISTED`/`CTX_TABLE_HOISTED` |
| DARK_EDITION sin dedup + EDITION base cache | game.lua:2668-76 |
| `FPS_CAP = 60` on Android | globals.lua:67 |
| CRT uniform sends gated on `crt>0 or bloom>1` | game.lua:3163 |
| HTTP channel pop gated on `F_HTTP_SCORES` | game.lua:2649 |
| hand chip/mult double `update_text` dedup | `apply_hand_update_text_dedup` |
| hand_level `no_recalc` | `apply_hand_level_no_recalc` |
| `localize('k_lvl')` cache | `apply_lvl_prefix_cache` |
| shader_time per-card cache | 98846f8 + ARGS guard simplify |
| enh_cache metatable hoist, MOVEABLES ipairs, exp_times cache | 0c01843 |
| align_cards 6 sort closures → named function | `SORT_CLOSURE_HOISTED` (0.72 KB/1kf) |
| juice_up rotation table alloc → direct pick | `JUICE_ROT_HOISTED` (~0.3 µB/call) |
| LONG_DT logcat write rate-limited to once/5s | `LONG_DT_RATELIMITED` |
| parse_highlighted: dead double hand-evaluation removed + option tables hoisted + joker-merge scratch | `PARSE_HL_LEAN` (307.9→165.9 KB/selection-cycle, −46%; the dead `get_poker_hand_info` call was a full O(hand²) eval discarded every call) |
| card_eval_status_text: config table → local, 16 dead `.type` writes removed | `CES_CONFIG_ELIDED` (below noise floor of the 286 MB-gross scoring window; see refutation note below) |

## Reverted — do not reapply

- **setShader bind elision** (skip rebinds between same-shader draws):
  corrupted ALL card rendering. `Shader:send` is not batch-aware; the per-draw
  unbind/rebind IS the geometry flush that scopes per-card uniforms. The valid
  shape, if ever needed, is **uniform-value caching** (skip redundant `:send`s
  of unchanged values), not bind elision.

## Rejected by verification

- `remove_nils(G.ANIMATIONS)` waste — **code doesn't exist** (hunter error).
- Edition DrawStep iterating with nil edition — already guarded in vanilla.
- `pseudoseed` `string.format('%.13f')` → `x % 1` — **semantics change**: the
  13-decimal rounding is part of the PRNG; replacing it desyncs every seeded
  run and saved stream. Never apply.

## Tier 1 — complete (2026-06-10)

All four items resolved; 1–3 applied (see Already applied), 4 rejected:

- **Item 2 correction**: the listed "cache + invalidate" shape was replaced by
  a module scratch table wiped per call — same allocation win, zero staleness
  risk (no invalidation sites to miss). Bonus found during implementation: the
  Cryptid lovely patch left vanilla's `get_poker_hand_info` call computing a
  full discarded hand evaluation on every parse — removing it was most of the
  −46%.
- **Item 3 refutation**: the "config/vars → module scratch" shape is UNSAFE —
  `config` escapes into the deferred E_MANAGER event closure (attention_text
  reads `config.scale` when the event fires); a shared scratch would alias
  every queued scoring popup. Applied the safe reduction instead (plain local
  + dead-write removal). The `localize{vars={...}}` arg tables were left
  alone: retention analysis of localize not done, win too small to justify it.
- **Item 4 rejected (N/A)**: premise is texture_scaling=1; Joe runs
  texture_scaling=2 (settings.jkr), where mipmaps are wanted. No-op on the
  target device — revisit only if 1x ever becomes the shipped config.
  (Measured context: 259 MB texture memory across 112 images at 2x.)

**Bench**: `test/perf/alloc-bench.sh <save-dir>` — real-save boot, three
synchronous selection batches (nuGC-quiet, RNG-seeded; batch 1 is JIT-cold,
compare like batches), gross-allocation scoring window, texmem readout.
Scoring window grosses ~286 MB/hand — sub-100 KB findings are invisible
there; use the selection metric or a targeted micro-bench.

## Tier 2 — high value, needs an audit + measurement first

8. **`to_big` fast-path below 1e15** — talisman.lua to_big + 22 hot card.lua
   call sites: at Joe's normal chip scale every comparison allocates OmegaNum
   heap tables (`to_big(a) < to_big(b)` = 2 allocs); 550+/hand. Biggest
   scoring-throughput candidate. Risk: operators must tolerate plain-number
   operands — audit + test across the 1e15/1e300 boundaries. Measure:
   heap delta across a scoring pass (works in desktop smoke + sandbox).
9. **evaluate_poker_hand / get_X_same table reuse** —
   `misc_functions.lua:412-431/661-684`: 12 result sub-tables + O(hand²)
   `curr` tables per evaluation, fired on every selection change. Reuse
   requires confirming results don't escape the call frame (initial read says
   they don't; verify `parse_highlighted` + `evaluate_play_intro`).
10. **DrawStep layer short-circuit** — `card_draw.lua:512-14`: 21
    `check_conditions` per card per layer; bail on layer mismatch before the
    conditions pairs-loop. (Skip the bitmask variant — invalidation risk not
    worth it yet.)
11. **screen_scale frame-constant precompute + stickers-loop gate** —
    `sprite.lua:107` (two distinct constants per frame) and
    `card_draw.lua:320` (8-entry pairs scan per card; gate behind a
    `_has_any_smods_sticker` flag set in set_ability).

## Tier 3 — measure before deciding

12. **Card hover popup cache** (`card.lua:4872-74`): full UI-tree rebuild per
    hover start — one-frame jank on description open; directly relevant to our
    tap-and-hold descriptions. Dirty-flag cache across set_ability/edition/
    seal. Medium risk (stale tooltips if a mutation site is missed).
13. **DynaText per-glyph `newText` cache** (`text.lua:118-122`): 300-380 GPU
    text objects/sec during scoring animations. Cache keyed by (font, char).
14. `SMODS.get_card_areas` module cache (mind Cryptid `cry_beta` variant);
    telemetry wrapper micro-costs; deploy-push asset stripping (deploy-time
    only; needs a nativefs asset-load audit).
