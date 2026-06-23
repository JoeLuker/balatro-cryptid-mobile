
### apksigner fails on NixOS in build.sh
the SDK build-tools apksigner is a shell wrapper with '#!/bin/bash' shebang, but NixOS has no /bin/bash, so signing dies with 'bad interpreter'. Also build-tools (apksigner/zipalign) aren't on PATH by default. Workaround used: export PATH=$HOME/Android/Sdk/build-tools/35.0.0:$PATH and run apksigner via 'bash $(which apksigner)'. Durable fix not yet applied to build.sh.
<!-- session:2026-06-08-44ada020 | commit:e7723fadd74a66044489c875107455c9b6a9d6af | date:2026-06-08 -->

### Android runtime Lua = lovely DUMP, not mod lovely/ payloads
on this Android build, the game runs src/dump/functions/*.lua (pre-patched on desktop, copied to game.love/functions/ at build time). Mods' own lovely/*.toml+*.lua payloads are NOT executed at runtime (lovely-injector doesn't live-patch on Android). So any fix to lovely-injected code (e.g. sticky-fingers wrappers) MUST patch game_dir/functions/*.lua during build_apk, NOT mods/<mod>/lovely/. Crash line numbers in tracebacks (functions/misc_functions.lua:2722) map to src/dump/functions/.
<!-- session:2026-06-08-44ada020 | commit:e7723fadd74a66044489c875107455c9b6a9d6af | files:/etc/nixos/hosts/teleos/configuration.nix,scripts/build.sh | area:teleos | date:2026-06-08 -->

### Android build
lovely-injector does NOT run on-device (no native .so in APK). Per-mod Mods/<mod>/lovely/ payload folders and the standalone Mods/lovely/ dump+log are dead weight — stripped at build time in build.sh (embed loop + prepare_transfer). Runtime patching is entirely via the lovely DUMP copied from src/dump to game.love top-level (functions/, engine/, SMODS/). require 'lovely' is satisfied by the build-generated top-level lovely.lua, not Mods/lovely/.
<!-- session:2026-06-08-44ada020 | commit:e7723fadd74a66044489c875107455c9b6a9d6af | files:/etc/nixos/hosts/teleos/configuration.nix,scripts/build.sh | area:teleos | date:2026-06-08 -->

### Mali GPU shader NaN -> black cards
Cryptid glitched.fs line 'float iTime = tan(2.*time)' produces inf/NaN at tan asymptotes on Mali-G710 (Tensor G2), cascading through rand() to NaN texCoords -> Texel returns pure black -> glitched card renders black for a frame. Fixed in build.sh apply_glitch_shader_fix with 'iTime = (abs(iTime)<1000.)?iTime:0.' guard (NaN compares false). Same Mali-precision family as the CRT.fs time*1000 overflow fix. Also: Tensor G2 thermally throttles (status SEVERE ~59C) under texture_scaling=2 + CRT + bloom + shadows; lowered mobile defaults via apply_mobile_graphics_defaults. settings.jkr is compressed (can't grep); reset by deleting files/save/game/settings.jkr so globals defaults regenerate.
<!-- session:2026-06-08-44ada020 | commit:e7723fadd74a66044489c875107455c9b6a9d6af | files:/etc/nixos/hosts/teleos/configuration.nix,scripts/build.sh,config.yaml,patches/reserve-shim/reserve-shim.json,patches/reserve-shim/reserve-shim.lua,justfile | area:patches | date:2026-06-09 -->

### Mali fp16 shader overflow fix via per-variable highp
Cryptid glitched_b.fs rendered black on Mali-G710 because chaotic math (pow^3/^5, div-by-~0) overflows fp16 mediump. Global 'precision highp float;' CRASHES (Mali rejects mixed-precision texture/sampler path). WORKING fix: qualify ONLY the math helpers (mod2, bitxor) and accumulators (t, randnum, cx, cy, mbx...) as highp, leave Texel/texture_coords at default precision. Compiles on Mali + boots. Also built a glslang compile-check harness (wrap shader w/ love-ish preamble: #version 100, #define number float/Image sampler2D/extern uniform/Texel texture2D, varyings, main calling effect()). glslang catches mod-collision/syntax but NOT Mali's precision-overload strictness (it passed the global-highp version that crashed).
<!-- session:2026-06-08-44ada020 | commit:e7723fadd74a66044489c875107455c9b6a9d6af | files:/etc/nixos/hosts/teleos/configuration.nix,scripts/build.sh,config.yaml,patches/reserve-shim/reserve-shim.json,patches/reserve-shim/reserve-shim.lua,justfile | area:patches | date:2026-06-09 -->

### Card warp root cause (touch description-persist)
the description popup hangs off CONTROLLER.hovering.target but the 3D mesh tilt hangs off card.states.hover.is — two different flags. Tilt anchor tilt_var.mx/my = LIVE cursor whenever hover.is is true (card.lua:4921 + SMODS DrawStep card_draw.lua:88), fed to sprite.lua mouse_screen_pos uniform. Persisting a description without clearing hover.is makes the old card skew toward wherever the finger goes. Fix = TAP_DESC_RELAX: drop hover.is once finger is off the card; popup survives, tilt falls to ambient branch. Earlier set_offset/hover_offset guard was the WRONG knob (only feeds tilt_var.amt magnitude, not the mx/my anchor).
<!-- session:2026-06-09-d573b6f7 | commit:afde8cc53c6eb146a40b5a50c553d961355bed2b | date:2026-06-09 -->

### Stationary touch-hold on a hand card registers as a degenerate drag (hand cards have drag.can for reorder); release then takes the drag-release path which calls stop_hover() and nils hovering.target. Any persist-description feature must exempt holds with travel < G.MIN_CLICK_DIST from the drag-release path (TAP_DESC_HOLD_NODRAG).
<!-- session:2026-06-09-d573b6f7 | commit:afde8cc53c6eb146a40b5a50c553d961355bed2b | date:2026-06-09 -->

### eval_card Cryptid extended tiers at wrong level
ret.e_chips/ee_chips/eee_chips/hyper_chips (lines 659-675) and ret.e_mult/ee_mult/eee_mult/hyper_mult (lines 678-696) and first ret.x_chips write (line 655) are all top-level on ret, but trigger_effects only dispatches named sub-tables (playing_card/enhancement/edition/seals/jokers). SMODS.calculate_effect is called on sub-table contents, not top-level keys. Talisman's calculate_individual_effect extension handles e_chips etc. as keys inside effect sub-tables (e.g. playing_card.e_chips), NOT as top-level ret.e_chips. Result: Cryptid playing card extended tier bonuses written to ret top-level are dead -- they never reach Talisman's handler. Only the second x_chips write at ret.playing_card.x_chips (line 704) correctly reaches trigger_effects. Source: common_events.lua:636-708 vs utils.lua:1372-1412.
<!-- session:2026-06-09-1fc98b16 | commit:4bbd06a8357756a649ee22575b9ebe14fb55cab5 | date:2026-06-09 -->

### can_reserve_card crash root cause
Cryptid's `misc_functions.lua:2722` calls `can_reserve_card`, a function normally provided by Pokermon. Without Pokermon, the field is nil and the game crashes during UI calc. The fix is a tiny `reserve-shim` patch that defines the function rather than installing the entire Pokermon mod.
<!-- session:2026-06-08-44ada020 | commit:e7723fadd74a66044489c875107455c9b6a9d6af | files:patches/reserve-shim/reserve-shim.lua,patches/reserve-shim/reserve-shim.json,functions/misc_functions.lua | area:patches | date:2026-06-08 -->

### Pokermon cannot be partially installed via folder nesting
Pokermon's loader (`pokermon.lua:4`) hard-errors if its folder is nested in Mods or not fully present, so you cannot cherry-pick files from it — extracting only the needed function via a shim is the correct path.
<!-- session:2026-06-08-44ada020 | commit:e7723fadd74a66044489c875107455c9b6a9d6af | files:/etc/nixos/hosts/teleos/configuration.nix,scripts/build.sh,scripts/build.sh,scripts/build.sh,scripts/build.sh | area:scripts | date:2026-06-08 -->

### Frame flicker on hand-play
The single-frame screen flash when playing any hand (at any chip scale) was a brightness/dim-to-black-for-one-frame artifact, ultimately traced to a shader/effect rather than a slowdown; investigated alongside the broken glitched-joker homescreen card (glitch shader).
<!-- session:2026-06-08-44ada020 | commit:e7723fadd74a66044489c875107455c9b6a9d6af | files:scripts/build.sh | area:scripts | date:2026-06-08 -->

### Mobile card-description interaction model
Jokers/consumables use tap-to-toggle (tap shows description, tap again hides). Playing cards should use tap-and-hold to show, tap-elsewhere to dismiss — a different interaction than jokers. This distinction lives in the Sticky Fingers behavior patched through build.sh.
<!-- session:2026-06-08-44ada020 | commit:e7723fadd74a66044489c875107455c9b6a9d6af | files:scripts/build.sh | area:scripts | date:2026-06-08 -->

### Settings tabs
The game exposes a "Video" tab that does nothing on this build and should be removed; "Graphics" is the correct/functional tab.
<!-- session:2026-06-08-44ada020 | commit:e7723fadd74a66044489c875107455c9b6a9d6af | files:scripts/build.sh | area:scripts | date:2026-06-08 -->

### Input Controller architecture map (verified against build/game/engine/controller.lua, June 2026).

SUBSYSTEM: engine/controller.lua
PURPOSE: Processes all input sources (touch, mouse, gamepad, keyboard) each frame. Maintains the authoritative cursor/focus/hover/drag/click state machines. Single choke point between raw SDL events and Balatro UI. sticky-fingers mod patches bake touch-tap and drag-select logic into this file via the lovely dump.

--- VERIFIED LINE NUMBERS (grepped against built file) ---

Controller:update          line 191   (top-level per-frame dispatcher)
Controller:set_cursor_position  (called update_cursor in some notes) line 611
Controller:update_axis     line 645
Controller:get_cursor_collision  line 1082  (NOT ~600 — earlier map was wrong by ~480 lines)
Controller:set_cursor_hover      line 1112  (NOT ~700 — earlier map was wrong by ~412 lines)
Controller:update_focus          line 1243  (NOT ~650 — earlier map was wrong by ~593 lines)
Controller:queue_L_cursor_press  line 1147
Controller:L_cursor_press        line 1166
Controller:L_cursor_release      line 1190

--- VERIFIED FUNCTION ROLES ---

Controller:get_cursor_collision (line 1082):
  Walks G.DRAW_HASH flat array. Calls EMPTY(collision_list) unconditionally at top,
  then early-returns if COYOTE_FOCUS is set (leaving list empty). Otherwise populates
  collision_list with every node whose bounding box contains the cursor position.

Controller:set_cursor_hover (line 1112):
  Selects cursor_hover.target from collision_list (first hoverable entry). Also stamps
  cursor_hover.time (TOTAL) and cursor_hover.uptime (UPTIME). Sets cursor_hover.handled=false
  on target change. Does NOT fire hover callbacks — those are fired in Controller:update's
  object-dispatch block (~line 449 onward). The function name is misleading on this point.

Controller:update_focus (line 1243):
  GAMEPAD-ONLY PATH. Early-returns immediately when HID.controller is false (line 1247).
  On Android with touch input, HID.controller is never set (set_HID_flags only sets it for
  'axis', 'button', 'axis_cursor' types — not 'touch'). Therefore update_focus is
  effectively a no-op on Android unless a physical gamepad is connected.
  Manages self.focused.target, NOT self.hovering.target. The earlier map was wrong on both
  the role (said hovering.target) and the COYOTE_FOCUS claim.

Controller:L_cursor_press (line 1166):
  Real press handler. Guards on self.locked / self.locks.frame. Stamps cursor_down.T/time/
  uptime/handled, sets is_cursor_down, resolves cursor_down.target from collision data.
  cursor_down.uptime is stamped here at line 1174 tagged CURSOR_DOWN_UPTIME_FIX.

Controller:L_cursor_release (line 1190):
  Stamps cursor_up.T/time/uptime, sets cursor_up.handled=false, clears is_cursor_down.
  Sets cursor_up.target = hovering.target or focused.target.
  Does NOT set released_on.target. Does NOT set touch_control.s_tap.
  Both released_on and s_tap are assigned in the cursor_up processing block inside
  Controller:update (lines 339-391). The earlier map was wrong on this.

--- VERIFIED CONTROL FLOW ORDER inside Controller:update ---

(1)  Lock parse → self.locked
(2)  update_axis → set_HID_flags
(3)  set_cursor_position (mouse/touch cursor placement)
(4)  Key/button event dispatch (pressed_keys, held_keys, released_keys, buttons)
(5)  Snap-to-grid / snap_cursor_to handling
(6)  get_cursor_collision (line 312) — populates collision_list from G.DRAW_HASH
(7)  update_focus (line 313) — gamepad focused.target selection
(8)  set_cursor_hover (line 314) — cursor_hover.target selection + uptime stamp
(9)  L_cursor_queue flush (lines 315-317) — deferred press resolves here
(10) cursor_down handled=false block (lines 326-337) — drag init state, DRAG_SELECT_ACTIVATE
(11) cursor_up handled=false block (lines 339-391) — release, click, released_on, s_tap
(12) DRAG_SELECT_LOOP (line 394) — slide-to-select while is_cursor_down
(13) TAP_DESC_HOLDGATE (line 408) — promotes cursor_hover.target → hovering.target (200ms gate for hand cards)
(14) TAP_DESC_PERSIST (line 413) — clears hovering.target when cursor_hover nil
(15) Object dispatch: clicked (422), released_on (441), drag-init create_drag_target_from_card (431), dragging (436), TAP_DESC_RELAX (447), hovering (449+)

IMPORTANT ORDER NOTE: drag-init (duration>0.1, line 431) runs AFTER TAP_DESC_HOLDGATE (line 408),
not before it. The earlier map had this backwards. The ordering is benign — they touch
different state (hover vs drag) — but the map claim was wrong.

--- VERIFIED STATE STRUCTURES ---

self.cursor_down (line 21 init, NOT ~50):
  Init: {T={x=0,y=0}, target=nil, time=0, handled=true}
  Fields .uptime, .distance, .duration are NOT in the init table.
  .uptime assigned at line 1174 inside L_cursor_press (CURSOR_DOWN_UPTIME_FIX)
  .distance assigned at line 327 (reset to 0 on new press) and accumulated at line 483
  .duration assigned at line 328 (reset to 0) and accumulated at line 484 as UPTIME - uptime

self.cursor_hover (line 24 init):
  Init: {T={x=0,y=0}, target=nil, time=0, handled=true}
  .uptime added dynamically at line 1116 inside set_cursor_hover (lovely patch injection)

self.touch_control (line 18 init):
  {s_tap={target=nil, handled=true}, l_press={target=nil, handled=true}}
  Present in built file as ordinary Lua; originated from lovely patch but no toml marker visible.
  l_press.target is never set anywhere — guard 'not self.touch_control.l_press.target' at
  line 364 is always true. l_press infrastructure is dead.
  s_tap.target is set at line 365 on short taps but has no consumer outside update. Dead.

self.dragging (line 12 init):
  {target=nil, handled=true, prev_target=nil}
  .handled gates drag-init block at line 431.

self.L_cursor_queue (set at line 1152):
  Single {x,y} table. Flushed at lines 315-317. NOT in init table — nil until first queue.

--- COYOTE_FOCUS ---
  Set at line 1408 in button handler (gamepad dpad card-reorder path).
  Effect in get_cursor_collision: EMPTY(collision_list) runs unconditionally, then
  COYOTE_FOCUS causes early return — list stays empty.
  Effect in set_cursor_hover: sets cursor_hover.target = G.ROOM and returns (line 1121).
  Net: collision_list empty, cursor_hover = G.ROOM for that frame. The earlier map said
  COYOTE_FOCUS 'clears collision_list' which is slightly wrong — it skips population after
  the unconditional EMPTY, but the clearing is not conditional on COYOTE_FOCUS.
<!-- session:2026-06-09-c2f18140 | commit:83a0ee4eeb06f6f3e31d098bf50a4f0b3a966550 | date:2026-06-09 -->

### Android mod-copy duality
the build ships TWO copies of every mod — embedded in game.love (patched by build_apk appliers) and pushed to files/save/Mods (from prepare_transfer). SMODS enumerates mods from the EMBEDDED archive (proof: stale save-dir Pokermon never loaded; embedded-only shader fixes work on device), but LÖVE save-dir reads can shadow same-path archive files, so mod-file patches must be applied to BOTH copies (see apply_talisman_dim_fix). Deploy pushes but never deletes — stale mods linger in files/save/Mods.
<!-- session:2026-06-09-c2f18140 | commit:83a0ee4eeb06f6f3e31d098bf50a4f0b3a966550 | files:docs/GAME-ARCHITECTURE.md,scripts/build.sh,scripts/patch_main_lua.py | area:scripts | date:2026-06-09 -->

### Local test harness (test/)
'just test-controller' runs 10 gesture tests against the REAL built engine/controller.lua in <1s via pure luajit + stubs (tap/hold/drag/slide; all TAP_DESC_*/DRAG_SELECT_* patches). 'just smoke' boots build/game on this machine under Xvfb with love.system.getOS() spoofed to 'Android' so shipped code paths run verbatim; asserts boot-to-menu + screenshot at build/smoke/smoke.png. Use these BEFORE deploying to the phone. Phone still required for: Mali shader behaviour, touch feel, perf numbers. Fake cards must set click_timeout=0.3 to match Card:init.
<!-- session:2026-06-09-b370c95b | commit:e5c767772e87596f5db49061f1279843c8de0ec9 | date:2026-06-09 -->

