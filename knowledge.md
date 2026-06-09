
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
