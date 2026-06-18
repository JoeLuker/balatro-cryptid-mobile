# Balatro → Native Compose: Engine Port Plan

## 1. Goal

The deliverable is the native Kotlin/Jetpack-Compose rewrite itself — no Lua VM, no `love-android`, no embedded LÖVE runtime ships. Fidelity to the real Balatro (+ the Cryptid mod) is the **acceptance test**, not the architecture: the strategy is to reimplement the whole LÖVE engine faithfully in Kotlin/Compose — the `Moveable`/`Node` transform spine, the `EventManager` timing queue, the runtime object classes (`Card`/`CardArea`/`Blind`/`Tag`/`Back`/`CardCharacter` + their `Particles` children), the `Sprite`/`AnimatedSprite` draw pipeline, the frame-level `Game:draw` compositor, the `UIBox` layout passes, the content-registration + `calculate_context` dispatch, the run-state serialization graph, and the GLSL shader set — then verify each layer against the existing pixel/behaviour oracle (`Oracle.kt`'s 99 cases, `tools/uiref` layout dumps, the `bref_3` reference frames), and only **then** pare back to the minimum that still passes the oracle. The current rebuild is a useful but partial slice: scoring math (`Score.kt`) is logic-faithful and oracle-verified, and the HUD layout (`UILayout.kt`) is geometry-verified at rest, but almost everything animated or interactive is frame-matched, hard-coded to the `bref_3` screenshot, or absent. We build up from the spine, not down from the screenshots.

> Note (vs. the draft): the draft's §1 framed only four "live primitives" as the spine and implied that the `Moveable` upgrade discharged the obligation to port `Card`/`CardArea`/`Blind`/`Tag`/`Back`. It does not — "extends `Moveable`" is one mixin, not the ~5000-line `Card` class, `CardArea`'s seven `align_cards` branches, or the per-class `save`/`load` pair. Those runtime classes now get their own owning phase (P0.5), and the frame compositor, particles, and run-state serialization are now scheduled units rather than implied.

## 2. The Engine Spine

Every renderable, every animation, every interaction in Balatro bottoms out in four live primitives. Until these are **running per-frame** (not frozen, not approximated by an ad-hoc `LaunchedEffect`), nothing above them can be faithful — a card's resting position, a HUD value's bump, a scoring pop, a shop-sign slide, and a blind dissolve are all *outputs of the spine*, not independent effects. The current rebuild skipped the spine for everything except cards-in-hand (which got a real `BalatroSpring`) and hard-coded the rest to reference coordinates. That is the central debt this plan repays.

The spine, in dependency order:

```
GameClock / TimerRegistry  (G.TIMERS: TOTAL, REAL, REAL_SHADER, UPTIME, BACKGROUND; G.FRAMES.DRAW/MOVE)
└── exp_times + SpeedFactor (exp(-50*dt) xy / exp(-60*dt) scale / exp(-190*dt) r; SPEEDFACTOR + ACC fast-forward)
    │
    ├── Transform (T / VT / CT / original_T  — the target vs visible double-buffer)
    │   └── NodeLifecycle (G.ID, FRAME epochs, states{visible/collide/hover/click/drag}, GlobalRegistry/SceneRegistry)
    │       └── SceneGraph (children keyed map, set_container, recursive remove, add_to_drawhash)
    │           └── Moveable
    │               ├── RoleHierarchy (Major/Minor/Glued, get_major w/ FRAME.MAJOR memo, glue_to_major)
    │               ├── AlignmentSystem (type-string 'cm'/'cli'/'bmi' → offset; lr_clamp; self.Mid)
    │               ├── LerpSpringXY / LerpSpringR / LerpSpringScale / LerpSpringWH (per-channel integrators)
    │               ├── JuiceAnimation (juice_up, 0.4s damped sine: 50.8/^3 scale, 40.8/^2 rot)
    │               ├── ShadowParallax (shadow_parrallax.x from room-center distance)
    │               └── MoveOrchestrator (move(): FRAME.MOVE guard → juice→XY→R→Scale→WH→parallax)
    │
    ├── EventManager (5 FIFO queues; fixed 1/60 drain; blocking/blockable gating; triggers immediate/after/ease/condition/before)
    │   └── Event (trigger, delay, timer TOTAL|REAL, ease.ref_table in-place mutation, no_delete, created_on_pause)
    │
    └── ViewportTransform / RoomAndStageObjects (TILESIZE=20, TILESCALE=3.65, TILE_W=20, TILE_H=11.5;
        love.resize aspect-contain; G.ROOM + G.ROOM_ATTACH anchor; logical→pixel = coord * TILESCALE * TILESIZE)
```

Above the spine sit two distinct tiers the spine *drives*. **Tier A — the runtime object classes** (`Card`, `CardArea`, `Blind`, `Tag`, `Back`, `CardCharacter`, each with its own `save`/`load`, dispatch, animation surface, and a child `Particles` instance) extend `Moveable` but are themselves large behavioural files; they are NOT discharged by porting the base `Moveable`. **Tier B — the consumers**: `Sprite`/`AnimatedSprite` (consume `VT` + atlas), the frame-level `Game:draw` compositor, `UIBox` layout passes (`VTInitPass` calls `move_with_major(0)` + `calculate_parrallax`), `CardArea.align_cards` (writes `T.x/T.y/T.r` from `G.TIMERS.REAL`), the whole `state_events.lua` scoring cascade (chained `Event`s), and `Controller` input (hit-tests `G.DRAW_HASH`, dispatches to `node:click/drag/hover`). None of these can be made engine-faithful while the layer below is a constant.

**Why nothing above the spine can be faithful until the spine is live:**

- **`VT`/`T` double-buffer is the foundation of every motion.** The current HUD, stat boxes, and chips/mult readout are statically placed (no `Moveable`); they cannot bump, lean, or settle like the engine because there is no `VT` chasing a `T`. The bump you see is an ad-hoc `Animatable` spring in `RenderDynaText`, not `move_juice`.
- **`EventManager` is the timing spine for the whole scoring cascade.** The current cascade (`RoundPlay`'s `LaunchedEffect` with 140ms/300ms/450ms hard delays) cannot match the engine because the engine's timing is *event-driven and fixed-step* (1/60 drain, `blocking`/`blockable` gating), with `SPEEDFACTOR` fast-forward. Fixed delays diverge the moment a joker inserts a follow-on event.
- **`ViewportTransform` + `RoomAndStageObjects` is what makes positions live.** `PF.*` offsets (e.g. `PLAY_SCORING_Y=3.7925`) are resolved from a frozen oracle dump, not computed from `G.play.T.y` at runtime; they don't move when the consumeable count changes the play-area width. Until `set_screen_positions` runs against a live `ROOM_ATTACH`, layout is pinned to one screenshot.
- **The runtime object classes carry their own non-trivial surface.** `Card` (`set_ability`, `use_consumeable`, `start_dissolve`/`start_materialize`, `draw_shader`, the ability property bag, the child `Particles`) and `CardArea` (`emplace`/`remove_card`/`draw_card_from`/`hard_set_cards`/`parse_highlighted` + seven `align_cards` branches) are the largest behavioural files in `src/dump`. They sit between the base `Moveable` (P0) and every consumer that mutates them (P1 `BlindSystem`, P4 schedulers/lifecycle), so they get a dedicated phase.

## 3. Current Coverage

| Engine Unit | Current fidelity | Kotlin files |
|---|---|---|
| UIBox three-pass layout (`calculate_xywh`/`set_wh`/`set_alignments`) | **live-faithful** (80/80 nodes, worst 5e-5 — geometry-at-rest only) | `ui/UILayout.kt`, `ui/UIBox.kt` |
| UIBox node types (`G.UIT.R/C/T/B/O`) + `Cfg` fields | **live-faithful** | `ui/UIBox.kt` |
| DynaText readout + idle float + value-bump | **partial** (readout/idle-float faithful; value-bump is an *approximate* spring — snap-to-1.22 + `spring(0.36,520)`, not exact `juice_up` — see §5 item 4) | `ui/UIBox.kt` |
| Moveable spring integrator (`move_xy/r/scale/juice`) | **live-faithful** (exact constants; but runs only for hand cards — the per-frame loop/EventManager/SPEEDFACTOR driver is ABSENT) | `ui/Spring.kt` |
| CardArea `align_cards` hand fan (spread/arc/bob/lift) | **live-faithful** (fan unit hardcoded `1.8u`, not `G.CARD_W=2.04878u` → slightly narrow) | `ui/Spring.kt` |
| Card `juice_up` (scored-card pop) | **live-faithful** (but positioned at frozen `PF.PLAY_SCORING_Y`) | `ui/RunScreen.kt` |
| `background.fs` felt shader | **partial** (API≥33 only; API<33 falls back to a static gradient; colours BAKED to Small Blind, `spin_amount=0` — one frozen blind state on a subset of devices; see §5 item 6) | `ui/Felt.kt` |
| `love.resize` room-fit (`uiScaleFor`) | **live-faithful** (repro path forces width-fit = frame-matched) | `ui/UIBox.kt`, `ui/RunScreen.kt` |
| `G.C` colour palette | **live-faithful** (Panel is a measured composite approximation) | `ui/BalatroStyle.kt` |
| m6x11plus glyph rendering (`BTxt` + descent fix) | **live-faithful** | `ui/BalatroStyle.kt` |
| `create_UIBox_HUD` tree (JSON load + binds) | **live-faithful** (static geometry; no Moveable transforms — placement only, not motion) | `ui/HudSpec.kt`, `ui/RunScreen.kt` |
| `create_UIBox_HUD_blind` token panel | **partial** (overlaid separately; chip-scale spring constants approx; text-scale not `VT.scale`) | `ui/RunScreen.kt` |
| HUD contents (round boxes, dollars/chips, buttons, hand readout) | **partial** (statically placed; no Moveable bump/settle dynamics — geometry-at-rest only) | `ui/RunScreen.kt` |
| `set_screen_positions` card-area coords (`PF.*`) | **frame-matched** (oracle dump + `bref_3`; `PLAY_SCORING_Y` is a frozen frame; no live recompute) | `ui/RunScreen.kt` |
| Joker `align_cards` fan distribution | **partial** (simplified dx; `BalatroFloat` bob only, no per-joker `BalatroSpring`, no ticking loop) | `ui/RunScreen.kt` |
| Card atlas extraction (`P_CARDS`/8BitDeck/Enhancers) | **live-faithful** | `ui/CardArt.kt` |
| Joker atlas extraction (Jokers + Cryptid atlases) | **partial** (86/90; 4 Cryptid jokers — fspinner, jimball, wee_fib, wheelhope — have NO atlas entry, a content-availability question, not finish-work; see §5 item 18) | `ui/JokerArt.kt` |
| `BlindChips.png` atlas | **partial** (frame 0 only of 21; Ox row unmapped) | `ui/BlindArt.kt` |
| Stake chip sprite | **partial** (White Chip only) | `ui/StakeArt.kt` |
| `create_UIBox_round_evaluation` cash-out | **partial** (Compose Column/Row, not UIBox tree) | `ui/RunScreen.kt` |
| `create_UIBox_blind_select` / `_blind_choice` | **partial** (UIBox tree via flow interpreter; static blind sprite; no float/outline/debuff-prefix) | `ui/RunScreen.kt` |
| `create_UIBox_shop` | **partial** (FlowRow chrome; no vouchers/boosters; simplified RNG) | `ui/RunScreen.kt` |
| Card drop shadow (`draw_shader` parallax) | **partial** (tinted second pass of the white `c_base` stock silhouette; the 8BitDeck pip OVERLAY is not composited into the mask — an atlas-compositing gap, not purely a shader gap; see §5 item 13) | `ui/RunScreen.kt` |
| `create_UIBox_current_hands` (Run Info) | **partial** (flow interpreter; no tooltip; no `visible` gating) | `ui/RunScreen.kt` |
| Scoring cascade animation (`scoreStep`/`popIndex`) | **partial** (hard delay loop, not EventManager) | `ui/RunScreen.kt` |
| `evaluate_round` reward calc | **logic-only** (correct numbers; instant, no `ease_dollars`) | `ui/RunScreen.kt` |
| Boss blind scoring side (Flint/suit debuff/Wall/Needle/Water) | **logic-only / partial** | `ui/RunScreen.kt`, `game/Blinds.kt` |
| Hand-level / planet system (`HandLevels`/`levelUp`) | **logic-only** | `ui/RunScreen.kt`, `game/Levels.kt`, `game/Hands.kt` |
| `evaluate_play` scoring cascade | **logic-only** (oracle-verified math; no E_MANAGER/juice/sound) | `game/Score.kt` |
| `eval_card` per-card contribution | **logic-only** | `game/Score.kt` |
| `Card:calculate_joker` dispatch | **logic-only** (~45 vanilla + ~80 Cryptid; many gaps) | `game/Score.kt` |
| Scoring `context` (`Sctx`) | **logic-only** (no non-scoring context flags; NOTE: a prior composition cascade `game/Scoring.kt` — Tally/Effects/Context/ScoreRun — was *retired* in favour of `Score.kt`; see §4 P1 caution) | `game/Score.kt`, `game/Scoring.kt` (retired) |
| `FJoker` accumulator state | **logic-only** (callers pre-seed; no run-loop populating events) | `game/Score.kt` |
| `evaluate_poker_hand` + getters | **logic-only** (1:1; Cryptid hand stubs not returned) | `game/Hands.kt` |
| PlayingCard model | **logic-only** (no Blue/Purple seal; no card editions) | `game/Cards.kt` |
| Hand base stats / `G.GAME.hands` | **logic-only** | `game/Hands.kt`, `game/Levels.kt` |
| Deck (shuffle/enhance/seal) | **partial** (non-Balatro RNG; no discard pile/round flow) | `game/Deck.kt` |
| Joker editions (Foil/Holo/Poly scoring) | **logic-only** (affects pricing/scoring only, NOT appearance — needs `Card.draw_shader` + shaders) | `content/Editions.kt`, `game/Score.kt` |
| Oracle / parity harness | **logic-only** (99 cases vs LÖVE baselines) | `game/Oracle.kt` |
| Telemetry (dev infra) | **logic-only** (not a Balatro concept) | `bridge/Telemetry.kt` |
| **Runtime object classes** (`Card`/`CardArea`/`Blind`/`Tag`/`Back` full behavioural surface) | **ABSENT** (only the scoring-relevant data model + base `Moveable` integrator exist; not `set_ability`/`use_consumeable`/`emplace`/seven `align_cards` branches/`parse_highlighted`/per-class `save`/`load`) | — |
| **Particles** (`childParts1/2`, `self.children.particles`) | **ABSENT** (no particle system; soul/holo/edition sparkle + Jimbo character cannot be faithful without it) | — |
| **Game:draw frame compositor** (18-layer ordered draw + two-canvas offscreen→CRT blit) | **ABSENT** (no frame-level layered compositor, no `G.CANVAS`→`G.AA_CANVAS` blit, no dragged-card-drawn-last/screenwipe/cursor top layers) | — |
| **Run-state serialization** (`save_run`/`load` over the live Card/CardArea/Blind tree; `string_packer` `STR_PACK/STR_UNPACK`) | **ABSENT** (distinct from profile persistence) | — |
| EventManager / Event | **ABSENT** | — |
| Moveable VT-lerp for HUD/jokers/blinds | **ABSENT** (only hand cards) | — |
| Controller / HID / FocusNavigator / PointerGestureResolver | **ABSENT** | — |
| Sprite / AnimatedSprite / ShaderPipeline / DrawStepList | **ABSENT** (art is direct atlas-crop Images) | — |
| GLSL→AGSL shaders (dissolve/CRT/foil/holo/poly/negative/voucher/flame/vortex/skew + 11 Cryptid) | **ABSENT** (only `background.fs`→`FELT_AGSL` done) | `ui/Felt.kt` (felt only) |
| Content registry (`P_CENTERS`/pools/`SMODS.GameObject`/`calculate_context`) | **ABSENT** (jokers inline in `calcJoker`) | — |
| PseudoRNG (`pseudoseed`/`pseudohash`/`pseudoshuffle`) | **ABSENT** (hardcoded/simplified seeds) | — |
| Run state machine / shop economy / vouchers / boosters / consumables flow | **ABSENT** | — |
| SaveManager / SoundManager threading model (`love.thread` channels → SoundPool/ExoPlayer worker + DataStore IO coroutine) | **ABSENT** | — |
| Localization (`loc_vars`/`localize`/`G.localization`) | **ABSENT** (minimal HUD `loc()` only) | — |
| Sound system (`play_sound`/`modulate_sound`) | **ABSENT** | — |
| Tutorial system / `OVERLAY_TUTORIAL` flow (`say_stuff`, `DragTarget` registration, `tutorial` event queue) | **ABSENT** | — |
| Main menu / settings / collection / save-load | **ABSENT** | — |

## 4. Port Phases (dependency-ordered)

### P0 — The Spine (foundation; nothing above it can be faithful first)

Port the live primitives every other unit reads each frame. Order within P0 is fixed by the tree in §2.

- **GameClock / TimerRegistry** — NET-NEW. `G.TIMERS` (TOTAL/REAL/REAL_SHADER/UPTIME/BACKGROUND) + `G.FRAMES.DRAW/MOVE` as mutable floats advanced in one `withFrameNanos` loop. TOTAL is `SPEEDFACTOR`-scaled and pause-aware; REAL is raw. **This unblocks everything** — `JuiceAnimation`, `AnimatedSprite`, `DynaText`, all shaders, and the EventManager fixed-step drain read these clocks.
- **SpeedFactorAndTimers / exp_times** — NET-NEW. `SPEEDFACTOR = GAMESPEED + max(0, ACC-2)`, `ACC` accumulates 0.2·GAMESPEED·dt up to 16 during HAND_PLAYED/NEW_ROUND. `exp_times.{xy,scale,r}` + `max_vel` recomputed per frame. Required before the Moveable integrator can be frame-exact.
- **Transform** — NET-NEW. `T`/`VT`/`CT`(=T alias)/`original_T` as mutable holders (not Compose `State` — written every frame). `velocity`, `pinch`.
- **NodeLifecycle + GlobalRegistry** — NET-NEW. `SceneRegistry` singleton replacing `G.I.*`/`G.MOVEABLES`/`G.STAGE_OBJECTS`; deferred-removal queue for safe mutate-during-iterate; `FRAME` epoch ints; `created_on_pause`; `G.STAGE_OBJECT_INTERRUPT` as a registration-suppress flag.
- **SceneGraph** — NET-NEW. `children` as `LinkedHashMap<String,Node>` (both named `h_popup`/`d_popup` and positional keys); recursive `draw`/`set_container`/`remove`; `add_to_drawhash` z-sort hook.
- **Moveable** (UPGRADE of `ui/Spring.kt`) — the spring integrator constants in `BalatroSpring` are already exact and live-faithful; promote it from a per-card helper into the base `Moveable` class with `T`/`VT`, `RoleHierarchy` (Major/Minor/Glued + `get_major` `FRAME.MAJOR` memo), `AlignmentSystem` (type-string parser, `lr_clamp`, `self.Mid`), `JuiceAnimation`, `ShadowParallax`, and `MoveOrchestrator` (the `FRAME.MOVE`-guarded `move()` dispatcher). This is the single highest-leverage upgrade: it converts cards-only motion into universal motion. NOTE: this ports the *base mixin only* — the large runtime classes that extend it (Card/CardArea/Blind/Tag/Back) are P0.5, not here.
- **EventManager + Event** — NET-NEW. Five FIFO queues (`unlock/base/tutorial/achievement/other`), fixed `queue_dt=1/60` accumulator drain decoupled from render frames, `blocking`/`blockable` two-condition removal, front-insert `append_count` index dance, `forced` param, `no_delete`, `created_on_pause`. Triggers: `immediate`/`after`/`ease`/`condition`/`before`. `ease.ref_table` in-place mutation via a `MutableState<Float>` / `KMutableProperty` handle. The `tutorial` queue is ported here but its *flow* is owned by the Tutorial system (P6). **This unblocks the entire `state_events.lua`/`common_events.lua` layer** and replaces every hard-coded delay in `RunScreen.kt`.
- **ViewportTransform + RoomAndStageObjects** (UPGRADE) — `uiScaleFor` is already live-faithful; what is missing is the live `G.ROOM`/`G.ROOM_ATTACH` Moveable anchor and `prep_stage`/`delete_run`/`remove_all`. Make all world coordinates `ROOM_ATTACH`-relative and recomputed, not pinned constants.
- **MainUpdateLoop** — NET-NEW. The `withFrameNanos` driver: advance timers → `E_MANAGER.update(real_dt)` → state `update_*` → `ANIMATIONS.animate(real_dt·SPEEDFACTOR)` → two-pass Moveable (`move(min(1/20,real_dt))` then `update`) → `Controller.update`. The two-pass `FRAME.MOVE` guard is mandatory.

*Verification gate:* re-run the `tools/uiref`/`verify_layout.py` HUD comparison — it must stay 80/80 after the HUD becomes Moveable-driven instead of statically placed. **Scope honesty:** 80/80 is a STATIC-LAYOUT geometry match (positions at rest); passing it after the conversion proves only that nothing moved out of place at rest. It does NOT validate that the bump/settle/lean *dynamics* are faithful. Dynamic-motion fidelity is a separate gate — the `bref_3` + `deploy_diff.sh` rendered-pixel comparison on a *captured mid-animation frame*, added in P4 once the cascade and `ease_*` Events drive motion.

### P0.5 — Runtime Object Classes (the load-bearing actors; between the spine and its consumers)

The five largest behavioural files in `src/dump` extend `Moveable` but are not discharged by porting it. They must exist before any consumer (P1 `BlindSystem`, P4 schedulers/lifecycle, P5 tooltips) can be built faithfully, so they get their own slot immediately after the spine.

- **Card** (`card.lua`, ~5000 lines) — NET-NEW. `set_ability`, the ability property bag, `use_consumeable`, `start_dissolve`/`start_materialize` (drive the dissolve/materialize shaders), `draw_shader` (drives foil/holo/negative/voucher/booster edition passes with correct uniforms/timing), `set_seal`/`set_edition`/`set_sticker`, `flip`/`highlight`, the child `Particles` instance, plus `save`/`load`. This is the runtime caller that P2's authored shaders need before edition/soul visuals can light up.
- **CardArea** (`cardarea.lua`) — NET-NEW. `emplace`, `remove_card`, `draw_card_from`, `hard_set_cards`, `parse_highlighted`, and all seven `align_cards` branches (hand/jokers/consumeables/play/deck/discard/shop variants). The P4 card-transfer schedulers cannot be built faithfully until this exists.
- **CardAreaFactory** — NET-NEW (`game.lua` unit). Builds the six canonical areas (`G.hand`/`G.jokers`/`G.consumeables`/`G.play`/`G.deck`/`G.discard`) with correct `config` (`card_limit`, `type`, `highlighted_limit`). Currently unscheduled in the draft; required before `set_screen_positions` (P4) can resolve any area `T`.
- **Blind** (`blind.lua`) — NET-NEW. `set_blind`, `defeat`, `debuff_card`/`debuff_hand`, boss-effect hooks, `juice_up`, the disable/dissolve animation, plus `save`/`load`. `BlindSystem` (P1) supplies the typed debuff predicates; this class is the runtime that runs them.
- **Tag** (`tag.lua`) — NET-NEW. `set_tag`, `apply_to_run`, `yep`/`nope` animation, `juice_up`, plus `save`/`load`.
- **Back** (`back.lua`) — NET-NEW. Deck-back runtime, `apply`/`trigger_effect`, plus `save`/`load`. Feeds `BackCenter` (P1) `RunModifiers`.
- **Particles** (`particles.lua`) — NET-NEW. The particle system that `Card` and `CardCharacter` instantiate as a child and draw (`card.lua:4931`): `childParts1`/`childParts2`, lifetime/spawn/velocity, draw against `VT`. Drives soul/holo/edition sparkle and the Jimbo character. Absent entirely from the draft; edition/soul visuals are not faithful without it.

*Verification gate:* `Oracle.kt` stays green (99/99) — the scoring data model must survive being re-homed onto the real `Card`/`CardArea` classes. No new pixel gate yet (visuals come online in P2).

### P1 — PseudoRNG + Content Registry + Calculate Dispatch (the data backbone)

The scoring math is already correct but pre-seeded; this phase makes content and randomness *engine-driven and oracle-reproducible*.

- **PseudoRNG** — NET-NEW (replaces hardcoded `deck=20260614L`/`blind*prime` seeds). `pseudohash` rolling-float formula, `pseudoseed` (stateful in `G.GAME.pseudorandom[key]`), `pseudorandom`/`pseudorandom_element`/`pseudoshuffle`, `random_string`, `generate_starting_seed`. **Must be bit-for-bit (`Double` arithmetic, Lua-Xorshift `math.randomseed`+`math.random`, not `java.util.Random`)** or the oracle diverges. Highest-risk unit in this phase.
- **PCentersRegistry + ContentPoolRegistry** — NET-NEW. `G.P_CENTERS` flat map → typed sealed registry; `P_CENTER_POOLS`, `P_JOKER_RARITY_POOLS[1..4]`, `P_BLINDS/TAGS/SEALS/STAKES/CARDS`; `init_item_prototypes` + `load_profile` discovered/unlocked/alerted merge; sort-by-`order`.
- **SMODSGameObject + ContentPoolManager** — NET-NEW. Sealed class tree replacing `Object:extend` metatables; `take_ownership`/`inject_class` become compile-time override/subclass; `insert_pool`/`remove_pool`, `no_pool_flag`/`yes_pool_flag` eligibility predicate; Cryptid `ObjectType` (Food/Meme/M/Epic/Exotic/Tier3) as category tag set.
- **CalculateDispatch** — UPGRADE of `Score.kt`'s `calcJoker`. Replace the single inline `when` with `SMODS.calculate_context(context)` → `calculate_card_areas` → `eval_card` → typed `*Definition.calculate(card, context)`. Sealed `Context` hierarchy replacing the `Sctx` flag bag; re-entrant `post_trigger` with a context stack; retrigger loop as explicit mutable trigger list. **CAUTION — do not re-introduce a retired abstraction:** a prior composition cascade (`game/Scoring.kt`: Tally/Effects/Context/ScoreRun) was already attempted and *retired* in favour of `Score.kt` being the sole engine (per its own header). The new sealed `Context` must be a thin dispatch shape over `Score.kt`, NOT a revival of the retired Effects/ScoreRun composition. Delete `game/Scoring.kt` as part of this unit rather than letting it shadow the design. **This unblocks** moving all ~150 vanilla + ~445 Cryptid jokers off the inline switch.
- **Concrete content classes** — NET-NEW: `JokerCenter` (rarity pools, `blueprint_effect`), `ConsumableCenter` (+`ConsumableType` registry, Cryptid `Code` set, `hidden`/`soul_rate`), `VoucherCenter` (`requires` chain, inline `used_vouchers` checks), `BackCenter` (`apply`→`RunModifiers`; wires P0.5 `Back`), `EditionSystem` (UPGRADE — scoring done, add `get_card_limit_key`), `SealSystem` (add Blue/Purple), `EnhancementSystem` (DONE logic; gate field), `BlindSystem` (typed debuff predicates; consumed by P0.5 `Blind`), `TagSystem` (`config.type` mini-dispatch + `yep`/`nope`; consumed by P0.5 `Tag`), `StakerSystem` (`above_stake` chain), `StickerSystem` (`obj_buffer` iterable registry), `SuitRankPokerHand` (UPGRADE — wire Cryptid `CRY_*` hands that `Hands.evaluate` currently stubs).
- **CryptidObjectRegistration** — NET-NEW. Each Cryptid item → `data object` implementing the per-type interface; `pools` → `Set<Category>`; `dependencies.items` → build-time flags.
- **LocVarsLocalization** — NET-NEW (replaces minimal `loc()`). `loc_vars`/`generate_ui`/`process_loc_text`, `info_queue` nested-tooltip model, `G.localization.descriptions[set][key]`.

*Verification gate:* `Oracle.kt` must stay green (99/99) after dispatch is rerouted through the registry, and after RNG replaces the hardcoded seeds the deal/shop sequences must match a fresh LÖVE seed dump.

### P2 — Sprite / AnimatedSprite / DrawStepList + Frame Compositor + Shaders (the visible substrate)

With the spine driving `VT` and the runtime objects (P0.5) supplying `draw_shader`/`start_dissolve` callers, the draw pipeline can consume them.

- **SpriteAtlasRef + QuadAddressing** — UPGRADE of `CardArt.kt`/`JokerArt.kt`/`BlindArt.kt`/`StakeArt.kt`. Generalize one-off atlas crops into `Atlas`(ImageBitmap+px/py) + `Quad`(srcRect). Finish the *mechanical* partials: 21-frame blind animation, stake progression 2-8, Ox row. **Content caveat:** the 4 missing Cryptid jokers (fspinner, jimball, wee_fib, wheelhope) have NO atlas entry in the shipped Cryptid atlases — confirm whether the art exists at all before treating it as porting work; if absent upstream it is a content-availability gap, not a quad-addressing task.
- **Sprite + PrepDraw + DrawHashAndBounds** — NET-NEW. `prep_draw` imperative push/translate/rotate → `Canvas.withSave { translate; rotate; scale }` closure; `draw_self` vs `draw_steps` walk; debug `draw_boundingrect`.
- **DrawStepList + multi-layer card-face composite** — NET-NEW. `List<DrawStep>` (shader/shadow_height/send/no_tilt/other_obj/ms/mr/mx/my); Cryptid `edshader` appended pass. Includes compositing the 8BitDeck pip OVERLAY onto the `c_base` stock so the card-face (and its shadow silhouette) is the real multi-layer image, not a single base crop — this is the half of the drop-shadow fix that is compositing, not shading (see §5 item 13).
- **AnimatedSprite + AnimationSystem** — UPGRADE. `floor(FPS·(REAL-offset)) % frames` frame index; `float` bob into `VT`; `G.ANIMATIONS.animate`. This drives animated blind chips and the shop sign.
- **GameDrawCompositor (`Game:draw`)** — NET-NEW. The frame-level 18-layer ordered composite that the draft entirely omitted: CANVAS render at `CANV_SCALE` → SPLASH_BACK → nodes → moveables → uiboxes → cardareas → cards → attention uiboxes → SPLASH_FRONT → OVERLAY_TUTORIAL → OVERLAY_MENU → debug → alerts → **dragged/focused card drawn last (on top)** → popups → achievement notify → screenwipe → cursor; then the two-canvas offscreen+blit pattern (render into `G.CANVAS`, CRT post-process blit into `G.AA_CANVAS`). Owns the dragged-card-drawn-last rule, the screenwipe and cursor top layers, and the offscreen→CRT→onscreen pipeline. High-complexity unit; without it nothing above per-object `Sprite.draw` owns the screen composite.
- **ShaderPipeline + ShaderRegistry_Base** — NET-NEW (only `background.fs`→`FELT_AGSL` is DONE). Re-author 19 base GLSL → AGSL `RuntimeShader` (API≥33), GLES fallback below: `dissolve` (+ shared burn-edge/noise lib; driven by `Card.start_dissolve`/`start_materialize` from P0.5), `CRT` (wire `GRAPHICS.crt`/`bloom`; consumed by the compositor's post-process blit above), `foil`/`holo`/`hologram`/`polychrome`/`negative`/`negative_shine`/`booster`/`debuff`/`played`/`voucher`/`gold_seal`/`flash`/`flame`/`splash`/`vortex`/`skew`. Rewrite LÖVE `effect()`/`position()` entry points and `extern`→`uniform`; inject `love_ScreenSize`; `shadow_parrallax` `VT` mutate must be a local copy.
- **ShaderRegistry_Cryptid** — NET-NEW. 11 Cryptid shaders (astral/ultrafoil/noisy/glitched/glitched_b/oversat/mosaic/gold/blur/glass/m); resolve `PRECISION` macro (AGSL has no `mediump` → implicit precision); `blur.fs` may need multi-pass.

### P3 — Controller / Input (interaction on top of the live tree)

- **HIDManager** — NET-NEW (touch primary; gamepad optional). `MotionEvent` source → HID flags; `setVisible` no-op.
- **PointerGestureResolver** — NET-NEW. `L_cursor_press`/`release`, `cursor_down`/`up` distance/duration, `MIN_CLICK_DIST`/`click_timeout`; the one-frame `L_cursor_queue` defer is likely unnecessary in Compose.
- **Controller** — NET-NEW. `update(dt)` pipeline; `get_cursor_collision` over the z-ordered hit-test tree (replaces `G.DRAW_HASH`); intent slots; `locks`/`locked`; dispatch to `node:click/drag/hover/release`.
- **HoverInput / DragInput** — UPGRADE/NET-NEW. `h_popup`/`d_popup` named-child lifecycle; cursor → node-local via container inverse-rotation; Cryptid `force_popup` hook. The dragged card's draw-on-top is owned by the P2 compositor.
- **FocusNavigator + GamepadAxisMapper** — NET-NEW, *deferrable for touch-first* (stub for mobile; keep for TV/gamepad).

### P4 — Run Loop, Scoring Cascade Animation, Run Serialization, Shop Economy (the game, now event-driven)

This is the big frame-matched→live conversion phase, all gated on P0's EventManager, P0.5's runtime classes, and P1's dispatch.

- **ScreenLayoutManager / set_screen_positions** — UPGRADE (frame-matched→live). Replace `PF.*` constants with live `G.jokers/hand/play/deck.T` (now real `CardArea`s from P0.5) and `CAI` widths derived from `G.CARD_W` (and fix `SpringHand`'s `1.8u`→`2.04878u` fan unit). Recompute on consumeable/hand-size change.
- **StateMachine + GameStateEnum + StopUseGate** — NET-NEW. `G.STATE`/`G.STAGE` enum, `STATE_COMPLETE` one-shot made explicit, `update_*` per-state; `STOP_USE` reference-counted lock via `AtomicInt`.
- **EaseValueScheduler / CardAreaStatusText** — UPGRADE. `ease_value`/`ease_colour`/`ease_chips`/`ease_dollars`/`ease_discard`/`ease_hands_played`/`ease_ante`/`ease_round`/`ease_background_colour[_blind]` as real `ease` Events (replaces instant cash-out + frozen felt colours). `card_eval_status_text`/`update_hand_text` floating popups + `juice_up`.
- **CardMoveScheduler / DrawToHandController / PlayCardAction / DiscardAction** — NET-NEW. `draw_card` (`before`-trigger Events), six `draw_from_X_to_Y` staggered fan-outs (adds deal/discard fly animations the rebuild lacks); exact reverse-iteration draw order for RNG parity. Built on P0.5 `CardArea.draw_card_from`/`emplace`.
- **ScoringOrchestrator** — UPGRADE (frame-matched→live). Replace `RoundPlay`'s hard-delay `LaunchedEffect` with the four-phase `evaluate_play_*` cascade chained via Events / a suspend state machine (`SCORING_COROUTINE` → suspend points); O(N²) `other_joker` loop; `Cryptid.ascend` big-number wrap. Card pops driven by `Card.juice_up` against the live play `CardArea` `VT`.
- **RoundLifecycleController / RoundEvalCashout** — UPGRADE. `new_round`/`end_round`/`win_game` transitions (call `card:calculate_banana`/`perishable` on the P0.5 `Card`); animated cash-out rows (`add_round_eval_row`) replacing the partial Compose Column.
- **RunStateSerialization (`save_run`/`load`)** — NET-NEW, distinct from profile persistence. The `string_packer` equivalent (`STR_PACK`/`STR_UNPACK` semantics) over the live `Card`/`CardArea`/`Blind`/`Tag`/`Back` object tree — each P0.5 class's `save`/`load` pair wired into a single in-progress-run snapshot. Kotlin uses its own serialization format (e.g. kotlinx-serialization), not the LÖVE byte format, but must round-trip the full live graph. `RoundLifecycleController`'s `save_run` calls depend on this unit; it is the dependency the draft's profile-only `ProgressAndSave` left dangling.
- **ShopActionCallbacks + UIStatePollCallbacks + SortCallbacks** — UPGRADE/NET-NEW. `buy_from_shop`/`sell_card`/`reroll_shop`/`toggle_shop`/`select_blind` with `CONTROLLER.locks`; per-frame poll callbacks (`HUD_blind_visible`, `flash`, `slider`, `option_cycle`) → `derivedStateOf`.
- **ProgressAndSave / ProfileAndUsageTracking** — NET-NEW. Profile/settings/metrics persistence (NOT run-state — that is `RunStateSerialization` above). Coroutine/IO + DataStore replacing `love.thread`; throttled writes; seeded/challenge stat guard.
- **SaveLoadThreadingModel** — NET-NEW. The background-thread channel infrastructure the draft referenced only via its callers. LÖVE's `engine/save_manager.lua` (`save_request`/`load_channel` `love.thread` channels) → a DataStore IO coroutine/`Dispatchers.IO` worker that both `RunStateSerialization` and `ProgressAndSave` post to; `SAVE_MANAGER.channel:push` becomes a channel/Flow send. (The audio half of the threading model is `SoundThreadingModel` in P6.)

*Verification gate:* `bref_3` + `deploy_diff.sh` rendered-pixel comparison on captured mid-cascade frames (this is the dynamic-motion gate P0 deferred); `save_run`→`load` round-trips the live object graph identically; `Oracle.kt` stays green.

### P5 — Remaining Screens & Widgets (UIBox catalog completion)

All gated on P0 (UIBox already live), P0.5 (runtime classes for tooltips), P2 (shaders + compositor) + P3 (input).

- **UIBoxHost / UIBoxDynContainer / UIBoxButton** — UPGRADE. World-anchored overlays (`major`/`bond`/`offset`); fix the button-hover-colour gap on the absolute-positioned path.
- **GenericOptionsPanel / TabsWidget / SliderWidget / ToggleWidget / OptionCycleWidget / TextInputWidget** — NET-NEW. Modal shell, tabs, and settings controls (`TextInputController` → `BasicTextField`).
- **HudPanel / BlindSelectPanel / BoosterPackPanel / RoundEvaluationPanel / EndOfRunPanel / RoundScoresRow** — UPGRADE partials → full UIBox trees via `UILayout` absolute renderer (not the flow interpreter); animated blind sprite, deferred-slot `SnapshotStateList` for round-eval.
- **ShopPanel / DragTargetWidget** — UPGRADE. Sign slide-in via Event; drag-to-sell via `dragAndDropTarget`. `DragTargetWidget` registers with `OVERLAY_TUTORIAL` — that registration is consumed by the Tutorial system (P6).
- **CollectionPanels / HighScoresPanel / RunInfoPanel / SettingsPanel / MainMenuPanel / CardUnlockPanel / NotifyAlertWidget / BlindPopupTooltip** — NET-NEW. The currently-ABSENT screens; `LazyVerticalGrid` + paging; unlock sequence as a coroutine (cancellable `Job` for `joker_unlock_table` guard).
- **CardCharacter** — NET-NEW. Jimbo wrapper (extends the P0.5 runtime-object tier; instantiates a `Particles` child like `Card`); `say_stuff` recursive Event loop (drives tutorial speech consumed by P6).

### P6 — SoundSystem, Tutorial flow, Cryptid display, dev tooling

- **SoundSystem** — NET-NEW. `play_sound`/`modulate_sound` via SoundPool/ExoPlayer; ambient closures sample a `StateFlow`; `SMODS.Sound.replace_sounds`.
- **SoundThreadingModel** — NET-NEW. The audio half of the engine threading model (`engine/sound_manager.lua`, `sound_request` `love.thread` channel): the SoundPool/ExoPlayer worker loop the `play_sound` caller pushes to. Pairs with P4's `SaveLoadThreadingModel`.
- **TutorialSystem / OVERLAY_TUTORIAL flow** — NET-NEW. The tutorial overlay/flow as a subsystem: consumes the `tutorial` event queue (P0), `DragTargetWidget`'s `OVERLAY_TUTORIAL` registration (P5), and `CardCharacter.say_stuff` (P5); drives the tutorial speech/highlight sequence and the `OVERLAY_TUTORIAL` draw layer (P2 compositor). Distinct engine surface the draft only ported piecemeal.
- **JokerDisplayLayer** — NET-NEW. Built-in live-stats overlay; `ref_table`/`ref_value` → typed property access.
- **NumberFormatting / ColourUtilities / MathUtilities** — UPGRADE/DONE-ish. `number_format` scientific switch at `E_SWITCH_POINT`, big-number guard; colour blends already partially in `BalatroStyle.kt`.

## 5. Frame-matched → Live upgrades (explicit conversion list)

Every place the current rebuild is pinned to the `bref_3` reference or an ad-hoc effect, and what it must become:

1. **`PF.*` absolute offsets** (`ui/RunScreen.kt`) — `JOKERS_X/Y`, `PLAY_X/W`, `PLAY_SCORING_Y=3.7925`, `HAND_X/Y`, `DECK_X`, the referenced-but-unused `PLAY_RESTING_Y`. Resolved from an oracle dump + frozen frame. → driven by live `set_screen_positions` against `G.ROOM_ATTACH` + `CardArea.T` (real `CardArea` from P0.5), recomputed when play width changes (P4 `ScreenLayoutManager`).
2. **`PLAY_SCORING_Y` is a frozen scoring-frame measurement** — scored cards are placed at a constant, not at `G.play.T.y` driven by the live cascade. → P4 `ScoringOrchestrator` positions via the play `CardArea`'s live `VT`.
3. **Scoring cascade timing** (`RoundPlay` `LaunchedEffect`, 140/300/450ms) — fixed delays standing in for the event queue. → P0 `EventManager` + P4 four-phase `evaluate_play_*` chain.
4. **DynaText value-bump** (`RenderDynaText` snap-to-1.22 + `spring(0.36,520)`) — close-but-not-exact approximation of `move_juice`; this is why §3 now labels the bump **partial**, not live-faithful. → exact `JuiceAnimation` damped sine (50.8/^3) once DynaText sits on a real `Moveable` (P0).
5. **HUD blind-token chip scale** (`animateFloatAsState` 0.001→0.5, applied as text scale) — approximate spring, wrong target. → engine `blind_chip_UI_scale` spring on the Moveable's `VT.scale` (P0/P4).
6. **Felt shader baked to Small Blind** (`FELT_AGSL`, `spin_amount=0`, fixed colours, API≥33 only with a static-gradient fallback below) — one frozen blind state on a subset of devices; this is why §3 now labels Felt **partial**, not live-faithful. → live `ease_background_colour[_blind]` per blind/state with `spin` and colour-from/to (P4 `EaseValueScheduler`), plus a real GLES fallback path or an explicit documented degrade.
7. **Boss `DYN_UI` colour** (hudBlind hard-coded fallback Mult colour for all bosses) — → `G.P_BLINDS[boss].boss_colour` from registry (P1) into dynamic `DYN_UI` state.
8. **Repro width-fit branch** (`maxWidth/ROOM_W` in `UIBox.kt`/`RunScreen.kt`) — forces a specific 16:9 screenshot match. → drop in favour of the live aspect-contain `uiScaleFor` path for all devices.
9. **Joker idle motion** (`BalatroFloat` infinite sine, fixed seed, no ticking loop) — → per-joker `BalatroSpring`/`Moveable` with `move_xy`/`move_r` so jokers lean and settle (P0 Moveable + P4 layout).
10. **HUD/stat-box/readout static placement** (no Moveable; the 80/80 geometry gate validates rest-position only, not motion) — → Moveable-backed `VT`-lerp so HUD elements bump and settle like the engine (P0), verified by the P4 mid-animation pixel gate.
11. **Cash-out instant numbers** (`buildCashOut`, no `ease_dollars`) — → counted-up rows via `ease_dollars` Events (P4 `RoundEvalCashout`).
12. **Discard/deal instant** — no fly animations. → `CardMoveScheduler` staggered `draw_card` Events (P4) over P0.5 `CardArea.draw_card_from`.
13. **Card drop-shadow mask** (tinted second pass of the white `c_base` stock; the 8BitDeck pip OVERLAY is not composited into the silhouette) — this is two fixes, not one: (a) composite the pip overlay into the card-face/silhouette via the multi-layer card-face composite (P2 `SpriteAtlasRef`/`DrawStepList`), and (b) drive the shadow pass through the real `draw_shader`/`dissolve.fs` (P2 shaders) called by `Card.draw_shader` (P0.5). The draft attributed the whole fix to shaders; the shape problem is the compositing half.
14. **Shop/blind-select/run-info via the flow interpreter** (`RenderUI`/Container, `Row/Column + spacedBy`) — edge-case-divergent. → the absolute-positioned `UILayout` renderer used by the HUD (P5).
15. **Simplified shop/deck RNG** (`blind*prime`, `deck=20260614L`) — → `pseudorandom(seed+key)` chain (P1 `PseudoRNG`).
16. **`SpringHand` fan unit `1.8f` hardcoded** — narrower than engine. → derive from `G.CARD_W=2.04878u` (P4).
17. **`FJoker` accumulators pre-seeded by callers** — only valid for oracle tests. → populated by live non-scoring context events (`end_of_round`/`setting_blind`/`selling_card`/`rental`/etc.) once P1 dispatch + P4 run loop exist.
18. **Joker atlas "86/90" treated as finish-work** — the 4 missing (fspinner, jimball, wee_fib, wheelhope) have NO atlas entry in the shipped Cryptid atlases. → resolve as a content-availability question first (does the art exist upstream?), then either source/extract it or document it as an unshippable Cryptid subset; NOT a mechanical quad-addressing task (P2 `SpriteAtlasRef`).
19. **Edition affects pricing/scoring only, not appearance** — no `Card.draw_shader` runtime to drive the foil/holo/poly passes. → P0.5 `Card.draw_shader`/`start_dissolve` wired to the P2 edition shaders + P0.5 `Particles` for the sparkle.

## 6. Render substrate (what a logic port does NOT give you)

A faithful `Score.kt`/`Hands.kt` is correct math and zero pixels. The finite list of substrate work that logic cannot cover:

- **GLSL→AGSL shaders** — 19 base + 11 Cryptid GLSL programs must be re-authored as AGSL `RuntimeShader` (API≥33) or GLES 3.0 fallback. LÖVE's `effect(colour,texture,texture_coords,screen_coords)`/`position(mat4,vec4)` entry points and `extern` uniforms have no AGSL analogue and must be rewritten; `love_ScreenSize` injected; AGSL has no `mediump` so Cryptid's `PRECISION` macro resolves to implicit precision. **State: only `background.fs`→`FELT_AGSL` (`ui/Felt.kt`) is done, and only partially — live-faithful on API≥33 but a static-gradient fallback below, baked to one blind state. All card-edition, dissolve, CRT, flame, vortex, skew, and every Cryptid shader are ABSENT** — edition currently affects only pricing/scoring, not appearance (the runtime driver `Card.draw_shader` is P0.5).
- **Frame compositor** — the `Game:draw` 18-layer ordered composite + two-canvas offscreen→CRT blit is a render-substrate unit that no per-object `Sprite.draw` provides. Layer order (dragged card last, then popups/screenwipe/cursor) and the `G.CANVAS`→`G.AA_CANVAS` CRT post-process are correctness, not polish: get the order wrong and overlays/cursor/drag z-fight. **State: ABSENT (P2 `GameDrawCompositor`).**
- **Particle system** — `Card`/`CardCharacter` each own a `Particles` child driving soul/holo/edition sparkle and the Jimbo character. No Compose primitive supplies this; it is its own lifetime/spawn/draw system against `VT`. **State: ABSENT (P0.5 `Particles`).**
- **Glyph rasterizer** — m6x11plus with `includeFontPadding=false`, `LineHeightStyle.Trim.Both`, and the `DESCENT_OVERHANG_EM=0.0625` descender-clip fix; `squish` non-uniform x-scale via `Paint.textScaleX`; `TEXT_OFFSET` baseline nudge for pixel-grid parity. **State: `BTxt` in `ui/BalatroStyle.kt` is live-faithful.** This is the one substrate piece already solid.
- **dt / spring timing** — `exp(-50/-60/-190·dt)`, `maxVel=70·dt`, the `min(1/20,real_dt)` move cap, fixed `1/60` EventManager drain, dual TOTAL/REAL clocks, `SPEEDFACTOR` fast-forward. **State: the integrator constants (`ui/Spring.kt`) are live-faithful, but they only run for hand cards; the per-frame loop, the EventManager fixed-step, and the dual-clock `SPEEDFACTOR` driver are ABSENT (P0).**
- **Colour / gamma** — `G.C` as float RGBA → Compose `Color`; `FilterQuality.None` nearest-neighbour everywhere (matches LÖVE). The `Panel` colour is a *measured rendered composite* (#2E3A3C, not raw `G.C.BLACK` #374244) because the HUD shadow composites on top and is not headlessly observable; `DYN_UI` must stay mutable (boss fights re-theme it). **State: `BalatroStyle.kt` palette is live-faithful with the documented Panel approximation; the dynamic `DYN_UI` re-theming flow is ABSENT.**
- **Atlas/quad addressing** — `newQuad` srcViewport → `drawImage(srcOffset,srcSize)`; per-frame `setViewport` mutation for animation; multi-layer card-face composite (pip overlay onto `c_base` stock). **State: static crops are live-faithful for cards (`CardArt.kt`); partial for jokers (86/90, with 4 having no atlas entry at all), blinds (frame 0 of 21), stakes (White only); the live `Sprite`/`AnimatedSprite` quad-mutation pipeline and the multi-layer composite are ABSENT (P2).**
- **Save/sound threading** — LÖVE's `string_packer` + `save_manager`/`sound_manager` `love.thread` channel workers are engine substrate with no Compose analogue: a DataStore IO coroutine (save) and a SoundPool/ExoPlayer worker (sound) the gameplay callers post to. **State: ABSENT (P4 `SaveLoadThreadingModel` + `RunStateSerialization`; P6 `SoundThreadingModel`).**

## 7. Pare-back

We build the engine up faithfully, then trim — and the **pixel/behaviour oracle is the regression gate that licenses every cut**. The oracle has three teeth, all already in the tree: `Oracle.kt`'s 99 scoring cases vs LÖVE baselines (behaviour), the `tools/uiref`/`verify_layout.py` layout dump (80/80 HUD nodes, pixel-exact geometry **at rest**), and the `bref_3` reference frames + `deploy_diff.sh` (rendered-pixel, including the mid-animation frames added in P4 to cover motion). Nothing is removed unless all three stay green. **Be explicit about what each tooth proves:** 80/80 is rest-geometry only; dynamic-motion fidelity is licensed by the `bref_3` mid-cascade pixel gate, not by the layout dump.

**When to pare back:** only *after* a slice is engine-faithful and oracle-verified — never as a shortcut to get there (a frame-matched constant is not "pared back," it is debt; see §5). Concretely, after each phase passes its gate, sweep for what fidelity proved unnecessary.

**What is a candidate to trim, and how:**

- **The dual UIBox renderer.** `UIBox.kt` carries two interpreters: the absolute-positioned `UILayout` (verified) and the Compose-flow `RenderUI/Container` (used by shop/blind-select/run-info, divergent on spacing). Once P5 moves every screen onto `UILayout`, **delete the flow interpreter** — keeping it is preserved dead code the layout oracle would otherwise have to bless twice.
- **The retired composition cascade.** `game/Scoring.kt` (Tally/Effects/Context/ScoreRun) was already dead-ended in favour of `Score.kt`. Delete it as part of P1 `CalculateDispatch` rather than carrying a retired abstraction in-tree that invites re-introduction.
- **Tutorial overlay** (if not a target). If the touch-first build ships without the tutorial, `OVERLAY_TUTORIAL`, the `tutorial` event queue, and `CardCharacter.say_stuff`'s tutorial branch can be stubbed behind a flag — the behaviour oracle never exercises them. Keep `CardCharacter` itself only if Jimbo appears elsewhere (e.g. main menu).
- **Desktop/platform-only units.** `GamepadAxisMapper`, `FocusNavigator`, the on-screen `create_keyboard_input`, `WINDOW`/video settings, Steam/Discord/HTTP-scores, `GET_DISPLAYINFO`, `nuGC`, `CANV_SCALE` HiDPI canvas — trim or stub on the touch-first build behind feature flags; the behaviour oracle never exercises them, so removal is free. Keep `Controller`'s focus stubs only if TV/gamepad is a real target.
- **Engine perf tricks with no JVM payoff.** `ARGS`/`RETS` GC-reduction pools, the `L_cursor_queue` one-frame input defer (Compose dispatches input and layout in the same frame), `G.DRAW_HASH` dirty-region tracking (recomposition subsumes it for non-Canvas paths) — port the *semantics* (z-order hit-test) but drop the LÖVE-specific machinery. Verify the gesture oracle still classifies tap-vs-drag identically.
- **The `STATE_COMPLETE`/`FRAME.MOVE` epoch guards** can be simplified to a monotonic frame counter on the `GameLoop` rather than per-node epoch ints — but only after the layout + scoring oracles confirm no node double-updates per frame.
- **Speculative content surface.** Cryptid's full ~445-object registry and 30 GLSL shaders ship only the subset reachable by the run loop and verified visually; unreferenced `ObjectType` categories or shaders with no live binding stay out until a screen needs them. The 4 atlas-less Cryptid jokers (§5 item 18) stay out until their art availability is resolved.

The discipline: **faithful-then-trim, oracle-gated, never trim-to-avoid-faithful.** A cut that drops an oracle case below green is reverted, not the oracle relaxed.