### Emulator GLES translator is stricter than Mali/Mesa
rejects (1) shader files whose last line is a directive without trailing newline (28 shipped .fs had this), (2) prototype-first shader structure (blur.fs: effect() before helper definitions -> 'function definition not found' in vertex stage). Both fixed as build appliers (apply_shader_eof_newlines, apply_blur_shader_reorder). Also: LÖVE's crash screen logs NOTHING to logcat and keeps the process alive — detect via screenshot polling: static+flat = crash, animated+colour-diverse = menu. LuaJIT under ARM->x86 ndk_translation cannot JIT: emulator boot takes minutes vs desktop 14s.
<!-- session:2026-06-09-7507a29a | commit:54f3b4c8d557967eba10312e71574a15475c7b11 | date:2026-06-09 -->

### Perf findings doc at docs/PERF-FINDINGS.md
verified+ranked optimization list. NEVER reapply setShader bind elision (corrupts rendering — Shader:send not batch-aware) or pseudoseed %.13f format change (PRNG desync). Tier-1 GC wins (align_cards closures, parse_highlighted hoists, juice_up literal) are measurable in DESKTOP SMOKE via collectgarbage deltas — same Lua, no phone. Biggest scoring candidate: to_big fast-path for values <1e15 (OmegaNum allocs at normal chip scale).
<!-- session:2026-06-09-cc3a91f3 | commit:2a8c40df01796705cd1f79cfd1e11af4d9fd6c0c | files:scripts/build.sh,patches/android-telemetry.lua,docs/PERF-FINDINGS.md | area:scripts | date:2026-06-10 -->

### Headless Android emulation on NixOS
The project can run the APK in a headless emulator via a Nix shell (`test/emulator/shell.nix`) that downloads a system image, booted/driven by `test/emulator/run.sh`, providing an alternative to physical-phone deploys (which are gated behind `BALATRO_DEPLOY_PHONE=1`).
<!-- session:2026-06-09-a2689c6a | commit:4f9e9afb539b4b1966b0672efaf3f28df643e549 | files:test/emulator/shell.nix,test/emulator/run.sh,scripts/build.sh | area:test | date:2026-06-09 -->

### Dual build/source tree mirroring
Edits are consistently applied to both `build/game/...` and `src/dump/...` paths in tandem, indicating these trees mirror each other and changes must be kept in sync.
<!-- session:2026-06-09-a2689c6a | commit:4f9e9afb539b4b1966b0672efaf3f28df643e549 | files:build/game/engine/sprite.lua,src/dump/engine/sprite.lua,build/game/game.lua,src/dump/game.lua | area:build | date:2026-06-09 -->

### Custom love.run frame loop
The game replaces LOVE's default run loop (main.lua:906). Per frame it pumps events (deferring touchpressed until after mousepressed), steps the timer, applies an EMA dt smoothing (0.8 prev + 0.2 raw, capped at 0.1s), then calls update/draw and sleeps to enforce G.FPS_CAP (default 500). The smoothed dt — not raw dt — is what feeds simulation, preventing Android resume spikes from jumping the sim.
<!-- session:2026-06-09-c2f18140 | commit:83a0ee4eeb06f6f3e31d098bf50a4f0b3a966550 | files:main.lua,conf.lua | date:2026-06-09 -->

### Talisman scoring coroutine
Scoring runs as a cross-frame coroutine. `G.FUNCS.evaluate_play` (main.lua:2094) creates `G.SCORING_COROUTINE`, records `G.SCORING_START`, and resumes once; subsequent resumes happen every frame in the Talisman `love.update` override (main.lua:2111). The only `coroutine.yield()` site is in `Card:calculate_joker` (main.lua:2204), which yields when more than TIME_BETWEEN_SCORING_FRAMES (0.03s) has elapsed. A 'calculating...' overlay appears only after scoring exceeds 0.3s.
<!-- session:2026-06-09-c2f18140 | commit:83a0ee4eeb06f6f3e31d098bf50a4f0b3a966550 | files:main.lua | date:2026-06-09 -->

### Android nativefs shim
On Android, `nativefs.lua:14` returns a pure-Lua table wrapping love.filesystem (no FFI), whereas other platforms delegate to the FFI-based `nativefs.nativefs`. The telemetry error handler (android-telemetry.lua:127) is the outermost love.errorhandler, logging a CRASH event with state/ante/round context before chaining to the SMODS crash-screen handler.
<!-- session:2026-06-09-c2f18140 | commit:83a0ee4eeb06f6f3e31d098bf50a4f0b3a966550 | files:nativefs.lua,android-telemetry.lua | date:2026-06-09 -->

### Save system cadence
`G.FILE_HANDLER` holds per-cycle flags (progress, settings, run, metrics, force) polled in `Game:update`. Saves push to the `save_request` channel every F_SAVE_TIMER seconds (30s default, 5s dev). SOUND/SAVE/HTTP managers each run as background threads started in `G:start_up`.
<!-- session:2026-06-09-c2f18140 | commit:83a0ee4eeb06f6f3e31d098bf50a4f0b3a966550 | files:game.lua | date:2026-06-09 -->

### Incremental GC budget
`nuGC` (functions/misc_functions.lua:718) runs `collectgarbage('step',1)` under a time budget (~0.3ms) at the top of every `Game:update`, with `disable_otherwise=true` halting automatic GC between calls and a safety full-collect when the heap exceeds 300MB.
<!-- session:2026-06-09-c2f18140 | commit:83a0ee4eeb06f6f3e31d098bf50a4f0b3a966550 | files:functions/misc_functions.lua | area:functions | date:2026-06-09 -->

### Timer semantics
`G.TIMERS` distinguishes TOTAL (game-speed-scaled), REAL (wall seconds, reset at round start), REAL_SHADER (frozen at 300 under reduced_motion), UPTIME (never reset), and BACKGROUND. Nodes receive `dt*SPEEDFACTOR`; SPEEDFACTOR derives from SETTINGS.GAMESPEED plus ACC overflow during HAND_PLAYED/NEW_ROUND.
<!-- session:2026-06-09-c2f18140 | commit:83a0ee4eeb06f6f3e31d098bf50a4f0b3a966550 | files:globals.lua,game.lua | date:2026-06-09 -->

### Warp-artifact root cause
vanilla main.lua run loop dedupes touchpressed+synthetic mousepressed by same-pump coincidence (the 'touched' flag), discarding LÖVE's native istouch arg (_d). When SDL delivers the pair across two pump batches (seen on Joe's 120Hz foldable, 2208x1840 inner display), the press classifies as MOUSE: every HID.touch-gated patch (TAP_DESC_RELAX, DRAG_SELECT_ACTIVATE) silently deactivates for that press, vanilla mouse-hover semantics wedge states.hover.is=true on the card, its tilt anchor (tilt_var.mx/my -> mouse_screen_pos shader uniform) tracks the finger forever, and hover.is being stuck also blocks the ambient_tilt branch from ever overwriting the huge frozen tilt_var.amt — cards stay violently stretched at rest and artifacts accumulate one per lost race. Fix: HID_ISTOUCH_FIX in patch_main_lua.py — dispatch with (touched or _d). Diagnosis chain that worked: phone APK byte-identical to build/game, desktop repro with phone save clean, gesture suite green (harness force-sets HID touch so it is blind to misclassification), Joe's repro steps (tap card A, drag card B) pinned the trigger.
<!-- session:2026-06-10-8b8b54c2 | commit:4b9164d06f5b5620cb9e09483d276ec37f701841 | files:test/warp-repro-autorun.lua,test/warp-repro.sh,scripts/patch_main_lua.py | area:test | date:2026-06-10 -->

### CORRECTION to earlier warp root-cause note
the HID istouch races were real hardening but NOT the warp. The actual warp: vanilla controller.lua released_on path (line ~442) runs hovering.target:stop_hover() + nils hovering.target after any card drag — but Node:stop_hover only removes the popup and NOTHING clears states.hover.is. The card is orphaned with hover.is stuck true; its 3D tilt (tilt_var -> mouse_screen_pos uniform) tracks the live cursor forever. Desktop self-heals via constant mouse-over re-acquisition; touch never re-acquires, so every card drag (reorder, drag-select sweep, sticky-fingers) wedged one more card until restart. Fixed by DRAG_RELEASE_UNHOVER applier (commit 25b97a1): clear the flag in the same path. Found mechanically by test/controller/fuzz.lua (invariant: finger up => no card holds hover.is) — wedged within 40 steps on every seed in ALL configs including drag-select disabled; frame-traced minimal repro in test/controller/min-repro.lua. Lesson: the nominal-flow gesture suite was green the whole time — only invariant-fuzzing over random interleavings exposed it.
<!-- session:2026-06-10-29cb0bd3 | commit:fc08cbf30e7e5aa487ac3a2b0101b6e425575532 | files:test/controller/run.lua,/home/jluker/.claude/projects/-home-jluker-balatro-cryptid-mobile/memory/never-deploy-to-phone-unasked.md,test/controller/fuzz.lua,test/controller/min-repro.lua,scripts/build.sh | area:test | date:2026-06-10 -->

### Drag-to-select regression
The multi-card drag-select path in the controller was the confirmed root cause of the touch breakage; isolated via a minimal repro plus fuzz harness under `test/controller/`.
<!-- session:2026-06-10-29cb0bd3 | commit:fc08cbf30e7e5aa487ac3a2b0101b6e425575532 | files:test/controller/min-repro.lua,test/controller/fuzz.lua | area:test | date:2026-06-10 -->

### Alloc benchmarking workflow
Per-frame allocation hotspots are measured via an autorun Lua bench wired through `scripts/build.sh`, with results captured in `docs/PERF-FINDINGS.md`. Lean-down Python helpers (`/tmp/ph-lean.py`, `/tmp/ces-lean.py`, `/tmp/gxs-lean*.py`) were used to trim measurement noise.
<!-- session:2026-06-10-29cb0bd3 | commit:fc08cbf30e7e5aa487ac3a2b0101b6e425575532 | files:test/perf/alloc-bench-autorun.lua,test/perf/alloc-bench.sh,docs/PERF-FINDINGS.md | area:test | date:2026-06-10 -->

### Warp artifact regression window
User reported shader warp artifacts that were NOT present "earlier today at ~3pm" but appear now when moving cards around — pointing to a regression introduced by commits between 3pm and the session. Recent commits in the window touch shader range reduction (`glitched.fs` MALI_RANGE_FIX), DynaText glyph cache, and released_on liveness guards — these are the prime suspects for a future bisect.
<!-- session:2026-06-10-8b8b54c2 | commit:4b9164d06f5b5620cb9e09483d276ec37f701841 | files:shaders/glitched.fs,build/game/main.lua | area:shaders | date:2026-06-10 -->

### released_on lifecycle contract (root cause of two device bugs): sticky-fingers' per-drag buy-target nodes are destroyed by drag-end cleanup BEFORE the controller's released_on dispatch runs in the same frame. Node:remove nils G.CONTROLLER.released_on.target on removal — so the dispatch either crashed on nil (the SMODS_BOOSTER_OPENED Pull crash) or, once nil-guarded, silently swallowed buys (voucher drag-to-buy 'not working'). Fix RELEASED_ON_PENDING_KEEP (b0ba883): Node:remove leaves released_on.target alone while a dispatch is pending (handled==false); dispatching on a just-removed node is correct because the release callback acts on the CARD. Diagnosed entirely via phone-home telemetry: G_REL_SKIP events with card key proved the swallow in two drag attempts. Confirmed working on device by Joe.
<!-- session:2026-06-10-cbc52b56 | commit:ea8ae4194a66ab031d8a557d9fcc5d7dfb5ab6c0 | files:scripts/build.sh,docs/PERF-FINDINGS.md,scripts/telemetry-home.py,patches/android-telemetry.lua,justfile,/tmp/stale-pos-probe.lua,test/controller/run.lua,test/controller/fuzz.lua,/tmp/overlap-probe.lua,test/perf/alloc-bench-autorun.lua | area:test | date:2026-06-10 -->

### Provenance logging is essential for input debugging
Three consecutive failed fix attempts on the slide/tap bug were broken by the user pointing out "not enough provenance in the logs." Adding `src=<file:line>` call-site tags, click counts (`cl=`), and tap timing (`tap=`) to G_DSEL/G_HL/G_CLICK telemetry events revealed the actual gesture state machine flow. Without call-site provenance, the gesture traces were ambiguous about which code path triggered highlight add/remove.
<!-- session:2026-06-10-cbc52b56 | commit:ea8ae4194a66ab031d8a557d9fcc5d7dfb5ab6c0 | files:patches/android-telemetry.lua | area:patches | date:2026-06-10 -->

### Touch gesture state machine signals
Device gesture traces emit G_DSEL (drag-select state s=highlight/select/sc), G_HL (highlight add/rem with source + count n=), G_CLICK (area+card+src), G_HOVER/G_HOVER_IS, G_POPUP (description up/down), G_DRAG, G_STOPHOVER. Highlight operations originate from cardarea.lua:104 (remove), controller.lua:422 and card.lua:5180 (add). Card click routing goes through gameset.lua:645.
<!-- session:2026-06-10-cbc52b56 | commit:ea8ae4194a66ab031d8a557d9fcc5d7dfb5ab6c0 | files:patches/android-telemetry.lua | area:patches | date:2026-06-10 -->

### Phone-home beats local watcher for device telemetry
Replaced a polling watcher with the device POSTing telemetry directly (telemetry-home.py receiver). User explicitly questioned why a watcher was needed when the device could phone home — phone-home is the cleaner pattern for pulling device-side traces back to the dev machine.
<!-- session:2026-06-10-cbc52b56 | commit:ea8ae4194a66ab031d8a557d9fcc5d7dfb5ab6c0 | files:scripts/telemetry-home.py,patches/android-telemetry.lua,justfile | area:scripts | date:2026-06-10 -->

### Android mod-config persistence maze, fully mapped 2026-06-10
(1) love save dir = files/save/game/ — files/save/Mods is the deploy overlay, INVISIBLE to love.filesystem; the deploy config-preservation that backs up files/save/Mods/**/config.lua protects files the game never reads or writes (mods load from the embedded game.love archive; runtime Talisman code is the lovely-merged copy inside main.lua, NOT Mods/Talisman/talisman.lua). (2) love.filesystem.write fails silently when parent dirs are missing AND createDirectory does not recurse on this device — fixed in android-nativefs shim (ensure_parent_dirs) which un-breaks ALL SMODS mod config saves. (3) Talisman score-type UI persists break_infinity='' (vanilla scoring) which crash-loops boot since this pack requires omeganum (Big nil -> number_format indexes a plain number, main.lua:1633); clamped at config read (TAL_BREAKINF_CLAMP) + pcall'd STR_UNPACK (TAL_CFG_SAFE_UNPACK) since love.filesystem.write isn't atomic. (4) apply_android_smods_path_fix in build.sh is defined but NEVER CALLED — patch_main_lua.py superseded it; don't add patches there. (5) Quit button: vanilla never sets F_QUIT_BUTTON=false on Android (Switch/PS do) — quit gave a black lingering activity and dropped sub-30s settings changes (F_SAVE_TIMER=30 on Android, quit path never flushed FILE_HANDLER); fixed by hiding the button + QUIT_FLUSH (parallel session, 577e89e).
<!-- session:2026-06-10-20d898da | commit:72617555988b19873ad71d55ef4dec99af5b4723 | files:scripts/build.sh,patches/android-nativefs.lua,scripts/patch_main_lua.py | area:scripts | date:2026-06-10 -->

