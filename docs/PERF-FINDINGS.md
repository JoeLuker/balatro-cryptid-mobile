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
| LONG_DT logcat print gated behind the Debug Logging setting — **correction 2026-06-10**: the rate-limit previously claimed here was never actually in the tree (hunter error; no `LONG_DT_RATELIMITED` marker existed anywhere). Realized instead as a `G.SETTINGS.telemetry_log` gate when telemetry went default-off | `apply_telemetry_toggles` |
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

## Tier 0 — infra patches (landed 2026-06-11)

All four patches build-verified clean; deployed to device (build stamp `0611-1042.24cfd8`).

| Marker | Summary | Measured benefit |
|---|---|---|
| `JIT_OPT_RAISE` | Raises LuaJIT compile budgets (maxtrace=4000, maxmcode=16384, hotloop=28) via `pcall(jit.opt.start(...))` in main.lua | Not yet measured on device; prevents trace eviction on large mod load |
| `EVQ_BURST_ATTRIB` | Times `Event:handle` calls when `EVQ_PROF` global is live (set by android-telemetry.lua); buckets slow handlers by `source:linedefined` for per-window EV_SLOW events | Working: `EV_SLOW src=button_callbacks.lua:3238 ms=5754` confirmed first use |
| `EVQ_COMPACT` | Replaces `table.remove(v, i)` in hot `EventManager:update` loop with identity-mark + O(n) two-pointer compaction | e_manager drops from ~14ms (initial transition burst) to ~0.11ms steady-state after UIBox animation completes — but see caveat below |
| `UI_FUNC_THROTTLE` | Phase-staggered `(G.FRAMES.MOVE + self.ID) % 3 == 0` func polling per UIElement; `_ft_seen` first-fire guarantee; `instant_func` escape hatch | Not yet isolated; contributes to UI update cost reduction |

### First on-device draw-call baseline (2026-06-11, ante 1 BLIND_SELECT)

**⚠ n_ui column is `n_ui_total` (raw `#G.I.UIBOX`) — see measurement-gap note below.**

| State | `e_manager` ms | `shsw_avg` | `dc_avg` | `uiboxes` ms | fps | `n_ui_total` |
|---|---|---|---|---|---|---|
| SPLASH | 0.38–0.46 | 12–96 | 7–49 | 0.04–0.08 | 124–125 | 0 |
| MENU (idle) | 0.09–0.10 | 24 | 31–32 | 0.69–0.75 | 124–125 | 5 |
| BLIND_SELECT (start_run burst) | 14.48 | 40 | 49 | 0.67 | 5 | 94 |
| BLIND_SELECT (initial) | 15.21 | 770 | 625 | 2.56 | 61 | 88 |
| BLIND_SELECT (settling) | 0.14 | 614 | 597 | 2.60 | 69 | 49 |
| BLIND_SELECT (settling) | 0.11 | 508 | 544 | 2.02 | 69 | 22 |
| BLIND_SELECT (settled) | 0.11 | 455 | 516 | 1.72 | 77 | 11 |

**Measurement gap (discovered 2026-06-11):** `n_ui_total` conflates two populations
with different draw-loop treatment. The uiboxes timer brackets only the structural loop
(game.lua:3011), which excludes `attention_text` boxes, `parent`-owned boxes, and several
overlay singletons. The 88→11 settling sequence drops ~77 boxes, almost all of them
transient `attention_text` animation overlays from run-init tag/blind notifications —
they are excluded from the uiboxes codepath and therefore contribute ~0ms to the
`uiboxes` timer. The timer's 0.84ms drop over that sequence (2.56→1.72ms) reflects
only the handful of structural boxes removed, not the 77 animation boxes.

Consequence: the "linear scaling with n_ui" claim and the "0.03ms per-element" figure
in the original note are artifacts of correlating `n_ui_total` against a timer that
measures a strict subset. The uiboxes cost has a large fixed floor (~1.7ms at settled
BLIND_SELECT) driven by a small number of structural root boxes with deep trees, plus a
small marginal per-element cost (~0.01ms from the settling delta). This is not
per-element shader bind at uniform cost — it is a few heavy roots dominating.

**Telemetry fix applied (2026-06-11):** telemetry now emits `n_ui_s` (structural count,
matching the game.lua:3011 filter) and `n_ui_total` separately. Future baselines should
correlate `uiboxes` ms against `n_ui_s`. The `n_ui → shsw_avg` correlation noted below
also needs re-evaluation once `n_ui_s` data is available.

**The `shsw_avg` draw-call story stands but needs sharper data.** `shsw_avg=455` at
settled BLIND_SELECT with `n_ui_s` unknown but small means the shader switch count is
dominated by a few deep UIBox trees, not evenly spread across all registered boxes.
SpriteBatch work should target the heavy structural roots (blind_select panel, HUD, card
areas) rather than all UIBoxes uniformly.

