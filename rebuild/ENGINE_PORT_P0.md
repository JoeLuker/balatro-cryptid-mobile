# P0 — The Engine Spine: per-unit tickets

Drill-down of Phase P0 from [ENGINE_PORT_PLAN.md](ENGINE_PORT_PLAN.md). These are the live primitives
every other unit reads each frame; until they run per-frame, nothing above them can be faithful.
Order is fixed by the dependency tree in the plan's §2. New code lands in a new package
`systems.balatro.engine` (mirrors LÖVE's `engine/`). Pure-logic units are compile/run-verified
standalone with kotlinc (nix `kotlin-2.3.21`), no Android build:

```
cd rebuild/app/src/main/kotlin && \
  "$(ls -d /nix/store/*kotlin*/bin)/kotlinc" systems/balatro/engine -include-runtime -d /tmp/engine.jar && \
  "$(ls -d /nix/store/*kotlin*/bin)/kotlin" -cp /tmp/engine.jar systems.balatro.engine.EngineSpineCheckKt
```

Status legend: ☐ todo · ◐ in progress · ☑ done (verified).

---

### P0-T1 GameClock / TimerRegistry — NET-NEW — ☑
- **Source:** `game.lua` Game:update timer block (lines ~2616–2666) + exp_times/move_dt (~2824–2828).
- **Depends on:** nothing (root).
- **Target:** `engine/GameClock.kt`
- **Build:** the five `G.TIMERS` (REAL, TOTAL, REAL_SHADER, UPTIME, BACKGROUND) + `G.FRAMES.MOVE`;
  `real_dt`; the `SPEEDFACTOR` model (`isRun&&!paused ? GAMESPEED : 1`, then `+ max(0,|ACC|-2)`); the
  `ACC` ramp (`min(ACC + dt·0.2·GAMESPEED, 16)` during HAND_PLAYED/NEW_ROUND, else 0, reset on state
  change); `TOTAL += dt_paused·SPEEDFACTOR` while REAL/UPTIME/BACKGROUND/real_dt use raw dt; the
  per-frame `exp_times` (`exp(-50/-60/-190·real_dt)`) and `move_dt = min(1/20, real_dt)`.
  State-machine enum is P4 — `advance()` takes `isRun`/`handPlayedOrNewRound`/`state` flags for now.
- **Accept:** REAL accumulates raw dt; TOTAL freezes when paused; ACC ramps to 16 and lifts
  SPEEDFACTOR; `get("REAL"/"TOTAL")` resolves for Event timers. (EngineSpineCheck case 1.)

### P0-T2 Event + EventManager — NET-NEW — ☑
- **Source:** `engine/event.lua` (full).
- **Depends on:** P0-T1 (reads `G.TIMERS[timer]`).
- **Target:** `engine/EventManager.kt`
- **Build:** `Event` with triggers immediate/after/ease/condition/before; `blocking`/`blockable`;
  `delay`; `timer` (REAL/TOTAL, defaulting to REAL when created-on-pause); `no_delete`;
  `created_on_pause` pause-skip. Ease: `percent=(end-now)/(end-start)` 1→0, mapped by an extensible
  `EaseTypes` registry (lerp = identity; back-ease c1/c2/c3 hooks present), `ref = func(p·start + (1-p)·end)`
  via get/set lambdas (the Kotlin form of `ref_table[ref_value]`). `EventManager`: five FIFO queues
  (unlock/base/tutorial/achievement/other) drained at fixed `1/60`, decoupled from render frames;
  blocking gate; the `append_count` front-insert index dance; `pause_skip`; completed+time_done removal;
  `clear_queue` honoring `no_delete`.
- **Accept:** an `after` event fires at its delay; an `ease` interpolates monotonically to end_val and
  completes; a blocking event defers a later blockable event in the same queue until it completes;
  fixed-step drain advances at most one batch per crossed 1/60. (EngineSpineCheck cases 2–4.)

### P0-T3 Transform — NET-NEW — ☑
- **Source:** `engine/moveable.lua` (T/VT/CT/original_T, velocity, pinch).
- **Depends on:** none (data holder); consumed by Moveable.
- **Target:** `engine/Transform.kt`
- **Build:** mutable `T` (target x,y,w,h,r,scale) and `VT` (visible) holders — plain mutable floats,
  NOT Compose `State` (written every frame); `CT` alias, `original_T`, `velocity`, `pinch`.
- **Accept:** compiles; used by P0-T6.

### P0-T4 NodeLifecycle + GlobalRegistry — NET-NEW — ☑ (collision/popup parts deferred to P3 input)
- **Source:** `engine/node.lua` + `G.I.*`/`G.MOVEABLES`/`G.STAGE_OBJECTS` registration in `globals.lua`.
- **Depends on:** none.
- **Target:** `engine/Node.kt`, `engine/SceneRegistry.kt`
- **Build:** `G.ID`/`FRAME` epoch ints; `states{visible,collide,hover,click,drag}`; a `SceneRegistry`
  singleton with a deferred-removal queue (safe mutate-during-iterate); `created_on_pause`;
  `STAGE_OBJECT_INTERRUPT` registration-suppress flag.
- **Accept:** register/deferred-remove round-trips without concurrent-mod hazards.

### P0-T5 SceneGraph — NET-NEW — ☑
- **Source:** `engine/node.lua` children/`set_container`/`remove`, `engine/moveable.lua` `add_to_drawhash`.
- **Depends on:** P0-T4.
- **Target:** `engine/Node.kt` (same file as T4).
- **Build:** `children` as `LinkedHashMap<String,Node>` (named + positional keys); recursive
  `draw`/`set_container`/`remove`; `add_to_drawhash` z-sort hook.
- **Accept:** recursive remove detaches subtree; draw order stable.

### P0-T6 Moveable — UPGRADE of `ui/Spring.kt` — ◐ (class + areas + JOKER cards engine-driven via CardArea; hand cards next)
- **Source:** `engine/moveable.lua` (`move`, `move_xy`/`move_r`/`move_scale`/`move_juice`, `juice_up`,
  RoleHierarchy `get_major`, AlignmentSystem, ShadowParallax).
- **Depends on:** P0-T1 (clock/exp_times/move_dt), P0-T3 (Transform), P0-T5 (scene graph).
- **Target:** `engine/Moveable.kt` (promote `BalatroSpring`; leave `ui/Spring.kt`'s `SpringHand`
  composable consuming it during transition).
- **Build:** promote the (already exact) spring integrator into the base `Moveable` class with T/VT;
  Major/Minor/Glued `RoleHierarchy` (`get_major` with `FRAME.MAJOR` memo, `glue_to_major`); the
  `AlignmentSystem` (type-string `'cm'`/`'cli'`/`'bmi'` parser, `lr_clamp`, `self.Mid`); `JuiceAnimation`;
  `ShadowParallax` (x from room-center distance); `MoveOrchestrator` (`FRAME.MOVE`-guarded `move()`).
  NOTE: base mixin only — Card/CardArea/Blind/Tag/Back are P0.5.
- **Accept:** a Moveable's VT lerps to T with the exact constants; HUD stays 80/80 once it becomes
  Moveable-driven (the `tools/uiref`/`verify_layout.py` rest-geometry gate).

### P0-T7 ViewportTransform + RoomAndStageObjects — ☑ (engine/Room.kt; HUD still reads PF — wiring is the rewire)
- **Source:** `game.lua` `love.resize`/room transform; `set_screen_positions` (`functions/common_events.lua`).
- **Depends on:** P0-T3, P0-T6.
- **Target:** `engine/Room.kt` (+ fold `ui/UIBox.kt`'s `uiScaleFor`).
- **Build:** the live `G.ROOM`/`G.ROOM_ATTACH` Moveable anchor; `prep_stage`; make all world coords
  ROOM_ATTACH-relative and recomputed (replaces the frozen `PF.*` constants — see plan §5 #1).
- **Accept:** computed area `T.x/T.y` match the current measured `PF.*` constants (so we *derive* the
  joker/hand/play origins instead of curve-fitting — closes the localthunk-debt).

### P0-T8 MainUpdateLoop — NET-NEW — ☑ (engine/EngineHost.tick + withFrameNanos loop in RoundPlay; Controller hook P3-stubbed)
- **Source:** `game.lua` Game:update (~2616–2844): timers → `E_MANAGER:update` → state update →
  `ANIMATIONS.animate` → two-pass Moveable (`move(move_dt)` then `update`) → `Controller.update`.
- **Depends on:** P0-T1, P0-T2, P0-T6 (Controller is P3 — stub the hook).
- **Target:** `engine/GameLoop.kt` (a `withFrameNanos` driver) + wire into the Compose root.
- **Build:** the per-frame order with the mandatory two-pass `FRAME.MOVE` guard; one clock, one
  EventManager, one moveable sweep — replacing the scattered `LaunchedEffect` timers in `RunScreen.kt`.
- **Accept:** the existing render still draws; HUD stays 80/80; scoring cascade can be re-homed onto
  Events (P4) instead of hard delays.

---

**Progress:** T1–T5, T7, T8 done; T6's Moveable class done and its first integration landed — all
verified by 33/33 standalone checks (EngineSpineCheck.kt), HUD layout still 80/80, and a full
deploy_diff at **16.8%** vs bref_3 (down from 17.0% — the exact set_screen_positions origins + the
DECK_Y fix beat the rounded PF constants; no regression). The play-field card AREAS are now
engine-Moveable-driven: EngineHost owns one clock + EventManager + the six CardArea Moveables (T
from Room), the withFrameNanos loop in RoundPlay ticks them, and the render reads each area's VT
(== T == derived Room origin at rest). The frozen PF.* origin constants are deleted (debt closed).

**What remains of P0/T6:** card-LEVEL Moveable conversion — promote the hand cards (currently
`ui/Spring.kt` SpringHand) and jokers (currently `BalatroFloat`) to engine Moveables driven by the
same loop, so individual cards spring/lean/juice through the engine rather than ad-hoc Compose
animators. That's the first place the loop produces VISIBLE engine-driven motion (gated by the
pixel diff staying ≤17% at rest + the bref_3 mid-animation frame for dynamics). Then P0 is complete
and the spine moves to P0.5 (the runtime object classes: Card/CardArea/Blind/Tag/Back).