### Telemetry/logging default-off
Telemetry and general logging are now opt-in via in-game consent toggles, defaulting OFF. Motivation is distribution to non-developer users (sibling) where file generation on their device should be minimal until explicitly consented.
<!-- session:2026-06-10-64ffe7b7 | commit:b0ba883aac15cc64e1e41f2856f120e5512b96fb | files:patches/android-telemetry.lua,test/telemetry-gate-autorun.lua | area:patches | date:2026-06-10 -->

### PATCH TARGETING RULE for this pack
Talisman (and any lovely-injected mod) code exists in TWO copies — Mods/<mod>/<file>.lua (dead code on Android) and the lovely-merged copy inside main.lua (what actually executes). Any applier patching Talisman behavior MUST target build/game/main.lua (or both). Bitten twice on 2026-06-10: TAL_CONFIG_PERSIST (config writes) and NF_BIG_CACHE (heap churn fix inert on-device until re-applied to main.lua).
<!-- session:2026-06-11-fa194551 | commit:c455b398ec194f76df12640132e68b28e63311f7 | files:docs/PERF-FINDINGS.md,scripts/build.sh | area:docs | date:2026-06-11 -->

### Telemetry patch uses undefined globals at risk of nil-call crashes
`android-telemetry.lua:376` called `card_key_of`, a global that doesn't exist in this build, crashing inside the Cryptid `gameset.lua` update chain. Telemetry helpers must be locally defined or nil-guarded before call.
<!-- session:2026-06-11-5c5d19d5 | commit:544afafbf05dc2a15b8d40a11d1a173af3adff33 | files:patches/android-telemetry.lua | area:patches | date:2026-06-11 -->

### PERF_SNAPSHOT telemetry schema
Performance snapshots emit `fps`, `gc_kb`, `dt_avg_ms`, `dt_max_ms`, object counts (`n_node`, `n_mov`, `n_ui`, `n_card`), `n_moves`, and `state`. Observed `SELECTING_HAND` at ~28-36 fps with `n_mov`/`n_card` in the 120-165 range and `n_moves` 700-800 — high moveable/card counts correlate with the framerate dip.
<!-- session:2026-06-11-5c5d19d5 | commit:544afafbf05dc2a15b8d40a11d1a173af3adff33 | files:patches/android-telemetry.lua | area:patches | date:2026-06-11 -->

### Deploy is gated
Deploying to the phone requires `BALATRO_DEPLOY_PHONE=1 ./scripts/build.sh deploy` — the phone is Joe's device, not CI.
<!-- session:2026-06-11-5c5d19d5 | commit:544afafbf05dc2a15b8d40a11d1a173af3adff33 | files:scripts/build.sh | area:scripts | date:2026-06-11 -->

### Move-pass cost dominates update time
Live PERF_SNAPSHOT telemetry consistently shows `move` (~4-7ms) and `update` (~3-6ms) as the largest contributors to per-frame update time, with `n_mov` (moveables) in the 180-240 range and FPS in the 30-44 band. The MOVEABLE_SLEEP optimization (settled moveables sleeping through the move pass) targets exactly this.
<!-- session:2026-06-11-fa194551 | commit:c455b398ec194f76df12640132e68b28e63311f7 | files:test/perf/moveable-bench-autorun.lua,patches/android-telemetry.lua | area:test | date:2026-06-11 -->

### Heap census roots
Repeated CENSUS telemetry shows `G.STAGE_OBJECTS` (~108k-146k objects) and `G.DRAW_HASH` (~131k-138k) as the dominant heap roots, with `G.MOVEABLES`, `G.P_CENTER_POOLS`, and `G.P_JOKER_RARITY_POOLS` as secondary contributors. Heap climbs into the 130-225MB range during play.
<!-- session:2026-06-11-fa194551 | commit:c455b398ec194f76df12640132e68b28e63311f7 | files:docs/PERF-FINDINGS.md,scripts/build.sh,scripts/build.sh,scripts/build.sh,scripts/build.sh | area:scripts | date:2026-06-11 -->

### Telemetry is the source of truth, not snapshots
The developer explicitly wants analysis driven by live telemetry (PERF_SNAPSHOT, CENSUS, CRASH, G_HOVER/DRAG/USE events) streamed from the phone via monitors, not from static heap snapshots, which he considers useless.
<!-- session:2026-06-11-fa194551 | commit:c455b398ec194f76df12640132e68b28e63311f7 | files:docs/PERF-FINDINGS.md,scripts/build.sh,scripts/build.sh,scripts/build.sh,scripts/build.sh | area:scripts | date:2026-06-11 -->

### Build stamp confirms running version
The build-stamp system exists specifically so the developer can visually confirm on the phone's home screen which build is live; the version already displays on the home screen (no separate version display should be created).
<!-- session:2026-06-11-fa194551 | commit:c455b398ec194f76df12640132e68b28e63311f7 | files:docs/PERF-FINDINGS.md,scripts/build.sh,scripts/build.sh,scripts/build.sh,scripts/build.sh | area:scripts | date:2026-06-11 -->

### WebSearch 400 'tool_choice forces tool use is not compatible with this model' on fable sessions: fable-class models categorically reject forced tool_choice (verified vs live API, independent of thinking). Claude Code's WebSearch sub-request forces the web_search tool on the session model — broken upstream through 2.1.173. Fixed locally in claude-cache-proxy (/etc/nixos/packages/claude-cache-proxy, commit dc976c8): downgrades forced tool_choice to auto on fable models; metric tool_choice_downgrades on :11801/metrics.
<!-- session:2026-06-11-78ee74c8 | commit:846251ffafbe938f483a019cedca5a8c7cfcab3a | files:scripts/build.sh,test/event/diff.lua | area:scripts | date:2026-06-11 -->

### draw-call baseline 2026-06-11
shsw_avg=455 at settled BLIND_SELECT (n_card=213, n_ui=11). shsw scales linearly with n_ui — each UIElement issues its own shader-bound draw. uiboxes cost is 0.03ms/ui-element. This is the Tier-1 SpriteBatch target: batch UI elements sharing the same atlas/shader to collapse shsw_avg from 455→~20. game.lua:3521 = create_UIBox_blind_select() = 260ms (Cryptid-overloaded blind screen). EVQ_BURST_ATTRIB working: first EV_SLOW events confirm per-handler attribution is live.
<!-- session:2026-06-11-a9a3a8a9 | commit:24cfd8bc95a6247fa012c4dd8e38e977b5124246 | files:docs/PERF-FINDINGS.md | area:docs | date:2026-06-11 -->

### n_ui telemetry was n_ui_total (#G.I.UIBOX) which includes attention_text animation boxes excluded from the uiboxes draw loop (game.lua:3011 filter). The 88->11 settling sequence at BLIND_SELECT is almost entirely these transient attention_text boxes expiring, not structural draw roots. uiboxes ms barely moves (2.56->1.72) because those boxes don't go through the measured codepath. Fixed 2026-06-11: telemetry now emits n_ui_s (structural, matching the draw loop filter) and n_ui_total separately. Old 'linear scaling at ~0.03ms/element' claim is an artifact — actual shape is large fixed floor ~1.7ms from a few heavy roots plus ~0.01ms marginal per structural element.
<!-- session:2026-06-11-3389ebda | commit:53f0e07828558114782bd1c0d2d03b9d2de13f63 | files:mods/Cryptid/items/epic.lua,mods/Cryptid/items/misc_joker.lua,patches/android-telemetry.lua,scripts/build.sh,build/game/android-telemetry.lua,/etc/nixos/packages/claude-cache-proxy/main.go,docs/PERF-FINDINGS.md | area:mods | date:2026-06-11 -->

### Cryptid disable architecture (2026-06-11/12 session)
SMODS toggle is a footgun on this APK (lovely half baked into game files; STRUCTURAL_MODS_LOCK ignores .lovelyignore for Cryptid/Amulet/sticky-fingers). Sanctioned switch = CRY_VANILLA_GAMESET (4th gameset, every center resolves 'disabled' through Cryptid.enabled's 62 consumers; switcher added to Cryptid config tab — upstream has none). Mid-run disables remove blinds from G.P_BLINDS → stale blind_choices crash (CRY_BLIND_CHOICE_GUARD sanitizes after the registry sweep). Forcetrigger recursion bounded centrally at depth 20 (CRY_FORCETRIGGER_DEPTH_GUARD, ATLOG names culprit). All four upstreamable to Cryptid.
<!-- session:2026-06-11-820859d2 | commit:e3fb1b97c2acf5a3fd5a6c159fe1e49f7d6bc73d | files:/home/jluker/.claude/projects/-home-jluker-balatro-cryptid-mobile/memory/subagents-readonly-briefs.md,/home/jluker/.claude/projects/-home-jluker-balatro-cryptid-mobile/memory/MEMORY.md,/home/jluker/.claude/plans/declarative-launching-origami.md,patches/android-telemetry.lua,scripts/regen-dump.sh,scripts/build.sh,scripts/patch_main_lua.py,test/gameset/vanilla-autorun.lua,test/gameset/run.sh | area:scripts | date:2026-06-12 -->

### NUGC_ADAPTIVE limit found (2026-06-12, deep-run session)
burst peaks grow with run depth (187/229/215/316MB across one session); at 316MB the 300MB emergency full-collect fired = multi-second 1fps hitch, then clean recovery to 93MB. Churn is NOT OmegaNum (cdata post-Amulet) — suspects: scoring-overlay string.format per co.update, eval-table turnover at GAMESPEED 4. Next perf item (ahead of Tier-2a on UX): NUGC v2 — budget cap scales past 4ms under pressure + opportunistic full-collect at state transitions when heap >200MB (hide the big collect in natural pauses).
<!-- session:2026-06-11-820859d2 | commit:e3fb1b97c2acf5a3fd5a6c159fe1e49f7d6bc73d | files:/home/jluker/.claude/projects/-home-jluker-balatro-cryptid-mobile/memory/subagents-readonly-briefs.md,/home/jluker/.claude/projects/-home-jluker-balatro-cryptid-mobile/memory/MEMORY.md,/home/jluker/.claude/plans/declarative-launching-origami.md,patches/android-telemetry.lua,scripts/regen-dump.sh,scripts/build.sh,scripts/patch_main_lua.py,test/gameset/vanilla-autorun.lua,test/gameset/run.sh,test/resize/resize-autorun.lua | area:scripts | date:2026-06-12 -->

### Heap census telemetry output
The on-phone census emits `CENSUS root=<name> n=<count> at_mb=<heap_mb>` lines terminating in `CENSUS_DONE budget_left=<n>`. At 157 MB heap, the largest object-count roots were G.GAME (35156), G.CONTROLLER (32688), G.culled_table (25355), and G.P_CENTERS (21911), pointing at where retained Lua objects concentrate.
<!-- session:2026-06-11-f4626447 | commit:79c3ad9efc1b02a5dae8c923e0d1038632937613 | files:docs/PERF-FINDINGS.md,patches/android-telemetry.lua | area:docs | date:2026-06-11 -->

### Overlay geometry probing
UIBox trees with major=G.ROOM_ATTACH lay out in ROOM-relative coords (ROOM_ATTACH is T={0,0,TILE_W,TILE_H} with container=ROOM); G.ROOM.T is canvas-space. Comparing UIElement.T against G.ROOM.T mixes spaces and gives false off-screen verdicts — visible room interior in tree-space is [0,TILE_W]x[0,TILE_H]. (Bit the AMULET_OVERLAY_FIT probe twice before the room-space verdict.)
<!-- session:2026-06-12-820ab7f7 | commit:6953fbdb51dfcadb15d3a18c0667d66dd9ea1024 | files:scripts/build.sh,/tmp/ovp/overlay-probe.lua | area:scripts | date:2026-06-12 -->

### Balatro UI engine
UIT.O nodes with detached config.object (mid-teardown overlays) break UIBox:recalculate twice — calculate_xywh computes nil w/h and set_values writes T.w/T.h=nil, then set_values role wiring derefs the nil object and errors. If the recalculate caller pcalls (our resize handler), the error is swallowed and the half-mutated tree crashes move_wh a frame later with 'arithmetic on field w (nil)'. Fixed by UI_O_DETACHED applier (0x0 layout + role-wiring guard); regression test: just uio. General lesson: pcall around tree-mutating UI ops converts loud failures into delayed corruption — guard inside the mutation instead.
<!-- session:2026-06-12-820ab7f7 | commit:6953fbdb51dfcadb15d3a18c0667d66dd9ea1024 | files:scripts/build.sh,/tmp/ovp/overlay-probe.lua,scripts/patch_main_lua.py,/tmp/foldcrash/fold-repro.lua,patches/android-telemetry.lua,test/resize/resize-autorun.lua,test/ui-o-dims/run.sh,justfile | area:scripts | date:2026-06-12 -->

### Cryptid is prebaked into the build
The mod cannot be functionally disabled at runtime by toggling it on a profile — mods are baked into the build artifact, so turning Cryptid off on a profile breaks things (its overrides/modifiers still load and then index nil values). Disabling must happen at the gameset/build layer, not runtime profile toggles.
<!-- session:2026-06-11-820859d2 | commit:e3fb1b97c2acf5a3fd5a6c159fe1e49f7d6bc73d | files:scripts/build.sh,scripts/patch_main_lua.py | area:scripts | date:2026-06-11 -->

### Cryptid crash signatures
Disabling Cryptid via profile produced `lib/overrides.lua:480: attempt to index a nil value` (SELECTING_HAND) and `lib/modifiers.lua:287: attempt to index field 'ability' (a nil value)` (PLAY_TAROT) — symptoms of partially-loaded Cryptid state.
<!-- session:2026-06-11-820859d2 | commit:e3fb1b97c2acf5a3fd5a6c159fe1e49f7d6bc73d | files:/home/jluker/.claude/projects/-home-jluker-balatro-cryptid-mobile/memory/subagents-readonly-briefs.md,/home/jluker/.claude/projects/-home-jluker-balatro-cryptid-mobile/memory/MEMORY.md,/home/jluker/.claude/plans/declarative-launching-origami.md,patches/android-telemetry.lua,patches/android-telemetry.lua | area:memory | date:2026-06-11 -->

### Heap census interpretation
Census telemetry roots shift with game state; `G.STAGE_OBJECTS`, `G.DRAW_HASH`, `G.localization`, `G.P_CENTER_POOLS`, and `G.MOVEABLES*` are dominant object holders. `G.DRAW_HASH` ballooned to 100k+ entries in some samples, flagging it as a growth area.
<!-- session:2026-06-11-820859d2 | commit:e3fb1b97c2acf5a3fd5a6c159fe1e49f7d6bc73d | files:patches/android-telemetry.lua | area:patches | date:2026-06-11 -->

### Mobile UI scaling is build-time injected
The settings/debug UI sizing for Balatro mobile is handled through `scripts/build.sh` and `scripts/patch_main_lua.py` injection rather than in-game config — adapting UI to screen size means patching at build time.
<!-- session:2026-06-13-2b3359d0 | commit:6b16881ef78dbb20305da78ce7af5933a70277ba | files:scripts/build.sh,scripts/patch_main_lua.py | area:scripts | date:2026-06-13 -->

### DebugPlus mod stack on Android
DebugPlus v1.5.2 (by WilsontheWolf) loads under Steamodded alongside Cryptid, CardSleeves, Sticky Fingers, Amulet, and a Reserve Shim. The debug overlay can collide with the existing FPS/telemetry display, so integration (not just bundling) is required.
<!-- session:2026-06-13-2b3359d0 | commit:6b16881ef78dbb20305da78ce7af5933a70277ba | files:config.yaml,scripts/build.sh | area:scripts | date:2026-06-13 -->

### Render-perf workflow watchdog pattern
A long-running render-perf optimization runs as a background Workflow with per-agent journaling at `subagents/workflows/<runId>/journal.jsonl`. Recovery procedure for a hung run: inspect the journal for started-but-no-result agents, check orphaned boot processes via `pgrep love/xvfb/oracle-check`, stop the workflow, kill orphans, constrain the offending agent, and resume from the runId.
<!-- session:2026-06-13-4671d5b8 | commit:dae9c2bf172ffc367e73a9fc26d791b585470556 | files:docs/PERF_RENDER_AND_REWRITE_FOUNDATION.md | area:docs | date:2026-06-13 -->

### Cryptid gameset crash
`lib/gameset.lua:812` calls global `cry_items_for_set` (nil value) via the `gameset_config_UI` → `ccl` chain originating from `items/code.lua` and `items/pointer.lua:141` click handlers. Crash surfaces when viewing items in a set from the collection/settings screen.
<!-- session:2026-06-13-e247edbd | commit:6b16881ef78dbb20305da78ce7af5933a70277ba | files:(Cryptid mod) lib/gameset.lua,items/code.lua,items/pointer.lua | area:items | date:2026-06-13 -->

### Framerate degradation profile
FPS tanks when jokers sit idle (not just during scoring), and tanks harder during scoring even with scoring animations off. This points at per-frame allocation/iteration over inactive effects rather than animation cost — the idle-joker case implicates the effect dispatch loop walking all jokers every frame. There is a `patches/idle-joker-perf.lua` patch present in the working tree.
<!-- session:2026-06-13-e247edbd | commit:6b16881ef78dbb20305da78ce7af5933a70277ba | files:patches/idle-joker-perf.lua | area:patches | date:2026-06-13 -->

### Performance root-cause hypothesis
The developer attributes slowdown to Lua's JIT overhead and GC pauses, plus effects authored in a poorly-performing patch-based style. Target architecture: non-allocating active-list effect dispatch (only iterate active effects), deterministic tick loop, data-oriented-design entity store — modeled on Factorio's deterministic, GC-free efficiency.
<!-- session:2026-06-13-e247edbd | commit:6b16881ef78dbb20305da78ce7af5933a70277ba | date:2026-06-13 -->

### VERIFIED against Balatro's real engine
an O/DynaText HUD value node lays out byte-identical to a self-measuring T text node. Ran tools/uiref with USE_DYNATEXT=1 (extracted engine/text.lua + stubbed EMPTY/format_ui_value/utf8.chars/number_format into main.lua): every value node became UIT.O and the whole HUD geometry (w=3.23 h=4.542) + each value w/h (Hands 0.352x0.664, Money 0.704x0.664, Ante 1.056x0.664, etc.) matched the tracked T-node hud_geometry.ref.txt to 4 decimals, same x. This pins the native interpreter's O-sizing (objSize reads object.T.w == T branch's inline font:getWidth measure). Default mode (no env) reproduces the tracked reference exactly — no R/C/T/B regression.
<!-- session:2026-06-14-be67cd38 | commit:5a513e22ee22a53f0767514934a4767dd3ce9cf1 | files:.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Oracle.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Scoring.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/content/Content.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Board.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Hands.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/BigValue.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/ui/MainActivity.kt,.claude/worktrees/dp-head/rebuild/app/src/main/AndroidManifest.xml,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/bridge/Telemetry.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Cards.kt | area:.claude | date:2026-06-14 -->

