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
| ~~`FPS_CAP = 60` on Android~~ — **was never true** (hunter error, like the remove_nils entry): main.lua defaults G.FPS_CAP to 500 and nothing set it; device telemetry showed 240 fps at menus. Fixed 2026-06-10: cap at panel refresh rate | `FPS_CAP_DISPLAY` (globals.lua Android block) |
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

8. ~~`to_big` fast-path below 1e15~~ — **DONE 2026-06-10 in re-scoped form**
   (`CES_SIGN_FAST`). The global fast-path is UNWORKABLE and must not be
   attempted: LÖVE's LuaJIT has no 5.2-compat — `plain < Big` errors before
   the metamethod is consulted (verified empirically in the smoke runtime),
   and scoring routinely compares small values against huge stored Bigs, so a
   to_big that sometimes returns plain numbers crashes on the first mixed
   comparison. `__eq` is worse: mixed types return false silently. The safe
   shape is site-level type-aware helpers that never mix operand kinds.
   Shipped: 14 hot sign/zero-check sites (8 per-trigger in
   card_eval_status_text + 6 per-tick in the HUD chip/mult updaters) →
   `ces_is_neg/ces_lt_negp/ces_nonzero` with cached Big constants.
   Differential-tested against real OmegaNum across the 0/-0.01/1e15/1e300
   boundaries in plain and Big forms. Isolated alloc (GC stopped): 16,417 →
   1.0 KB per 30k checks; ~300 KB less garbage per scored hand. Remaining
   to_big sites are cold (once-per-round state_events checks, unlock
   conditions, display) — not worth the audit surface.
9. ~~evaluate_poker_hand / get_X_same table reuse~~ — **DONE 2026-06-10**
   (`GET_X_SAME_LEAN_V2`), but re-scoped twice during implementation:
   - vanilla `evaluate_poker_hand` (misc_functions.lua:412) is DEAD CODE —
     SMODS overrides it (overrides.lua:1721) with a registry: one func per
     PokerHandPart + one evaluate per registered hand. Alloc attribution
     (bench profile phase): the `_2/_3/_4/_5/_all_pairs` parts — five
     `get_X_same` calls per evaluation — were ~63 KB/selection-cycle.
   - "table reuse" is UNSAFE: qualifying group tables escape into results and
     become the scoring hand. Shipped shape instead: O(n) count-first rewrite
     allocating ONLY qualifying groups. Differential-tested identical to
     vanilla over 5000 randomized hands (membership, member order, result
     order, id<1 exclusion).
   - Isolated bench (GC stopped): 9.5 → 1.6 KB gross/evaluation (−83%),
     3.4× faster. Game-level batch-3 median: 196 → 144 KB/cycle.
   - Measurement gotcha for posterity: LuaJIT allocation sinking makes
     non-escaping garbage invisible in simple micro-traces, and per-run trace
     blacklisting makes game-level numbers bimodal (~144 vs ~379 KB/cycle on
     identical builds) — always run the bench 3× and compare medians of like
     batches, and stop the GC when measuring gross allocation.
10. ~~DrawStep layer short-circuit~~ — **REJECTED by measurement 2026-06-10**.
    Premise stale: live SMODS already checks `self.layers[layer]` FIRST in
    check_conditions (card_draw.lua:35) before the conditions loop. Measured
    (bench drawtime phase): Card:draw totals 0.53 ms/frame across ~34 calls —
    including the actual shader draws — so the dispatch overhead a
    steps-per-layer precompute could shave is ≤0.1–0.2 ms/frame (~1% of frame
    budget) on a build already at 97–130 FPS on device. Below action
    threshold.
11. ~~screen_scale precompute + stickers-loop gate~~ — **REJECTED /
    already-done 2026-06-10**. screen_scale precompute landed as
    `G._DRAW_SCREEN_SCALE` (game.lua Game:draw). The stickers premise is
    stale: the live SMODS 'stickers' DrawStep (card_draw.lua:284) checks two
    fields directly — there is no 8-entry pairs scan to gate.

## Tier 3 — measure before deciding

12. **Card hover popup cache** — measured 2026-06-10 (bench hover phase):
    joker description open = 3.14 ms + ~440 KB allocation. Phase breakdown:
    ability_table 0.14 + popup_def 0.23 + UIBox INSTANTIATION ~2.8 ms (88%).
    The doc's dirty-flag cache targets the wrong layer (the cheap definition
    phases) and its invalidation is intractable anyway — jokers mutate
    ability state inside calculate without going through set_ability, so any
    set_ability/edition/seal dirty flag serves stale scaling tooltips.
    **Deferred**: the remaining lever is making UIBox instantiation itself
    cheaper, which is a layout-engine project, not a cache. Item 13 shipped
    instead (below) — it was the content-addressable share of this cost.
13. ~~DynaText per-glyph `newText` cache~~ — **DONE 2026-06-10**
    (`DYNATEXT_GLYPH_CACHE`): love Text objects shared per (font, char) —
    immutable after creation (verified: both draw sites are pure draws; no
    DynaTextEffect registered in this modpack). Joker description open
    3.14 → 2.50 ms (−20%); 300-380 GPU text objects/sec during scoring → ~0
    steady-state (not visible in Lua heap metrics — GPU object churn).
14. `SMODS.get_card_areas` module cache (mind Cryptid `cry_beta` variant);
    telemetry wrapper micro-costs; deploy-push asset stripping (deploy-time
    only; needs a nativefs asset-load audit).
