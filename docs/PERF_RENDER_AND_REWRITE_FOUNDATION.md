# Framerate root-cause + render fix + rewrite foundation

Recovered from the render-perf workflow (wf_eca2c078-ab9). The workflow's probes did
the investigation; only the synthesis output was lost to context-pollution, so the
substance below was salvaged from the agent transcripts and is authoritative.

## Symptom (user-reported, on-device)
- Should run at 120fps. Gets ~60 after round 1, **<20fps with a dozen-plus jokers**.
- Tanks when jokers are **idle** (nothing scoring) → the renderer is the primary cost.
- Tanks **harder while scoring** → the allocation-heavy dispatch + GC piles on.
- "Clunky even with scoring animations off" → frame-time jank.

## Root cause A — per-frame GPU cost, scales O(jokers)
Live draw path is the **SMODS override** `src/dump/SMODS/_/src/card_draw.lua` (a clean
`DrawStep.obj_buffer` loop), NOT `src/dump/card.lua` (that's the no-SMODS fallback).

For ONE idle, plain (no edition/seal/sticker), front-facing joker, EVERY FRAME:
- **Pass 1 (shadow):** `G.shared_shadow:draw_shader('dissolve', shadow_height)` —
  1 setShader bind + 9 uniform `:send()`s + 1 `love.graphics.draw` + 1 setShader clear.
  Condition passes every frame (joker area type `'joker'`, shadows On, not greyed).
- **Pass 2 (center body):** `self.children.center:draw_shader('dissolve')` — same cost.
- Front sprite pass does NOT fire for jokers.

→ **~2 binds + 18 sends + 2 draws/idle card/frame.** ×20 ≈ 40 binds + 360 sends + 40 draws
for cards whose pixels never change. The `dissolve` `time` uniform changes each frame but
for a settled card (dissolve == 0) the OUTPUT is identical frame-to-frame → cacheable.

## Root cause B — per-frame CPU cost, scales O(N log N)
`src/dump/cardarea.lua`:
- `CardArea:move` (every Moveable, every frame) unconditionally calls `align_cards`.
- `align_cards` joker branch (cardarea.lua:563-582): O(N) position recalc, then an
  **O(N log N) `table.sort` every frame**, then (cardarea.lua:600-602) an O(N) rank
  reassign every frame.
  - Note: positions include a per-frame `math.sin(...G.TIMERS.REAL...)` float bob — the
    O(N) position update IS visible work; the **sort + rank reassign are redundant when the
    card set/order hasn't changed** (no drag/add/remove) and can be skipped safely.
- `CardArea:update → handle_card_limit → count_property` (SMODS utils.lua:3190): **2× O(N)
  scans/frame** for G.jokers to recompute slot counts that only change on card/ability change.

## The render fix (quality-preserving, in risk order)

NONE of these change a pixel. Implement lowest-risk first; each needs on-device confirm.

1. **count_property cache** (CPU, zero visual risk). Cache `extra_slots`/`total_slots`/
   `extra_slots_used`; invalidate on card add/remove and ability change. Eliminates 2×O(N)/frame.
2. **align_cards sort/rank skip** (CPU). Skip the `table.sort` + rank reassign when the joker
   set + order is unchanged (dirty-flag on add/remove/drag/reorder). Keep the per-frame sine
   position update (the float). Pixel-identical because the sort yields the same order.
3. **LAZY_SHADER nil-reset refinement** (GPU bind count, pixel-identical). Move the
   `love.graphics.setShader()` nil-reset OUT of `draw_shader` (engine/sprite.lua:123-131) to
   ONE reset per `Card:draw` (card_draw.lua DrawStep loop end + dump card.lua fallback +
   blind.lua). Lets LAZY_SHADER collapse the shadow→center same-`dissolve` S→nil→S ping-pong.
   Halves binds/clears per card. All draw_shader callers must self-reset; verified call graph:
   card.lua, SMODS/_/src/card_draw.lua, blind.lua, misc_functions.lua:2102 stake sprite.
4. **Static-composite cache** (biggest GPU win, highest risk → adversarial verify before ship).
   Cache shadow+center render of a settled card to a per-card FBO, redraw only the animated
   edition pass over it. INVALIDATION MATRIX (every pixel-affecting state — must be complete or
   it diverges): move/T.x/T.y/scale/rotation, juice, tilt, highlight, hover, drag, debuff/greyed,
   edition, sticker, seal, sprite/atlas swap, ability/value change, shadow_height inputs,
   GRAPHICS.shadows setting, resize/fold (FBO size). Editions animate → never cache those layers.
   Skip the cache for cards that juice/tilt/animate every frame (actively scored, hovered).

## Rewrite foundation (Track 2)
- **Parity harness:** scaffold already on disk at `test/oracle-check.sh` (left by a probe).
  Needs: deterministic boot (regen-dump-style nix+Xvfb), seed+forced-hand driver, capture the
  played-hand score from game state, emit golden record `seed -> [hand scores]`. The oracle any
  rewrite must match byte-for-byte.
- **Non-allocating dispatch prototype (in-Lua, the executable spec):** LIFO **context pool**
  (`SMODS._ctx_pool`, size 32, pre-allocated; `acquire_context`/`release_context`, LIFO matches
  the push/pop context-stack nesting so identity checks still work), a joker→context subscription
  index (a context only visits subscribed jokers), and cached per-joker derived values. Target:
  the ~340 throwaway tables/hand (J=8) + ~50 context literals + ~170 `{eval}` wrappers → ~0.
  Replaces SMODS calculate_context/eval_card paths (utils.lua). A/B on GC bytes + scoring wall-time.
- **Rust core spike:** deterministic fixed-step tick, SoA entity/joker store, no GC, fixed-point
  where determinism needs it; port the 10 representative jokers; run through the parity harness to
  get a per-effect port-cost number. 13 archetypes identified; 10 jokers chosen (dropshot,
  chili_pepper, … — covers scaling/custom-lambda, end-of-round self-destruct, big-num/OmegaNum,
  retrigger, RNG-seeded, cross-joker copy, global-state monkey-patch).

## Recommended sequence
Renderer first (fixes the live game, no rewrite): #1 → #2 → #3 (all safe, pixel-identical), then
#4 behind adversarial pixel verification. In parallel, finish the parity harness + land the in-Lua
dispatch prototype (it doubles as the native effect-engine spec). Greenlight the Rust port only
after the harness + the 10-joker spike return a real per-effect cost number.