### Self-reviewed the O-node interpreter diff (UIBox.kt + RunScreen.kt hudRound) end-to-end after the multi-edit build: clean, no defects. Confirmed (1) DynaText seg value-lambdas are invoked inside RenderDynaText's @Composable body (val text = s.value()), so RunState mutableStateOf reads subscribe correctly — bindings are live, not frozen; (2) objSize applying .width(minw*U) plus cfg()'s widthIn(min=minw*U) is a harmless redundant constraint for fixed-size O (sprites), and a no-op for the align=cm self-measuring HUD value O (no minw). No double-sizing conflict. Matches the engine-verified geometry. Ready to commit pending branch-ownership decision.
<!-- session:2026-06-14-be67cd38 | commit:5a513e22ee22a53f0767514934a4767dd3ce9cf1 | files:.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Oracle.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Scoring.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/content/Content.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Board.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Hands.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/BigValue.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/ui/MainActivity.kt,.claude/worktrees/dp-head/rebuild/app/src/main/AndroidManifest.xml,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/bridge/Telemetry.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Cards.kt | area:.claude | date:2026-06-14 -->

### VERIFIED Sprite-O sizing against Balatros real engine (tools/uiref USE_SPRITE_O=1): an O node with explicit config.w/h=0.5 and an embedded Moveable of intrinsic T=0.9x0.9 lays out at w=0.5000 h=0.5000 — proving config.w takes precedence over object.T.w (ui.lua:131 config.w or object.T.w). This is exactly the native interpreters objSize rule (if cfg.minw>0 cfg.minw else obj.w). Gotcha: the embedded O object MUST be a real Moveable (or Sprite) not a bare table — ui.lua:394 set_values calls object:set_role on every O unless config.no_role. Both O kinds now engine-verified: DynaText O self-measures equals T node; Sprite O sized by explicit config.w/h. Default uiref run still byte-identical to hud_geometry.ref.txt (both probes gated by env vars).
<!-- session:2026-06-14-be67cd38 | commit:5a513e22ee22a53f0767514934a4767dd3ce9cf1 | files:.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Oracle.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Scoring.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/content/Content.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Board.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Hands.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/BigValue.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/ui/MainActivity.kt,.claude/worktrees/dp-head/rebuild/app/src/main/AndroidManifest.xml,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/bridge/Telemetry.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Cards.kt | area:.claude | date:2026-06-14 -->

### Audited all 157 O nodes in UI_definitions.lua to validate the O-node config deferral decisions empirically: outline/outline_colour on O = 0 occurrences (deferring outline-on-O correct); draw_layer = only 2/157 (floating-text popup :919, blind sprite :1232 — z-order for overlap, Compose declaration-order covers the common case); hover = 6, can_collide = 8 (controller/touch concerns, correctly deferred). None of the deferred keys appear on any node the current HUD port renders. The synthesis handle-now (sprite/dynatext/shadow/maxw/button-feel) vs defer (draw_layer/float/bump/rotate/pop_in/focus/hover/can_collide) split is data-backed, not just judgment — no silently-wrong deferral.
<!-- session:2026-06-14-be67cd38 | commit:5a513e22ee22a53f0767514934a4767dd3ce9cf1 | files:.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Oracle.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Scoring.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/content/Content.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Board.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Hands.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/BigValue.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/ui/MainActivity.kt,.claude/worktrees/dp-head/rebuild/app/src/main/AndroidManifest.xml,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/bridge/Telemetry.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Cards.kt | area:.claude | date:2026-06-14 -->

### Rebuild app isolation
The Kotlin/Compose rebuild is package `systems.balatro.rebuild`, a completely separate app from the LÖVE game (`systems.shorty.lmm`). Never touch the LÖVE build or its saves when working the rebuild.
<!-- session:2026-06-14-28a6dd10 | commit:5a513e22ee22a53f0767514934a4767dd3ce9cf1 | files:rebuild/app/src/main/kotlin/systems/balatro/content/Content.kt | area:rebuild | date:2026-06-14 -->