**Identified slow event handlers (all `EV_SLOW` from 10:45–10:46 session):**
- `functions/button_callbacks.lua:3238` → `G:start_run(args)` — 5754ms, expected (full run init)
- `game.lua:3521` → `create_UIBox_blind_select()` — 260ms, the Cryptid-loaded blind select UI build
- `game.lua:1556` → 33.7ms at MENU transition (mod UI build)
- `functions/button_callbacks.lua:3081` → 6.9ms
- `game.lua:3515` → `save_run()` — 3.2ms, expected

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
14. ~~`SMODS.get_card_areas` module cache~~ — **REJECTED by audit 2026-06-10**:
    the one hot call site (`'jokers'` inside the per-joker scoring loop) is
    already hoisted outside the loop (`SCORING_AREAS_CACHED`, state_events.lua:821).
    The remaining call sites are once-per-hand or once-per-round-end — not
    per-frame. `cry_beta` is unrelated (Nostalgic Deck blind substitution; no
    `get_card_areas` variant exists).
    ~~telemetry wrapper micro-costs~~ — **REJECTED by measurement 2026-06-10**:
    `tel()` fires at 0.45 calls/s (132 events over 296s in a real session) —
    two orders of magnitude below frame rate; allocation and concat cost are
    invisible at this rate.
    ~~deploy-push asset stripping~~ — **DONE 2026-06-10** (`strip_en_us_assets`
    in build.sh): 7 non-English fonts + non-en locale files from 3 dirs +
    gamecontrollerdb.txt stripped before zip. game.love 81 MB → 47 MB (−34 MB
    compressed; 64 MB freed uncompressed). All removals safe: font loader guards
    with getInfo, SMODS locale loading is by exact name with no dir scan,
    loadGamepadMappings returns false on missing file without error.

## Memory leak audit — SELECTING_HAND heap growth (2026-06-10)

**Observed signature**: heap grows ~1 MB/s (158→203 MB over 45s) during
SELECTING_HAND while game-object counts fall. Retained Lua data; reaches
~290 MB after ~30 min; resets on restart; fps degrades 120→28. Present
across builds. GAMESPEED=4, Talisman omeganum backend, Cryptid mod active,
score numbers animating constantly.

Audit confirmed all 10 named project perf caches are safe (no unbounded
insertion; correct eviction or bounded size). Two root-cause patches applied:

### NF_BIG_CACHE — `talisman.lua` `number_format` override

**Marker**: `NF_BIG_CACHE`  
**Applied by**: `apply_nf_big_cache()` in build.sh, called from `patch_mods_dir()`

**Root cause**: Talisman's `number_format` override called `to_big(num)` on every
invocation to check `.str`, but `to_big` (`OmegaNum:new`) unconditionally allocates
a fresh table. The `.str` cache was written on that throwaway and discarded
immediately — it could never be re-read. At GAMESPEED=4 with score numbers
animating at 120 fps, this produced a fresh OmegaNum table per digit per frame.

**Fix**: Split into three paths:
1. Plain Lua `number` below `E_SWITCH_POINT` — no allocation (fast-path return via
   original `nf`).
2. Plain Lua `number` that needs formatting — single `to_big` allocation, no cache
   (value is ephemeral anyway).
3. Already-a-Big input — cache on the original object under `'str_'..notation` key.
   Different notation strings get different field names; no invalidation needed when
   user switches notation.

**Expected impact**: eliminates the dominant OmegaNum allocation during sustained
scoring display. Exact rate depends on how many score strings are rendered per frame
that arrive as plain numbers vs pre-boxed Bigs.

### LETTER_TABLE_REUSE — `engine/text.lua` DynaText inner loop

**Marker**: `LETTER_TABLE_REUSE`  
**Applied by**: `apply_letter_table_reuse()` in build.sh, called after
`apply_dynatext_glyph_cache` (anchors on the `cached_glyph(...)` text from that prior
patch — ordering is load-bearing).

**Root cause**: `DynaText:update_text()` rebuilds `strings[k].letters` from scratch on
every call. For each character: fresh `let_tab` table, fresh `dims={x,y}` sub-table
(unconditional), fresh `offset={x,y}` sub-table (when no old_letter). During score
animation at 120 fps with multi-digit numbers, this is the dominant per-frame Lua
allocation source: 3 tables × (digits per string) × (strings updating) × 120 fps.

**Fix**: When `old_letter` exists and `old_letter.char == c` (same character at same
position), reuse `old_letter` as `let_tab` directly. Mutate `dims.x`/`dims.y` in-place
instead of allocating a new `{x,y}` table. Preserve `offset` reuse (was already
conditional). Set `scale` only when nil (preserves animation interpolation state on
reuse). Allocates fresh tables only on first appearance or character-position change.

**Interaction with DYNATEXT_GLYPH_CACHE**: the GPU `love Text` object is still fetched
via `cached_glyph(self.font.FONT, c)` and written into `let_tab.letter` each call —
correctness preserved, GPU object still shared.

**Expected impact**: during steady-state animation of a fixed-length score display
(e.g. "1.23e456" not changing digit count), per-`update_text` allocation drops from
O(chars) tables to near zero. Digit-count changes (e.g. a score crossing a power of
ten) still allocate — unavoidable, but rare vs. per-frame.

### Remaining contributor (not patched)

**E_MANAGER event queue**: `update_hand_text` in `common_events.lua:541-606` enqueues
`trigger='before'` events with `delay=0.8` whose `func` closures upvalue `vals`
containing Big chips/mult objects. These are pre-existing vanilla-architecture events
(user-triggered on hand selection change, not per-frame) — impact is low compared to
the above two, and fixing it requires touching the event architecture. Deferred.