### Rebuild build/verify loop
Build via `nix-shell shell.nix --run 'gradle --no-daemon :app:assembleDebug'`; install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`; self-verify by pulling on-device telemetry with `adb exec-out "run-as systems.balatro.rebuild cat files/telemetry.log"` (check for no CRASH, healthy fps, brick-specific events).
<!-- session:2026-06-14-28a6dd10 | commit:5a513e22ee22a53f0767514934a4767dd3ce9cf1 | files:.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/content/Content.kt | area:.claude | date:2026-06-14 -->

### Recent HUD porting pattern
Prior commits on this branch port LÖVE HUD content (hand readout, chips/mult, dollars, blind/debuff lines) through a "UIBox interpreter" — bindings like `chip_text` vs `chip_total_text` are distinct and must not be conflated.
<!-- session:2026-06-14-28a6dd10 | commit:5a513e22ee22a53f0767514934a4767dd3ce9cf1 | files:rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt | area:rebuild | date:2026-06-14 -->

### Strangler-fig rebuild architecture
The project is migrating from a Lua monkey-patch layer (`patches/*.lua` over `Balatro.love` + Cryptid mod) to a native Kotlin/Compose rebuild under package `systems.balatro.rebuild`, installed as a SEPARATE app from the LÖVE build (`systems.shorty.lmm`). The rebuild uses an ECS (composition) and a registered-effect system instead of inheritance/patching.
<!-- session:2026-06-14-2bd8151c | commit:8656b205b1c885a0c7dbca8eeb0a28e954eacc77 | files:rebuild/app/src/main/kotlin/systems/balatro/engine/Ecs.kt,rebuild/README.md | area:rebuild | date:2026-06-14 -->

### Oracle-parity as the migration safety net
Correctness of the port is proven by scoring boards through the Kotlin engine and asserting against score-oracle goldens (`test/score-oracle-baselines.txt`). "Ported = scores like the original" is the gate for each content wave.
<!-- session:2026-06-14-2bd8151c | commit:8656b205b1c885a0c7dbca8eeb0a28e954eacc77 | files:rebuild/app/src/main/kotlin/systems/balatro/game/Oracle.kt,rebuild/app/src/main/kotlin/systems/balatro/game/Scoring.kt | area:rebuild | date:2026-06-14 -->

### Content port pipeline
Cryptid jokers come from `mods/Cryptid/items/*.lua` and are translated into Kotlin `Effect`s registered via the effect system, each verified by the oracle harness. Real game assets are reused from `app/src/main/assets/textures` and pulled from `src/Balatro.love` / `mods/Cryptid/assets`.
<!-- session:2026-06-14-2bd8151c | commit:8656b205b1c885a0c7dbca8eeb0a28e954eacc77 | files:rebuild/app/src/main/kotlin/systems/balatro/content/Jokers.kt | area:rebuild | date:2026-06-14 -->

### Build/deploy loop for the rebuild
Built via `nix-shell shell.nix --run 'gradle --no-daemon :app:assembleDebug'`, installed with `adb install -r`, and self-verified by reading `run-as systems.balatro.rebuild cat files/telemetry.log` for crashes/fps/expected events. Telemetry is first-class.
<!-- session:2026-06-14-2bd8151c | commit:8656b205b1c885a0c7dbca8eeb0a28e954eacc77 | files:rebuild/app/src/main/kotlin/systems/balatro/bridge/Telemetry.kt | area:rebuild | date:2026-06-14 -->

### UIBox O-node design
Balatro's `G.UIT.O` (object) nodes can be modeled as a single terminal `Ob(cfg, obj)` leaf added to the `UI` sealed interface, with `obj: Obj` a sealed hierarchy of `Sprite` (pre-cropped ImageBitmap + unit w/h) and `DynaText` (list of value-provider lambdas with per-segment colour/scale). O nodes are terminal and flow horizontally like C/B/T, reserving their object's size via `config.w or object.T.w` (mirroring `calculate_xywh`), so they need no container logic.
<!-- session:2026-06-14-c6c21629 | commit:83a8d5b34f728f2433cc2fdc4b564d24071fcacf | files:.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt | area:.claude | date:2026-06-14 -->

### DynaText live binding via Compose
Because `RunState` fields are Compose `mutableStateOf` (`handsLeft`, `roundScore`, `dollars`), reading them inside a `() -> String` provider lambda subscribes the composable, so a state change recomposes only that DynaText. This replaces Balatro's `UIElement:update_text` ref_table/prev_value polling for free.
<!-- session:2026-06-14-c6c21629 | commit:83a8d5b34f728f2433cc2fdc4b564d24071fcacf | files:.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt | area:.claude | date:2026-06-14 -->

### Pixel-art rendering detail
Sprite O nodes reuse the existing `Image(ImageBitmap, …)` path with cells cropped once upstream by `CardArt.cache`/`JokerArt.cache` (`Bitmap.createBitmap(atlas, col*142, row*190, 142, 190)`). `FilterQuality.None` is load-bearing — Balatro draws nearest-neighbour pixel art and Compose defaults to linear, which would blur the 142px cells.
<!-- session:2026-06-14-c6c21629 | commit:83a8d5b34f728f2433cc2fdc4b564d24071fcacf | files:.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt | area:.claude | date:2026-06-14 -->

### Hand card arc rendering
The fan/arc of cards in hand is driven by per-card rotation + vertical offset in `Spring.kt`; this had been repeatedly broken and required correcting the curve math to match the LÖVE original.
<!-- session:2026-06-14-e1c5d40d | commit:15624d1501125a80a7740a55588e484d4c132295 | files:rebuild/app/src/main/kotlin/systems/balatro/ui/Spring.kt,rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt | area:rebuild | date:2026-06-14 -->

### HUD faithful-port status
Cards, HUD, colours, and blind-select are faithful ports; SHOP, scoring-juice, and the jokers-row remain invented/non-faithful and are the outstanding fidelity gaps.
<!-- session:2026-06-14-e1c5d40d | commit:15624d1501125a80a7740a55588e484d4c132295 | files:rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt | area:rebuild | date:2026-06-14 -->

### Screenshot/verify workflow
Fidelity is verified on the emulator via a true deep-link run (`run-rebuild.sh` with `--ez run true`), not by deploying to Joe's phone.
<!-- session:2026-06-14-e1c5d40d | commit:15624d1501125a80a7740a55588e484d4c132295 | files:test/emulator/run-rebuild.sh | area:test | date:2026-06-14 -->

### Autonomous loop self-verification
Each brick runs end-to-end — implement → `nix-shell shell.nix --run 'gradle --no-daemon :app:assembleDebug'` → `adb install -r` + monkey-launch → pull `files/telemetry.log` via `run-as systems.balatro.rebuild` and check for CRASH/fps/brick-specific events before committing.
<!-- session:2026-06-14-be67cd38 | commit:5a513e22ee22a53f0767514934a4767dd3ce9cf1 | files:.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/bridge/Telemetry.kt | area:.claude | date:2026-06-14 -->

### Two separate Android apps share the device
`systems.balatro.rebuild` (the Kotlin port) is distinct from the LÖVE build `systems.shorty.lmm`; the rebuild work must never touch the LÖVE app or its saves.
<!-- session:2026-06-14-be67cd38 | commit:5a513e22ee22a53f0767514934a4767dd3ce9cf1 | files:.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Oracle.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Scoring.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Scoring.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/content/Content.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/content/Content.kt | area:.claude | date:2026-06-14 -->

### UI ground-truth harness
`tools/uiref` is a headless LÖVE harness built to render the original canvas for porting reference, rather than designing Compose UI from scratch.
<!-- session:2026-06-14-be67cd38 | commit:5a513e22ee22a53f0767514934a4767dd3ce9cf1 | files:.claude/worktrees/dp-head/tools/uiref/main.lua,.claude/worktrees/dp-head/tools/uiref/conf.lua | area:.claude | date:2026-06-14 -->

### LÖVE UI primitives ported, not reinvented
UIBox (layout + dynatext), Spring, and Juice are direct ports of the Lua canvas primitives; this is the chosen foundation for matching look/feel/animation.
<!-- session:2026-06-14-be67cd38 | commit:5a513e22ee22a53f0767514934a4767dd3ce9cf1 | files:.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/ui/UIBox.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/ui/Spring.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/ui/Juice.kt | area:.claude | date:2026-06-14 -->

### Faithful-port discipline order
The agreed sequence is Lua→Kotlin parity FIRST, then optimization, then efficiency fixes — do not optimize or refactor scoring before parity is reached.
<!-- session:2026-06-15-b057a023 | commit:8656b205b1c885a0c7dbca8eeb0a28e954eacc77 | files:rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt | area:rebuild | date:2026-06-15 -->

### Score.kt is the sole scoring engine
Recent commits deleted the composition engine entirely; `Score` is now the single authority. All joker effects must be ported into `Score.calcJoker` branches keyed by joker id (e.g. `"j_arrowhead"`).
<!-- session:2026-06-15-b057a023 | commit:8656b205b1c885a0c7dbca8eeb0a28e954eacc77 | files:rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt | area:rebuild | date:2026-06-15 -->

### Lua calculate_joker → Kotlin branch mapping
Each joker fires in a specific context — `individual` (per scored card, cardarea=="play"), `held` (cardarea=="hand"), `joker_main` (cardarea=="jokers" else-branch), `other_joker`. Effect fields map to `Fx`: `chips`/`chipMod`, `multMod`, `xMult`/`xMultMod`. Simple suit/chip jokers (Arrowhead, Baron) port cleanly; scaling jokers (Banner, Blue Joker, Acrobat) need external round/deck state not yet in `Sctx`.
<!-- session:2026-06-15-b057a023 | commit:8656b205b1c885a0c7dbca8eeb0a28e954eacc77 | files:rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt,rebuild/app/src/main/kotlin/systems/balatro/game/Cards.kt | area:rebuild | date:2026-06-15 -->

### UI-reference extractor
`tools/uiref/extract.lua` + `extract.sh` pull faithful layout/HUD values directly from the Lua source so the Kotlin HUD is data-driven rather than eyeballed.
<!-- session:2026-06-15-b057a023 | commit:8656b205b1c885a0c7dbca8eeb0a28e954eacc77 | files:tools/uiref/extract.lua,tools/uiref/extract.sh | area:tools | date:2026-06-15 -->

### Cryptid joker firing-context taxonomy
The bulk of Cryptid epic/exotic/M and spooky/code jokers are *non-scoring* — they fire only on lifecycle/economy contexts (`selling_self`, `end_of_round`, `setting_blind`, `buying_card`, `using_consumeable`, `reroll_shop`, `add_to_deck`/`remove_from_deck`) and emit no chips/mult/xMult during `evaluate_play`. Only a minority map to `joker_main`/`individual`/`repetition` and produce an `Fx`. When porting, the first classification is scoring vs non-scoring; non-scoring jokers get an empty `kotlin` body.
<!-- session:2026-06-16-63583927 | commit:770ae8918fadbefa882569c29f592280fa5cc34b | files:rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt | area:rebuild | date:2026-06-16 -->

### Accumulator pattern for scaling jokers
Scaling jokers store their current value in `card.ability.extra` fields (`mult`, `n`, `x`, `percent`) mutated via `SMODS.scale_card` in non-scoring contexts, then `joker_main` reads that accumulated value guarded by a threshold (e.g. `if (j.x > 1.0) xMultMod = j.x`). The engine models this by reading `j.x`/`j.mult`/`j.n` directly; the scaling increments themselves happen outside the scoring path. Examples: `j_cry_cut` (+0.5 xMult per Code card destroyed), `j_cry_python` (+0.15 xMult per Code consumable used), `j_cry_green_joker`-style mult accumulators.
<!-- session:2026-06-16-63583927 | commit:770ae8918fadbefa882569c29f592280fa5cc34b | files:rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt | area:rebuild | date:2026-06-16 -->

### Economy jokers use `calc_dollar_bonus`, not Fx
Jokers like `j_cry_number_blocks`, `j_cry_goldjoker` accumulate a `money`/`percent` field via `SMODS.scale_card` during `individual` scoring, but pay out through `calc_dollar_bonus` at end of round — they produce no scoring `Fx` despite firing during `individual`.
<!-- session:2026-06-16-63583927 | commit:770ae8918fadbefa882569c29f592280fa5cc34b | files:rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt | area:rebuild | date:2026-06-16 -->

### Engine dispatch gaps
Some Cryptid jokers can't be faithfully represented because the engine lacks the dispatch hook. `j_cry_spectrogram` and `j_cry_mstack`-adjacent retriggering rely on `context.retrigger_joker_check` (retrigger *other jokers* N times), which has no `Fx` analog. `j_cry_blacklist` zeroes `hand_chips`/`mult` as a global side-effect rather than returning an `Fx`. `j_cry_circus` reads per-rarity xMult from runtime `card.ability.extra` that `FJoker` has no field for (falls back to base config values — medium confidence). These were flagged rather than forced.
<!-- session:2026-06-16-63583927 | commit:770ae8918fadbefa882569c29f592280fa5cc34b | files:rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt | area:rebuild | date:2026-06-16 -->

### Two-branch joker scoring pattern
Scaling Cryptid jokers follow a consistent shape in the Kotlin engine — an accumulator branch (e.g. `individual`, `before`, `selling_card`, `buying_card`) mutates `j.mult`/`j.x`/`j.chips`, and a `joker_main` read branch emits `Fx().apply { multMod/xMultMod/chipMod = ... }` guarded by `> 0`/`> 1`. Reference exemplars: `j_cry_krustytheclown` (mult), `j_cry_wee_fib` (mult), `j_cry_eternalflame` (xmult).
<!-- session:2026-06-16-3d6357a8 | commit:c0519035010a8593d15910b499b7889c036167ab | files:rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt | area:rebuild | date:2026-06-16 -->

### Fx class field gaps block several Cryptid jokers
The engine's `Fx` class supports only `chipMod` (+chips), `multMod` (+mult), `xMultMod` (Xmult), and `repetitions`. Cryptid-specific effects can't be represented faithfully: `j_cry_big_cube` needs Xchip (chip multiplier, not flat chips), and `j_cry_happyhouse` needs Emult (exponential mult). These were left empty pending an engine extension rather than mis-mapped to chipMod.
<!-- session:2026-06-16-3d6357a8 | commit:c0519035010a8593d15910b499b7889c036167ab | files:rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt | area:rebuild | date:2026-06-16 -->

### Many Cryptid "jokers" have no scoring effect
A large fraction are economy/utility jokers (Lucky Joker, Compound Interest, Booster, Pickle, Pot of Jokes, Queen's Gambit, Seal The Deal) that fire on non-scoring contexts (`end_of_round`, `skip_blind`, `buying_card`, `destroying_card`) and contribute no chips/mult/xmult — correctly producing empty Kotlin branches.
<!-- session:2026-06-16-3d6357a8 | commit:c0519035010a8593d15910b499b7889c036167ab | files:/home/jluker/.claude/projects/-home-jluker-balatro-cryptid-mobile--claude-worktrees-dp-head/480c94b5-aea2-4c07-b84d-fe02a3e8978c/workflows/scripts/cryptid-joker-parity-wf_ebf56ad9-315.js,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Hands.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Hands.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt | area:.claude | date:2026-06-16 -->

### External-state dependencies
Some accumulators depend on per-round game state unavailable at engine call time — e.g. `j_cry_dropshot` needs `G.GAME.current_round.cry_dropshot_card.suit`. These require external state wiring beyond the scoring branch.
<!-- session:2026-06-16-3d6357a8 | commit:c0519035010a8593d15910b499b7889c036167ab | files:/home/jluker/.claude/projects/-home-jluker-balatro-cryptid-mobile--claude-worktrees-dp-head/480c94b5-aea2-4c07-b84d-fe02a3e8978c/workflows/scripts/cryptid-joker-parity-wf_ebf56ad9-315.js,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Hands.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Hands.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt | area:.claude | date:2026-06-16 -->

### Vanilla reference capture
`test/ref-autorun.lua` boots the real LÖVE Balatro headless to a known state (SELECTING_HAND) and captures a screenshot to use as a pixel-diff target; the tutorial must be skipped to get a clean reference.
<!-- session:2026-06-16-9cbada53 | commit:8656b205b1c885a0c7dbca8eeb0a28e954eacc77 | files:test/ref-autorun.lua | area:test | date:2026-06-16 -->

### Multi-lens visual diff
A workflow ran 6 parallel "lens" agents (card rendering, felt/background, HUD sidebar embossing, typography/palette, buttons, overall composition) each producing ranked deltas with file:line fixes — an effective harness for grounding UI parity work against a reference image.
<!-- session:2026-06-16-9cbada53 | commit:8656b205b1c885a0c7dbca8eeb0a28e954eacc77 | files:/home/jluker/.claude/projects/-home-jluker-balatro-cryptid-mobile/memory/rebuild-oracle-harness-headless.md,/home/jluker/.claude/projects/-home-jluker-balatro-cryptid-mobile/memory/MEMORY.md,.claude/worktrees/dp-head/test/kt-oracle.sh,.claude/worktrees/dp-head/justfile,.claude/worktrees/dp-head/rebuild/port-notes/cryptid-jokers-translations.json | area:.claude | date:2026-06-16 -->

### RunScreen parity levers
card width (`cardWidth`), felt radial gradient center/edge colors, `UIBox` emboss alpha/width for chunky beveled panels, `Spring.kt` SpringHand height multiplier, and button `canPlay` logic all directly govern how close the play screen reads to real Balatro.
<!-- session:2026-06-16-9cbada53 | commit:8656b205b1c885a0c7dbca8eeb0a28e954eacc77 | files:rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt,rebuild/app/src/main/kotlin/systems/balatro/ui/UIBox.kt,rebuild/app/src/main/kotlin/systems/balatro/ui/Spring.kt | area:rebuild | date:2026-06-16 -->

### Pixel-diff harness for UI parity
A diff harness that forces the emulator to the reference resolution, then subtracts matching pixels so only divergences remain (magenta), is the effective tool for chasing pixel-perfect parity — visual side-by-side is too coarse. Pure black = match; any color = a real layout bug to fix, no cheating.
<!-- session:2026-06-17-0b5aafd1 | commit:8656b205b1c885a0c7dbca8eeb0a28e954eacc77 | files:rebuild/app/src/main/kotlin/systems/balatro/ui/MainActivity.kt | area:rebuild | date:2026-06-17 -->

### Authoritative HUD geometry lives in the Lua
HUD box sizes, numbers, and placements should be derived directly from the real Balatro Lua UI code (extracted via `tools/uiref/extract.lua`) rather than eyeballed — Joe repeatedly pushed back that values were "all wrong" until they were sourced from Lua.
<!-- session:2026-06-17-0b5aafd1 | commit:8656b205b1c885a0c7dbca8eeb0a28e954eacc77 | files:tools/uiref/extract.lua,tools/uiref/main.lua,rebuild/app/src/main/kotlin/systems/balatro/ui/HudSpec.kt | area:tools | date:2026-06-17 -->

### UIBox sizes to content
Lua's UIBox sizes nodes to their content/stream; the Kotlin port initially did not. Match the *observed output* of Lua's layout, not a blind line-by-line transcription ("do it so it shows up exactly how it does in lua").
<!-- session:2026-06-17-0b5aafd1 | commit:8656b205b1c885a0c7dbca8eeb0a28e954eacc77 | files:rebuild/app/src/main/kotlin/systems/balatro/ui/UIBox.kt | area:rebuild | date:2026-06-17 -->

### Emulator, not phone, for the iterate loop
Build/deploy/capture cycles run on the emulator (`/tmp/fan.png`, `/tmp/diff` outputs); the phone (over Tailscale, `100.71.245.38:33203`) is only touched when Joe explicitly approves.
<!-- session:2026-06-17-0b5aafd1 | commit:8656b205b1c885a0c7dbca8eeb0a28e954eacc77 | files:.claude/worktrees/dp-head/.gitignore,/home/jluker/.claude/projects/-home-jluker-balatro-cryptid-mobile/memory/do-not-merge-add-debugplus-into-main.md,/home/jluker/.claude/projects/-home-jluker-balatro-cryptid-mobile/memory/MEMORY.md,tools/uiref/main.lua,tools/uiref/main.lua | area:memory | date:2026-06-17 -->

### Balatro card shadows
Shadows are an explicit rendered element in Balatro's draw code (offset/colour-driven), not a side effect of the rasterizer — port the shadow algorithm from source rather than attributing the gap to anti-aliasing.
<!-- session:2026-06-18-3c3fcbf1 | commit:f1817e442bcfbfcc00abea67a1f4dbdc32f7f9ac | files:rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt | area:rebuild | date:2026-06-18 -->

### Pixel-diff residuals have real causes
Felt-masked diff gaps trace to concrete, fixable sources (descent-trim, descender clip, offset, colour, missing shadow, exact card positions, popup/art placement) — measure the cause, don't hand-wave "anti-aliasing."
<!-- session:2026-06-18-3c3fcbf1 | commit:f1817e442bcfbfcc00abea67a1f4dbdc32f7f9ac | files:rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt | area:rebuild | date:2026-06-18 -->

### Claude Desktop image rendering
The user repeatedly could not see inline/attached images in the Claude Desktop app; delivering images one at a time (and using a magenta diff overlay) was the workaround that eventually rendered.
<!-- session:2026-06-18-3c3fcbf1 | commit:f1817e442bcfbfcc00abea67a1f4dbdc32f7f9ac | files:.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/ui/BalatroStyle.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/ui/BalatroStyle.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/ui/BalatroStyle.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/ui/BalatroStyle.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt | area:.claude | date:2026-06-18 -->

### Room-unit layout model
Balatro positions all card areas in a 22u × 12.9u "room" via `set_screen_positions` (common_events.lua). Each area's screen-space top-left = its `T.x/T.y` + `ROOM.T.x`(1.0)/`ROOM.T.y`(0.4375). Port the algorithm and place areas via absolute offsets in px (`xu*u`), not weight-based Compose Rows/Columns.
<!-- session:2026-06-18-991f5a2f | commit:f1817e442bcfbfcc00abea67a1f4dbdc32f7f9ac | files:rebuild/app/src/main/kotlin/systems/balatro/ui/UILayout.kt,rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt | area:rebuild | date:2026-06-18 -->

### Derived area constants (room units)
jokers(5.7573, 0.4375), played(6.5744, 5.7238 resting), hand(5.8573, 9.3238 — exact match), deck(18.2463, 9.3328 — right-anchored in box), consumeables(15.9963, 0.4375 — oracle-only, unverified). Card sprite = 2.04878u × 2.75122u, taller than box H (2.614u) → 12px overhang, center sprite on box.
<!-- session:2026-06-18-991f5a2f | commit:f1817e442bcfbfcc00abea67a1f4dbdc32f7f9ac | files:rebuild/app/src/main/kotlin/systems/balatro/ui/UILayout.kt | area:rebuild | date:2026-06-18 -->

### Unit-scale branch ambiguity
`love.resize` (main.lua:1229) picks width- vs height-constrained `u` by aspect vs orig_ratio (22/12.9 = 1.70543). At true 16:9 (3840×2160) the game is height-constrained (u=167.44, side pillars). But `bref_3` is width-constrained (u=174.5455), meaning it was rendered at an aspect <1.70543. Matching bref_3 exactly requires forcing the width branch; a true-16:9 device will legitimately differ.
<!-- session:2026-06-18-991f5a2f | commit:f1817e442bcfbfcc00abea67a1f4dbdc32f7f9ac | files:tools/uiref/verify_layout.py,tools/uiref/extract.lua,tools/uiref/verify_layout.py,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/ui/UIBox.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/ui/UIBox.kt | area:tools | date:2026-06-18 -->

### Box vs sprite parity
Compare CardArea *box* positions, not rendered sprite pixels. Sprite bounds differ from box numbers by overhang, shadows, rounded corners, hover-lift, and fan rotation (10–50px gaps that are NOT positioning errors).
<!-- session:2026-06-18-991f5a2f | commit:f1817e442bcfbfcc00abea67a1f4dbdc32f7f9ac | files:tools/uiref/verify_layout.py,tools/uiref/extract.lua,tools/uiref/verify_layout.py,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/ui/UIBox.kt,.claude/worktrees/dp-head/rebuild/app/src/main/kotlin/systems/balatro/ui/UIBox.kt | area:tools | date:2026-06-18 -->

### Engine spine is the root dependency
Every renderable, animation, and interaction in Balatro bottoms out in four live primitives — GameClock/TimerRegistry → Transform (T/VT double-buffer) → Moveable (spring/juice integrators) → EventManager (fixed 1/60 drain). Nothing above the spine (card rest positions, HUD bumps, scoring pops, shop slides, blind dissolves) can be faithful while the layer below is hard-coded; they are *outputs of the spine*, not independent effects.
<!-- session:2026-06-18-0dbb3055 | commit:f1817e442bcfbfcc00abea67a1f4dbdc32f7f9ac | files:rebuild/app/src/main/kotlin/systems/balatro/engine/GameClock.kt,rebuild/app/src/main/kotlin/systems/balatro/engine/Transform.kt,rebuild/app/src/main/kotlin/systems/balatro/engine/Moveable.kt,rebuild/app/src/main/kotlin/systems/balatro/engine/EventManager.kt,rebuild/ENGINE_PORT_P0.md | area:rebuild | date:2026-06-18 -->

### Hard dependency ordering for the port
Transform before everything; NodeLifecycle/SceneGraph before RoleHierarchy/AlignmentSystem/MoveOrchestrator; JuiceAnimation before LerpSpringR/Scale (they read juice.r/juice.scale); G_Timers/EventManager before Controller and all ease_*/scoring orchestration; UILayout passes (XYWH→SetWH→Alignment→VTInit) before UIBoxRecalculate; FontMetrics before XYWHPass and DynaTextCore; PCentersRegistry+SMODSGameObject before every Center subclass and before CalculateDispatch; CalculateDispatch before ScoringOrchestrator; PseudoRNG before deck/shop/pack generation.
<!-- session:2026-06-18-0dbb3055 | commit:f1817e442bcfbfcc00abea67a1f4dbdc32f7f9ac | files:rebuild/ENGINE_PORT_P0.md | area:rebuild | date:2026-06-18 -->

### EventManager replaces fixed-delay scoring
The old scoring cascade used a `LaunchedEffect` with hard 140/300/450ms delays; this diverges from the engine the moment a joker inserts a follow-on event. The engine timing is event-driven and fixed-step (1/60 drain, blocking/blockable gating, SPEEDFACTOR fast-forward), so the cascade must be modeled as chained Events on the EventManager.
<!-- session:2026-06-18-0dbb3055 | commit:f1817e442bcfbfcc00abea67a1f4dbdc32f7f9ac | files:rebuild/app/src/main/kotlin/systems/balatro/engine/EventManager.kt | area:rebuild | date:2026-06-18 -->

### Coverage fidelity tiers
Current rebuild state stratifies as: UIBox 3-pass layout + node types = live-faithful (geometry-at-rest, 80/80); Moveable integrator = live-faithful but only ran for hand cards (no per-frame loop driving it); DynaText value-bump = approximate spring (not exact juice_up); set_screen_positions = frame-matched (frozen oracle dump, not live-computed); Score.kt = logic-only. Carry these tiers forward as explicit not-done caveats rather than letting them imply "done."
<!-- session:2026-06-18-0dbb3055 | commit:f1817e442bcfbfcc00abea67a1f4dbdc32f7f9ac | files:rebuild/app/src/main/kotlin/systems/balatro/ui/UILayout.kt,rebuild/app/src/main/kotlin/systems/balatro/ui/UIBox.kt | area:rebuild | date:2026-06-18 -->

### Card-position oracle
`test/cardpos.sh` + `test/cardpos-autorun.lua` boot real LÖVE Balatro headless and dump actual `G.<area>`/card `T.x/T.y/T.r` transforms (including the HAND_PLAYED scoring frame) as the parity target for the Kotlin `align_cards`/CardArea port.
<!-- session:2026-06-18-0dbb3055 | commit:f1817e442bcfbfcc00abea67a1f4dbdc32f7f9ac | files:test/cardpos.sh,test/cardpos-autorun.lua | area:test | date:2026-06-18 -->

### Ported vanilla '+chips if hand contains <type>' joker family (j_sly/wily/clever/devious/crafty) in Score.kt joker_main dispatch. Uses ctx.pokerHands containment (1:1 with card.lua:4209 context.poker_hands[type]), not top rank — so Clever fires on a Full House too. t_chips/type/pos verified from src/dump/game.lua:394-398. Added to CATALOG + JokerArt map (Jokers.png row 14, cols 0-4) and locked with Oracle case 'TwoPair 10s/7s + clever' = 268. Oracle 100/100.
<!-- session:2026-06-21-732a3b9f | commit:362bb95b9d06a5a7bd52cad89d25707fd32edc27 | date:2026-06-21 -->

### Ported vanilla '+Mult if hand contains <type>' family (j_jolly/zany/mad/crazy/droll) alongside the t_chips family. Score.kt joker_main, ctx.pokerHands containment, 1:1 with card.lua:4203 (mult_mod=t_mult). t_mult/type/pos from src/dump/game.lua:389-393; art Jokers.png row 0 cols 2-6. Oracle baselines jolly Pair=320, mad Two Pair=648. Both families committed d209335; oracle 102/102.
<!-- session:2026-06-21-732a3b9f | commit:362bb95b9d06a5a7bd52cad89d25707fd32edc27 | date:2026-06-21 -->

### Photograph debuff bug (found by adversarial audit, fixed efba943): port's first-face firstOrNull scanned scoringHand incl debuffed cards, but vanilla Card:is_face (card.lua:1193) returns nil for debuffed BEFORE the Pareidolia check. Under boss suit-debuff with a debuffed face leading the hand, Photograph X2 was lost. Fix: thread debuffSuit into Sctx, exclude debuffed from the scan. Only photograph needed it (all other face/suit jokers react to oc, which the per-card loop already skips when debuffed). Oracle case: Pair S_K,H_K under The Goad = 80. 176/176.
<!-- session:2026-06-21-c07c5a8c | commit:ee549fe99be6c955b337c02498277308c2b8aa09 | date:2026-06-21 -->

### Balatro hand containment for conditional jokers
The +chips conditional jokers check hand *containment*, not the single top-ranked hand — a Full House contains a Pair and Three of a Kind, so a joker keyed to "Pair" fires on a Full House. The Kotlin port must consult the played hand's full set of evaluated categories.
<!-- session:2026-06-21-0647820b | commit:362bb95b9d06a5a7bd52cad89d25707fd32edc27 | files:rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt | area:rebuild | date:2026-06-21 -->

### Vanilla source mapping
This joker family lives at `game.lua:394-398`, set=Joker, `config = {t_chips=N, type='<HandType>'}` — t_chips values: j_sly +50 (Pair), j_wily +100 (Three of a Kind), j_clever +80 (Two Pair), j_devious +100 (Straight), j_crafty +80 (Flush).
<!-- session:2026-06-21-0647820b | commit:362bb95b9d06a5a7bd52cad89d25707fd32edc27 | files:rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt | area:rebuild | date:2026-06-21 -->

### Verified scoring gap
Two Pair (10,10,7,7) with j_clever scored 54 (= 20 base + 34 card chips) before the fix; correct is 134 (+80). This is the Oracle baseline assertion locking the family.
<!-- session:2026-06-21-0647820b | commit:362bb95b9d06a5a7bd52cad89d25707fd32edc27 | files:rebuild/app/src/main/kotlin/systems/balatro/game/Oracle.kt | area:rebuild | date:2026-06-21 -->

### Two-codebase delineation
The repo contains two distinct projects — a native Kotlin/Compose Balatro remake (the actual end goal; fidelity is its acceptance test) and the Lua/LÖVE Cryptid mod build. They must be kept structurally separate. See memory [[rewrite-is-the-goal-native-compose]].
<!-- session:2026-06-21-e40b8757 | commit:a3d8859e1df31560e1e9910344bb8cc93b9a9d4e | files:.claude/worktrees/relaxed-elbakyan-1c44d4/nix/update-sources.sh,.claude/worktrees/relaxed-elbakyan-1c44d4/nix/sources.nix,.claude/worktrees/relaxed-elbakyan-1c44d4/overlay/patches/series,.claude/worktrees/relaxed-elbakyan-1c44d4/overlay/game/README.md,.claude/worktrees/relaxed-elbakyan-1c44d4/overlay/config/README.md | area:.claude | date:2026-06-21 -->

### Bake-in over live-patch
Because the target environment can't live-patch, all mods/config must be baked into the build artifact ahead of time. The chosen mechanism is a Nix overlay: upstream sources are pinned (sources.nix), mods staged (stage-mods.sh), patches generated as an ordered series (gen-patches.sh, overlay/patches/series), and the whole thing assembled by a Nix derivation (balatro-cryptid.nix) driven by flake.nix.
<!-- session:2026-06-21-e40b8757 | commit:a3d8859e1df31560e1e9910344bb8cc93b9a9d4e | files:nix/balatro-cryptid.nix,nix/sources.nix,nix/stage-mods.sh,nix/gen-patches.sh,overlay/patches/series,flake.nix | area:nix | date:2026-06-21 -->

### Crash signature
`talisman/coroutine.lua:201: asize` — a Talisman (big-number) coroutine assertion under the stacked Steamodded mod set (Cryptid 0.5.14a, Amulet, Talisman, etc.) on Android/LÖVE 11.5. Surfaced during this session as the in-game crash to diagnose.
<!-- session:2026-06-21-e40b8757 | commit:a3d8859e1df31560e1e9910344bb8cc93b9a9d4e | files:.claude/worktrees/relaxed-elbakyan-1c44d4/nix/update-sources.sh,.claude/worktrees/relaxed-elbakyan-1c44d4/nix/sources.nix,.claude/worktrees/relaxed-elbakyan-1c44d4/overlay/patches/series,.claude/worktrees/relaxed-elbakyan-1c44d4/overlay/game/README.md,.claude/worktrees/relaxed-elbakyan-1c44d4/overlay/config/README.md | area:.claude | date:2026-06-21 -->

### Photograph "first face" debuff divergence
Port's `isFace` (`id in 11..13`, Cards.kt:31) does not exclude debuffed cards, but vanilla `Card:is_face` (card.lua:1192-1193) returns nil for debuffed cards. Under a boss suit-debuff where a debuffed face leads the scoring hand, `firstOrNull { it.isFace || ctx.pareidolia }` selects the debuffed card, the per-card loop `continue`s past it, and Photograph's X2 is lost — port scores lower than vanilla. Root-cause fix: exclude debuffed cards from the firstOrNull predicate (or have isFace honor debuff). Unique to Photograph because it scans `scoringHand` to pick a winner; sock_and_buskin/scary_face/smiley/chad react to `oc` alone (already skipped by `continue`).
<!-- session:2026-06-21-dd0f098f | commit:1774e409d81fcf053ed5d96b1155f4df323adeb8 | files:rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt,rebuild/app/src/main/kotlin/systems/balatro/game/Cards.kt | area:rebuild | date:2026-06-21 -->

### Retrigger timing/ordering is faithful
The `context.repetition` pass sums all joker retriggers into `reps` BEFORE the `repeat(reps)` scoring loop, matching vanilla's num_repetitions-then-repeat timing. `repetition=N` means N EXTRA triggers (corroborated by the red-seal baseline: H_A scoring 2x).
<!-- session:2026-06-21-dd0f098f | commit:1774e409d81fcf053ed5d96b1155f4df323adeb8 | files:rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt | area:rebuild | date:2026-06-21 -->

### Hanging Chad reference-identity translation
Vanilla `context.other_card == context.scoring_hand[1]` is Lua reference identity; port's `oc === ctx.scoringHand.firstOrNull()` is a faithful translation — value-equal duplicate cards do not spuriously match. A debuffed first card is a no-op in both (vanilla skips via calculate_main_scoring else-branch, port via `continue`).
<!-- session:2026-06-21-dd0f098f | commit:1774e409d81fcf053ed5d96b1155f4df323adeb8 | files:rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt | area:rebuild | date:2026-06-21 -->

### Mime held-card retrigger is score-equivalent, not literal
Port gates the held-card retrigger on "non-empty effect" rather than "all held cards." This is score-identical because vanilla itself only runs the held repetition pass when `flags.calculated` and Mime's gate `(next(card_effects[1]) or #card_effects>1)` is non-empty — a plain non-steel held card with no reacting joker scores 0 whether retriggered or not. The port replays the whole effects table (steel x1.5, Baron xMult, Shoot the Moon mult), matching vanilla.
<!-- session:2026-06-21-dd0f098f | commit:1774e409d81fcf053ed5d96b1155f4df323adeb8 | files:rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt | area:rebuild | date:2026-06-21 -->

### Oracle coverage boundary
14 remaining uncovered jokers require CRY_* custom hand types (CRY_BULWARK/CRY_CLUSTERFUCK/CRY_ULTPAIR/CRY_NONE/CRY_WHOLEDECK) and are legitimately dormant until those hand types are ported. Non-score face-reactors (Midas Mask, Business Card, Reserved Parking, Faceless) are out of scope for score parity — they grant dollars/probabilities, not chips/mult.
<!-- session:2026-06-21-dd0f098f | commit:1774e409d81fcf053ed5d96b1155f4df323adeb8 | files:rebuild/app/src/main/kotlin/systems/balatro/game/Oracle.kt | area:rebuild | date:2026-06-21 -->

### Debuff-vs-is_suit audit (fixed ad70134)
seeing_double_check (utils.lua:2474) tallies via is_suit WITHOUT bypass_debuff -> debuffed cards don't count; rebuild wrongly fired X2 when the only club/non-club was debuffed -> fixed by excluding ctx.debuffSuit. ASYMMETRY: Flower Pot calls is_suit(suit, true) bypass_debuff=true (card.lua:4358) -> DOES count debuffed suits, so rebuild's all-card scan is already correct, left unchanged. Flush detection (flush_calc branch) also ignores debuff. Lesson: don't assume the Photograph-class bug everywhere; trace each joker's is_suit call. Oracle 178/178 (seeing_double under The Club=40, flower_pot under The Goad=1953).
<!-- session:2026-06-22-2af51fd3 | commit:dee18c668693e232586704cfeb071ddd8989ccc2 | date:2026-06-22 -->

### 3 red oracle cases on main (218/221) were all WRONG EXPECTED VALUES, engine faithful (independently confirmed, anyRealEngineBug:false). (1) Baseball+2 Fibonacci: expected forgot fibs fire +8/Ace; isolated by using Kings → 135. (2) CRY_NONE+the/nebulous/undefined: joker_main applies in BOARD ORDER, base mult=0, cry_the(X2) listed first gave 150 not 300; reordered X2 last → 300 (order matters with additive+multiplicative on mult=0 base). (3) FullHouse+cry_clash: expected used base-only, forgot all 5 cards score (53 chips) → 372. Fixed via PR #17 off origin/main. Lesson: fast-authored oracle baselines tend to forget per-card joker mult, card chips, and board-order effects — recompute independently before trusting an expected value.
<!-- session:2026-06-22-7c52d4d8 | commit:9c9edd355e4689a1e1446e9998b5544391a516dd | files:.claude/worktrees/zen-bardeen-3344b2/rebuild/app/src/main/kotlin/systems/balatro/game/Oracle.kt | area:.claude | date:2026-06-22 -->

### Parity audit of main found a REAL high-impact engine bug (PR #18): Hands.evaluate had no poker-hand DOWNGRADE CHAIN. Vanilla misc_functions.lua:551-561 makes 5oak contain 4oak/3oak/pair, 4oak contain 3oak/pair, 3oak contain pair in poker_hands. Without it, ALL containment jokers (sly/wily/clever/jolly/zany/mad + Cryptid type family) silently failed to fire on 3oak/4oak/5oak (e.g. 4oak+wily gave 728 not 1428). Fix: add downgrade to results map via putIfAbsent (never to top). Oracle 221→224. Audit also flagged: (a) n-based jokers j.n uninitialized = run-loop wiring, that's open PR #16 not an engine bug (agents over-flagged); (b) CRY_BULWARK/CRY_CLUSTERFUCK early-return omits HIGH_CARD from poker_hands — unverified, niche, follow-up. Lesson: audit agents conflate run-loop wiring with engine bugs; verify each against source.
<!-- session:2026-06-22-7c52d4d8 | commit:9c9edd355e4689a1e1446e9998b5544391a516dd | files:.claude/worktrees/zen-bardeen-3344b2/rebuild/app/src/main/kotlin/systems/balatro/game/Oracle.kt,.claude/worktrees/zen-bardeen-3344b2/rebuild/app/src/main/kotlin/systems/balatro/game/Hands.kt | area:.claude | date:2026-06-22 -->

### CRY_BULWARK/CRY_CLUSTERFUCK poker_hands dropped HIGH_CARD (folded into PR #18). Vanilla composite hands (misc_functions.lua:562) are added to a results table that keeps base hands; get_highest (686) non-empty for any non-empty hand incl stones → HIGH_CARD always present. Rebuild early-returns set only the composite. Fix: add HIGH_CARD to both sets (CRY_NONE stays empty). giggly/dubious/filler now fire on Bulwark/Clusterfuck. Oracle 224→226. PR #18 now covers both poker_hands-completeness fixes (downgrade chain + CRY HIGH_CARD).
<!-- session:2026-06-22-80051b53 | commit:cd308a105262e779d12ff11b95d01f62060d376d | files:.claude/worktrees/relaxed-elbakyan-1c44d4/scripts/patch_main_lua.py,.claude/worktrees/relaxed-elbakyan-1c44d4/nix/balatro-cryptid.nix,.claude/worktrees/relaxed-elbakyan-1c44d4/scripts/build.sh | area:.claude | date:2026-06-22 -->

### SMODS nil crash at main.lua:1410
The patched `main.lua` indexes global `SMODS` before the mod loader (Lovely/SMODS) has defined it, crashing at boot. Root cause is patch ordering / missing shim, not a syntax error despite LÖVE's misleading "syntax error through patching" message.
<!-- session:2026-06-22-2af51fd3 | commit:dee18c668693e232586704cfeb071ddd8989ccc2 | files:scripts/patch_main_lua.py,overlay/patches/08-main_lua.patch | area:scripts | date:2026-06-22 -->

### LÖVE print → logcat
Piping LÖVE's `print` to Android logcat requires patching it in via `patch_main_lua.py` (no native passthrough), needed because emulator/phone boot failures are otherwise opaque.
<!-- session:2026-06-22-2af51fd3 | commit:dee18c668693e232586704cfeb071ddd8989ccc2 | files:scripts/patch_main_lua.py | area:scripts | date:2026-06-22 -->

### Legacy build-artifact dependency map
Phase 5 cleanup required mapping every reference to legacy build artifacts across src/config, tools/regen, justfile, docs, and build.sh before cutting, to avoid breaking deploy/regen.
<!-- session:2026-06-22-2af51fd3 | commit:dee18c668693e232586704cfeb071ddd8989ccc2 | files:justfile,scripts/build.sh,README.md,MIGRATION.md | area:scripts | date:2026-06-22 -->

### Oracle test failures were test-data bugs, not engine bugs
All three failing cases (baseball, cry_none_the, fullhouse_clash) had the engine computing the vanilla-faithful value; the expected values/joker orderings in the test file were wrong. Independent re-derivation from vanilla rules confirmed engine correctness before editing tests.
<!-- session:2026-06-22-7c52d4d8 | commit:9c9edd355e4689a1e1446e9998b5544391a516dd | files:rebuild/app/src/main/kotlin/systems/balatro/game/Oracle.kt | area:rebuild | date:2026-06-22 -->

### `j.n` is never initialized before `Score.score()`
A whole class of jokers scale by `j.n` (Stone +25/stone-card, Steel X(1+0.2n), Blue +2/deck-card, Banner +30/discard-left, Supernova +mult per hand-type-plays, Abstract +3/board-joker, Driver's License X3 if ≥16 enhanced). The scoring engine reads `j.n` faithfully, but the run loop (`RunScreen.kt`) never populates it, so all these jokers silently score zero bonus in real gameplay. Banner/Supernova should read context directly (`ctx.discardsLeft`, hand-type-play count) rather than `j.n`.
<!-- session:2026-06-22-7c52d4d8 | commit:9c9edd355e4689a1e1446e9998b5544391a516dd | files:rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt,rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt | area:rebuild | date:2026-06-22 -->

### Poker-hand downgrade chain is required for joker firing
Vanilla's `evaluate_poker_hand` populates `results[]` with all downgraded hand types (a 4-of-a-kind also registers THREE_OF_A_KIND and PAIR). Jokers that check "does this hand contain a PAIR/3oaK" depend on the full `pokerHands` set; omitting the chain breaks them (e.g. Wily, Sly, Jolly) on higher-rank hands — 350+ point divergences.
<!-- session:2026-06-22-7c52d4d8 | commit:9c9edd355e4689a1e1446e9998b5544391a516dd | files:rebuild/app/src/main/kotlin/systems/balatro/game/Hands.kt | area:rebuild | date:2026-06-22 -->

### Copy-joker port is faithful
Blueprint/Brainstorm/Old Blueprint target resolution (reference-identity match, depth guard `blueprintDepth > board.size`, copy applied at top of `calcJoker`) matches vanilla `blueprint_effect`. Minor acceptable gaps: omitted debuff/`blueprint_compat`/`no_blueprint` checks — negligible because debuffed/incompatible targets return null anyway.
<!-- session:2026-06-22-7c52d4d8 | commit:9c9edd355e4689a1e1446e9998b5544391a516dd | files:rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt | area:rebuild | date:2026-06-22 -->

### Supernova was wired WRONG, not merely uninitialized (correcting the parity-audit's 'j.n never initialized' framing). RunScreen set supernova.fj.n in scoreBank() — AFTER the score() call AND keyed to the previous hand's type — so it never reflected the hand it scored (off-by-one + wrong key). Vanilla reads G.GAME.hands[scoring_name].played, incremented at state_events.lua:747 BEFORE the joker_main pass (so it INCLUDES the current hand). Fix: engine reads new ctx.scoringPlays=(prior plays of scoring type)+1, set in Score.score() from a handTypePlays map; RunScreen passes pre-increment _handPlayed (same run-scoped counter Obelisk uses). Branch claude/jn-jokers, commit b32c0d3.
<!-- session:2026-06-22-96db4626 | commit:da97e5fd98e3fc5b17ccdb6df3c5d0a4b4de1fda | files:.claude/worktrees/jn-jokers/rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt,.claude/worktrees/jn-jokers/rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt,.claude/worktrees/jn-jokers/rebuild/app/src/main/kotlin/systems/balatro/game/Deck.kt,.claude/worktrees/jn-jokers/rebuild/app/src/main/kotlin/systems/balatro/game/Oracle.kt | area:.claude | date:2026-06-22 -->

### j_cry_boredom audit (REFUTED claim)
RunScreen.kt:719 DOES pre-resolve boredom each hand (o.fj.n = if Random.nextInt(2)==0 then 1 else 0), wired via owned.map{it.fj} -> Score.score at RunScreen.kt:673/782. Score.kt guards (j.n>0) at lines 173 (retrigger_joker_check) and 262 (repetition/G.play) both fire. Claim that 'j.n stays 0 permanently / no pre-resolve loop' is FALSE. REAL (distinct, low-sev) gap: epic.lua:873/886 calls SMODS.pseudorandom_probability per-card AND per-joker, and pseudoseed('cry_boredom_joker') advances state each call (misc_functions.lua:345) -> each card/joker rolls INDEPENDENTLY. Engine's single per-hand roll is all-or-nothing (every scored card+every other joker retrigger together, or none). Same expected count, wrong variance/correlation; not a deterministic score bug.
<!-- session:2026-06-22-05f2cbaf | commit:da97e5fd98e3fc5b17ccdb6df3c5d0a4b4de1fda | date:2026-06-22 -->

### RunScreen is the run-loop monolith
The bulk of run-loop UI/state (play-field, phases, cash-out, scoring, save/load wiring) lives in a single large `RunScreen.kt`, which is why nearly every turn touched it.
<!-- session:2026-06-22-cd1fc105 | commit:fe41ec4b8bad3ecc805baf3d5d9dd745ab9b9f85 | files:rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt | area:rebuild | date:2026-06-22 -->

### Snapshot tests relocated to save package
`RunSnapshotTest` moved from `game/` to `save/`, tracking the snapshot/IO code into the `save` package.
<!-- session:2026-06-22-cd1fc105 | commit:fe41ec4b8bad3ecc805baf3d5d9dd745ab9b9f85 | files:rebuild/app/src/test/kotlin/systems/balatro/save/RunSnapshotTest.kt,rebuild/app/src/main/kotlin/systems/balatro/save/RunSnapshot.kt,rebuild/app/src/main/kotlin/systems/balatro/save/SaveIo.kt | area:rebuild | date:2026-06-22 -->

### Recurring cry-joker bug class
the scoring engine (Score.kt) has the correct read-path (reads j.n/j.x/check) but the RUN LOOP never populates the accumulator/counter, so the joker is a no-op in real play. The score-oracle stays GREEN because oracle cases hard-code the activated state (e.g. FJoker(n=1,x=7)) — they test the math, not the run-loop wiring. Confirmed instances fixed in PR #21: jimball (j.x never scaled in scoreBank), happyhouse (check counter never incremented), biggestm (j.x never init to 7 at buy). Supernova (PR #19) was the same class. Lesson: oracle-green != joker-works; run-loop accumulator wiring needs separate scrutiny.
<!-- session:2026-06-22-aab57457 | commit:da97e5fd98e3fc5b17ccdb6df3c5d0a4b4de1fda | files:.claude/worktrees/cry-fixes/rebuild/app/src/main/kotlin/systems/balatro/game/Deck.kt,.claude/worktrees/cry-fixes/rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt,.claude/worktrees/cry-fixes/rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt,.claude/worktrees/cry-fixes/rebuild/app/src/main/kotlin/systems/balatro/game/Oracle.kt,.claude/worktrees/cry-fixes/rebuild/app/src/main/kotlin/systems/balatro/game/Hands.kt | area:.claude | date:2026-06-22 -->

### Reference capture is the parity oracle
The pixel-parity workflow compares the Kotlin rebuild's HUD against a headless capture of vanilla Balatro produced by `test/ref-autorun.lua` + `test/ref.sh`. If the reference capture is wrong, every downstream comparison is invalid — the reference must be verified correct before trusting any diff.
<!-- session:2026-06-22-ff71eda2 | commit:28dd30ac587b60552e8b74f310a449a2107a774a | files:test/ref-autorun.lua,test/ref.sh,rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt | area:test | date:2026-06-22 -->

### Supernova `.played` timing
Vanilla increments `G.GAME.hands[scoring_name].played` in `evaluate_play_intro()` (state_events.lua:747) BEFORE the joker_main pass, so Supernova's calc includes the current hand. Rebuild mirrors this with `scoringPlays = (handTypePlays[handType] ?: 0) + 1` at score time, while `recordHandPlayed()` runs later in `scoreBank()`. Supernova reads `ctx.scoringPlays` directly with no FJoker `.n` state (like j_mystic_summit).
<!-- session:2026-06-22-96db4626 | commit:da97e5fd98e3fc5b17ccdb6df3c5d0a4b4de1fda | files:rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt,rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt | area:rebuild | date:2026-06-22 -->

### n-scaling joker deck scoping
Stone/Steel count enhancements over the whole persistent deck (`Deck.all` ≈ vanilla `G.playing_cards`); Blue Joker counts only the undrawn draw pile (`deck.remaining` ≈ `G.deck.cards`); Abstract counts all owned jokers including itself (`owned.size`); Banner uses `discardsLeft`; Driver's License needs ≥16 enhanced cards for X3. Confirmed vanilla configs: Stone extra=25, Steel extra=0.2, Blue extra=2, Banner extra=30, Abstract extra=3, Driver's extra=3.
<!-- session:2026-06-22-96db4626 | commit:da97e5fd98e3fc5b17ccdb6df3c5d0a4b4de1fda | files:rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt,rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt,rebuild/app/src/main/kotlin/systems/balatro/game/Deck.kt | area:rebuild | date:2026-06-22 -->

### FJoker `.n` sync pattern
Per-joker scaling counts are populated in `RunScreen.syncFJokerN()` immediately before scoring; jokers that read live context (Supernova, Mystic Summit) bypass `.n` entirely. The Oracle harness compiles the self-contained `systems.balatro.game` package via `nix-shell -p kotlin` (no Android deps) to verify parity.
<!-- session:2026-06-22-96db4626 | commit:da97e5fd98e3fc5b17ccdb6df3c5d0a4b4de1fda | files:rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt,rebuild/app/src/main/kotlin/systems/balatro/game/Oracle.kt | area:rebuild | date:2026-06-22 -->

### Headless build/deploy pipeline
The APK is built and deployed entirely from teleos (headless) via background `gen-patches → build gameLove → smoke → signed APK → adb deploy` commands; ohtoaos is the interactive host. Screenshots on the phone are a last-resort verification — logs (`adb logcat` / crash dumps) are the primary signal and the user strongly prefers reading logs over screenshotting his live phone.
<!-- session:2026-06-22-80051b53 | commit:cd308a105262e779d12ff11b95d01f62060d376d | files:scripts/build.sh,nix/balatro-cryptid.nix,nix/gen-patches.sh | area:nix | date:2026-06-22 -->

### Programmatic patcher over quilt stack
A 57-entry quilt patch series was collapsed into `scripts/patch_main_lua.py` plus a much smaller set of patches. The programmatic approach replaced dozens of fragile context-dependent `.patch` files (perf caches, shader resets, UI guards) that were hard to maintain across upstream changes.
<!-- session:2026-06-22-80051b53 | commit:cd308a105262e779d12ff11b95d01f62060d376d | files:scripts/patch_main_lua.py,overlay/patches/series,overlay/patches/08-main_lua.patch | area:overlay | date:2026-06-22 -->

### GLSL-ES / shader-state on mobile
The "all-white canvas / broken buttons" class of bug stemmed from shader state not being reset between draw passes on the Android GLSL-ES path, not from UI-scale or layout logic. Reset-on-draw guards (the deleted `57-draw_shader_nil_reset` and successor programmatic hooks) target this.
<!-- session:2026-06-22-80051b53 | commit:cd308a105262e779d12ff11b95d01f62060d376d | files:overlay/patches/08-main_lua.patch | area:overlay | date:2026-06-22 -->

### Competing debug tools
The APK shipped overlapping/competing debug-logging tools (DebugPlus console, Steamodded dev logging, `android-telemetry.lua` OBS suite). DebugPlus crashed (`console.lua:793 attempt to get length of field 'logs'`) when its log level was cycled — a symptom of multiple uncoordinated logging subsystems. The consolidation goal is one suite where components register and talk to each other.
<!-- session:2026-06-22-80051b53 | commit:cd308a105262e779d12ff11b95d01f62060d376d | files:patches/android-telemetry.lua | area:patches | date:2026-06-22 -->

### Score.kt edition cascade bug
joker editions applied in one when-block AFTER joker_main (lines 714-720), but faithful SMODS fires Foil/Holo as pre_joker (BEFORE joker_main, game_object.lua:3616/3649) and Poly as post_joker (AFTER joker_main AND other_joker, game_object.lua:3682). Diverges when main is multiplicative/exponential. Confirmed scenario: Pair aces + Holo Broken Home (X11.4 main): faithful (2+10)*11.4=4377, engine 2*11.4+10=1049. Also Poly: Pair aces + mprime + Poly Jolly: faithful (10^1.05)*1.5=538, engine (10*1.5)^1.05=549. Oracle never tests editioned multiplicative-main joker so it's green. Bug-2 (batched other_joker post-pass) already refactored to interleaved per-joker — not present.
<!-- session:2026-06-22-002ecc85 | commit:e935dc9a61aa0f39da2f52366f64b95175c45920 | date:2026-06-22 -->

### Seltzer (j_selzer) audit
vanilla card.lua:3963-3969 retriggers every scored played card once (repetitions=1, unconditional, cardarea=G.play); extra=10 is a self-destruct counter (card.lua:4203), not a retrigger gate. Engine Score.kt repetition block (lines 211-233) has NO j_selzer case — but more decisively, j_selzer is absent from CATALOG (RunScreen.kt:180-439, the sole obtainable-joker pool feeding shop/Buffoon/Wraith), so a Seltzer can never enter a run. Not a reachable score divergence; it's a missing-content gap. Canonical math (pair of aces): faithful=108, engine=64 (would-be, if it existed). Verdict: refuted as a real in-play score bug; real gap is content-completeness.
<!-- session:2026-06-22-002ecc85 | commit:e935dc9a61aa0f39da2f52366f64b95175c45920 | files:.claude/worktrees/manifest-poc/rebuild/app/src/main/kotlin/systems/balatro/game/JokerManifest.kt,.claude/worktrees/manifest-poc/rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt,.claude/worktrees/manifest-poc/rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt | area:.claude | date:2026-06-22 -->

### Blacklist score-time vs lifecycle split
Score.kt (lines 429-432) implements only the score-time nullify (chips=0, mult=0 when blacklisted rank appears in played/held hand). The self-destruct is a separate concern: during `joker_main`, if the blacklisted rank is absent from G.play.cards, G.hand.cards, G.discard.cards, AND G.deck.cards, the joker calls `card:start_dissolve()` and removes itself.
<!-- session:2026-06-22-b208fa71 | commit:da97e5fd98e3fc5b17ccdb6df3c5d0a4b4de1fda | files:rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt | area:rebuild | date:2026-06-22 -->

### Engine zone-model limitation
The pure scoring engine (Score.kt) computes a single hand's score and has no board-mutation/joker-lifecycle phase, and no model of G.discard.cards or G.deck.cards. Lifecycle effects requiring discard pile + remaining deck contents must be handled in the run loop, not Score.kt.
<!-- session:2026-06-22-b208fa71 | commit:da97e5fd98e3fc5b17ccdb6df3c5d0a4b4de1fda | date:2026-06-22 -->

### Blacklist rank assignment
Cryptid's `add_to_deck` assigns the blacklisted rank via `pseudorandom_element(SMODS.Ranks, pseudoseed("cry_blacklist"..ante))` (default 14=Ace). The engine's handling of `j.n` assignment on acquisition for this joker needed verification.
<!-- session:2026-06-22-b208fa71 | commit:da97e5fd98e3fc5b17ccdb6df3c5d0a4b4de1fda | files:mods/Cryptid/items/spooky.lua | area:mods | date:2026-06-22 -->

### Pareidolia face-gating
Pareidolia is modeled as a board-level flag (`isFace(pareidolia)`) consumed by `Cards.isFace`, so every face-gated joker reads through one predicate rather than each joker re-checking the effect.
<!-- session:2026-06-22-bf6a8c27 | commit:423379f8bf4200e000b008ee1b3c1c183a2fa25f | files:rebuild/app/src/main/kotlin/systems/balatro/game/Cards.kt,rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt | area:rebuild | date:2026-06-22 -->

### UI displacement measurement workflow
A delta-of-delta block-matching workflow (search radius ±50px full-res at 3840x2160) quantifies per-region misalignment between the Kotlin rebuild (MINE) and the LÖVE reference (REF). Key findings: only 16% of blocks were well-aligned (<=5px), mean displacement 25.7px. Worst offenders — the three bottom-row action buttons (Play Hand, Sort Hand, Discard) uniformly shifted ~350-370px LEFT (a layout-anchor bug, not per-button sizing), and the blind-info HUD box ~65-70px off (left+down). Hand cards K/J/8 and lower HUD score boxes were near pixel-perfect (2-10px). Felt background must be masked out (G>R+8 & G>B+4 & G>45 & G<185) as uninformative.
<!-- session:2026-06-22-8072e619 | commit:340d468a812f18f3fbd575cab1a748a1108bf9c3 | files:rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt | area:rebuild | date:2026-06-22 -->

### Overlay diff requires brightness normalization
MINE renders with a different white balance than REF (per-channel bias R+7.2, G+13.4, B+11.0 over non-felt pixels) — the green channel skew is largest. Structural diffs must subtract this bias first, otherwise a global luma delta masks real positional errors.
<!-- session:2026-06-22-8072e619 | commit:340d468a812f18f3fbd575cab1a748a1108bf9c3 | files:rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt,rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt,rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt | area:rebuild | date:2026-06-22 -->

### Port, don't build
The correct methodology for the Balatro UI rebuild is to extract real layout data (positions, trees) from the running LÖVE original via `tools/uiref/extract.lua`, then make the Kotlin side consume that data — not hand-author UI that "looks close." Hand-building was explicitly rejected as fundamentally flawed.
<!-- session:2026-06-22-caec3b43 | commit:63f63801ca972d05448d03e9338f48d65f92285f | files:tools/uiref/extract.lua,tools/uiref/extract.sh,rebuild/app/src/main/kotlin/systems/balatro/ui/UILayout.kt,rebuild/app/src/main/kotlin/systems/balatro/ui/HudSpec.kt | area:tools | date:2026-06-22 -->

### Extraction scope is total
The extractor should capture *all* UI elements (HUD, offer cards, shop), not a curated subset. Partial extraction leaves the port guessing at the rest.
<!-- session:2026-06-22-caec3b43 | commit:63f63801ca972d05448d03e9338f48d65f92285f | files:tools/uiref/extract.lua | area:tools | date:2026-06-22 -->

### Reference comes from real LÖVE, not Lovely
The rebuild's UI is an independent Kotlin/Compose reimplementation; the parity target is captured by booting real Balatro under LÖVE (via `test/ref-autorun.lua`), not generated by the Lovely mod loader. Parity is an acceptance test of the port, not shared rendering.
<!-- session:2026-06-22-01df4f48 | commit:31e3cc39825fa6ca231f9267ba7b6c183c459130 | files:test/ref-autorun.lua,rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt | area:test | date:2026-06-22 -->

### Rasterization fidelity vs. layout error are different failure classes
Small per-element outline/edge residuals and gamma/brightness differences are rasterization-level (expected, low-priority); dramatically wrong card angles and whole-button placement are layout/algorithm errors. A naive full-frame pixel diff conflates the two — measuring a per-region displacement field separates "everything slightly fuzzy" from "this region is in the wrong place."
<!-- session:2026-06-22-01df4f48 | commit:31e3cc39825fa6ca231f9267ba7b6c183c459130 | files:rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt | area:rebuild | date:2026-06-22 -->

### FJoker initial-state convention
A joker's acquisition state is seeded in `buy()` via per-key when-blocks setting `fjN`/`fjXInit`/`fjMult` (and `fjChips`/`fjXc` for chip/xmult-carrying jokers like bonk). The engine never reads stale acquisition values for roll-based jokers — the before-hand run loop pre-resolves pseudorandom rolls and overwrites `j.n`/`j.x`/`j.mult` before scoring reads them.
<!-- session:2026-06-22-aab57457 | commit:da97e5fd98e3fc5b17ccdb6df3c5d0a4b4de1fda | files:rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt,rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt | area:rebuild | date:2026-06-22 -->

### Roll-based jokers are pre-resolved in the run loop, not at score time
busdriver (j.mult ±50), googol_play (j.x=1e100 on 1-in-8), and boredom (j.n=1/0 retrigger flag) all have their pseudorandom outcome computed in RunScreen.kt's before-hand loop; Score.kt only reads the pre-resolved value. Acquisition `initN` mirrors `config.extra.odds` so the fallback odds are correct (e.g. busdriver initN=4, googol_play relies on `if (n>0) n else 8`).
<!-- session:2026-06-22-aab57457 | commit:da97e5fd98e3fc5b17ccdb6df3c5d0a4b4de1fda | files:rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt,rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt | area:rebuild | date:2026-06-22 -->

### Jolly-identity reactions
bonk and mprime treat a board joker as "Jolly" if its key is `j_jolly`, `j_cry_jollysus`, or its edition is `cry_m`. bonk gives 18 chips (6×3) per Jolly vs 6 per non-Jolly; this is read from `j.chips`/`j.xc`, so those must be seeded at buy() or the joker contributes 0.
<!-- session:2026-06-22-aab57457 | commit:da97e5fd98e3fc5b17ccdb6df3c5d0a4b4de1fda | files:rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt | area:rebuild | date:2026-06-22 -->

### blacklist (cry_cursed) acquisition
cost 0, no clean rarity-int mapping (mapped to Common). buy() rolls `fjN = (2..14).random()` overriding the config default of 14 (Ace). Self-destruct hook (RunScreen.kt) removes the joker when `!deck.hasRank(...)` — which is why glass-shatter deck destruction matters for it to fire.
<!-- session:2026-06-22-aab57457 | commit:da97e5fd98e3fc5b17ccdb6df3c5d0a4b4de1fda | files:rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt,rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt | area:rebuild | date:2026-06-22 -->

### Atlas gap renders placeholders
several cry jokers reference atlases not in the rebuild's loaded set (atlasone/atlastwo/atlasthree/atlasexotic/Jokers) — `atlasspooky` (blacklist), `atlasepic` (boredom, googol_play). These render as name placeholders until those atlases are wired into the art pipeline.
<!-- session:2026-06-22-aab57457 | commit:da97e5fd98e3fc5b17ccdb6df3c5d0a4b4de1fda | files:rebuild/app/src/main/kotlin/systems/balatro/ui/JokerArt.kt | area:rebuild | date:2026-06-22 -->

### Glass shatter must destroy from deck
shatter was visual-only (`startDissolve(card, shatter=true)`); permanent destruction requires removing the instance from `Deck.all` via the new `removeCard(card)`, which then correctly updates deck-size-scaling jokers (Blue Joker +2 chips/remaining card), stone/steel tallies, and gates blacklist's self-destruct.
<!-- session:2026-06-22-aab57457 | commit:da97e5fd98e3fc5b17ccdb6df3c5d0a4b4de1fda | files:rebuild/app/src/main/kotlin/systems/balatro/game/Deck.kt,rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt | area:rebuild | date:2026-06-22 -->

### Android mod discovery resolves via love.filesystem PhysFS union, save-dir-first
On LÖVE Android, `nativefs` is a wrapper that routes every dir/read op straight to `love.filesystem` (`setWorkingDirectory` is a no-op stub). SMODS discovers mods by scanning the virtual path `"Mods"`, which love mounts as a UNION of the writable save dir AND `game.love` — with the save dir taking precedence. A stale `files/save/Mods/` (from the last `build.sh deploy` push, Talisman-era with DebugPlus) therefore shadows the embedded `Amulet/Cryptid/...` in the archive, so SMODS loads the wrong/old set. The hardcoded mod list in `patch_main_lua.py` section 3 is ONLY for the dump's Talisman/Amulet detection, never for `SMODS.loadMods`.
<!-- session:2026-06-22-dadaeb8f | commit:cd308a105262e779d12ff11b95d01f62060d376d | files:scripts/patch_main_lua.py,patches/android-nativefs.lua,build/game/Mods/Steamodded/src/preflight/core.lua | area:scripts | date:2026-06-22 -->

### No runtime extraction existed
Nothing copied `game.love`'s embedded `Mods/` into `files/save/Mods/` at runtime; only `build.sh deploy()`/`prepare_transfer` (`adb push` → `run-as cp -r ... files/save/`) populated it, and `adb install -r` replaces `game.love` but never touches `files/save/Mods/`, so it goes stale.
<!-- session:2026-06-22-dadaeb8f | commit:cd308a105262e779d12ff11b95d01f62060d376d | files:scripts/build.sh,scripts/patch_main_lua.py | area:scripts | date:2026-06-22 -->

### `SMODS.Mods` on device showed `{Steamodded, Talisman, Lovely, Balatro}`
"Talisman" (not "Amulet") in the on-device `cry_diag.txt` was the smoking gun confirming the stale save-dir shadow — the build embeds Amulet, not Talisman.
<!-- session:2026-06-22-dadaeb8f | commit:cd308a105262e779d12ff11b95d01f62060d376d | files:.claude/worktrees/relaxed-elbakyan-1c44d4/nix/stage-mods.sh,.claude/worktrees/relaxed-elbakyan-1c44d4/scripts/patch_main_lua.py,.claude/worktrees/relaxed-elbakyan-1c44d4/scripts/patch_main_lua.py,.claude/worktrees/relaxed-elbakyan-1c44d4/nix/balatro-cryptid.nix,.claude/worktrees/relaxed-elbakyan-1c44d4/nix/sources.json | area:.claude | date:2026-06-22 -->

### Manifest perCard hooks must fire ONLY in the individual SCORING pass (ctx.individual && cardarea=='play'), NOT the repetition-COLLECTION pass (ctx.repetition). The two-pass scoring loop in Score.kt: (1) repetition pass visits each scored card ONCE to tally retrigger counts; (2) individual pass then scores each card 1+reps times. dispatchManifest originally fired perCard in BOTH, double-counting every non-retriggered card and silently over-scoring all batch-12 two-phase jokers (wee_fib +12 not +6, krustytheclown x1.16 not x1.08, antennastoheaven xc1.4 not 1.2, spectrogram n=4 not 2). Fixed in #44 (268->272 oracle). The individual pass already loops per-rep, so retriggered cards still accumulate correctly. Also: GameEvent model now has BeforeHand/HandScored(handType,playedCount)/Discarded/Sold(soldKey,sellValue); reducers should use 'if (e is X)' or 'when(e){...; else->s}' (NOT exhaustive when over GameEvent) so a new event variant doesn't ripple a compile break across all reducers. Manifest migration ~90% done (170+ jokers, batches 1-12 + Sold-event batch).
<!-- session:2026-06-23-6b7b0353 | commit:8f6af1949c0d1cbfe535faad4de6b19337417f15 | files:rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt,tools/uiref/extract.sh,rebuild/app/src/main/kotlin/systems/balatro/ui/HudSpec.kt | area:rebuild | date:2026-06-23 -->

### Played-card edition scoring gap
The engine never applied a scored playing card's own edition. `Score.evalCard` read only `c.enhancement` for the "play" cardarea; `c.edition` was read only on the joker path. Faithful behavior (vendor `common_events.lua:802-807` → `calculate_edition` → `game_object.lua` Holo/Foil/Poly) applies Foil +50 chips, Holo +10 mult, Poly ×1.5 per scored card, guarded to `G.play` only (held cards contribute nothing). Fix mirrors the joker-edition values already hardcoded in Score.kt.
<!-- session:2026-06-22-002ecc85 | commit:e935dc9a61aa0f39da2f52366f64b95175c45920 | files:rebuild/app/src/main/kotlin/systems/balatro/game/Score.kt,rebuild/app/src/main/kotlin/systems/balatro/game/Oracle.kt | area:rebuild | date:2026-06-22 -->

### Oracle edition coverage was joker-only
`Oracle.kt` edition tests exercised only `FJoker(edition=...)`, never a `PlayingCard` carrying an edition — which is why the played-card edition drop was silent and untested. `PlayingCard` does carry `edition: String` (Cards.kt:20), so the data was present and ignored.
<!-- session:2026-06-22-002ecc85 | commit:e935dc9a61aa0f39da2f52366f64b95175c45920 | files:rebuild/app/src/main/kotlin/systems/balatro/game/Oracle.kt | area:rebuild | date:2026-06-22 -->

### Manifest as single source of truth
Joker metadata was scattered across registries; the migration centralizes it in `JokerManifest.kt` so both `Score.kt` and `RunScreen.kt` read from one declarative table (Unix/functional "one source, many consumers" framing the user explicitly asked for).
<!-- session:2026-06-22-002ecc85 | commit:e935dc9a61aa0f39da2f52366f64b95175c45920 | files:rebuild/app/src/main/kotlin/systems/balatro/game/JokerManifest.kt | area:rebuild | date:2026-06-22 -->

### Patched-build architecture
The Android build applies a quilt-style ordered patch series (`overlay/patches/series`) over vanilla source, plus manual patches under `overlay/patches/manual/`, driven by `scripts/build.sh` and `scripts/patch_main_lua.py`. Telemetry lives in `patches/android-telemetry.lua`.
<!-- session:2026-06-23-8e4f7836 | commit:74a8b9d7a044192973d6ebda6c70a17470e08fd8 | files:scripts/build.sh,overlay/patches/series,patches/android-telemetry.lua | area:scripts | date:2026-06-23 -->

### Sticky-fingers exclusion is pre-existing
Per prior memory ([[lovely-regen-build]]), the sticky-fingers exclusion warning surfaces during lovely/dump regen and is a known pre-existing condition — relevant context for why `j_cry_sock_and_sock` may silently no-op rather than error.
<!-- session:2026-06-23-8e4f7836 | commit:74a8b9d7a044192973d6ebda6c70a17470e08fd8 | files:.claude/worktrees/relaxed-elbakyan-1c44d4/scripts/build.sh,.claude/worktrees/relaxed-elbakyan-1c44d4/scripts/build.sh,.claude/worktrees/relaxed-elbakyan-1c44d4/scripts/build.sh,.claude/worktrees/relaxed-elbakyan-1c44d4/scripts/build.sh,.claude/worktrees/relaxed-elbakyan-1c44d4/scripts/build.sh | area:.claude | date:2026-06-23 -->

### Hand-built UI → vanilla source mapping
A workflow mapped each rebuild composable to its extractable vanilla `create_UIBox_*` / `G.UIDEF` source, with the exact stubs needed to extract each template. Key mappings: `ShopCard` → `create_shop_card_ui` (UI_definitions.vanilla.lua:802-880); shop sell strip → `G.UIDEF.use_and_sell_buttons` + `card_focus_button` (239-297, 382-441); `BlindSelectScreen` → `create_UIBox_blind_select` + `create_UIBox_blind_choice` (1417-1459, 1485-1610).
<!-- session:2026-06-23-85bb2ba5 | commit:02802afef8be05af20f6bb6b6144f2c2564dade7 | files:rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt,tools/uiref/UI_definitions.vanilla.lua,tools/uiref/extract.lua | area:tools | date:2026-06-23 -->

### SHOP_SIGN is a floating UIBox
The animated "Improve your run!" shop sign is NOT part of the shop ROOT tree — it's a separate floating UIBox (align=cm, offset y=-15→0 slide-in) anchored to `G.HUD`'s `row_blind` element. Purely decorative, no RunState binding, and currently absent from the rebuild.
<!-- session:2026-06-23-85bb2ba5 | commit:02802afef8be05af20f6bb6b6144f2c2564dade7 | files:tools/uiref/UI_definitions.vanilla.lua | area:tools | date:2026-06-23 -->

### Sell button is per-card, not a global Row
Vanilla renders the sell button as `card_focus_button(type='sell')` attached to each joker card's `focused_ui` UIBox at align=cl, offset.x=-(card_width-0.17-card.T.w/2) — not as the rebuild's current raw Row of BButtons. Faithful replacement is a per-joker CardArea overlay bound to `RunState.sell(owned[i])`.
<!-- session:2026-06-23-85bb2ba5 | commit:02802afef8be05af20f6bb6b6144f2c2564dade7 | files:rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt | area:rebuild | date:2026-06-23 -->

### HUD parity rendering
RunScreen.kt and HudSpec.kt render the HUD from extracted vanilla `create_UIBox_*` trees via the uiref oracle pipeline; parity is verified by layout extraction rather than hand-built composables.
<!-- session:2026-06-23-6b7b0353 | commit:8f6af1949c0d1cbfe535faad4de6b19337417f15 | files:rebuild/app/src/main/kotlin/systems/balatro/ui/RunScreen.kt,rebuild/app/src/main/kotlin/systems/balatro/ui/HudSpec.kt,tools/uiref/extract.lua,tools/uiref/extract.sh | area:rebuild | date:2026-06-23 -->
