# Balatro Cryptid Mobile — Architecture Map

A map of the entire system: the build pipeline in this repo and the game code it
ships to the device. Produced from a deep multi-agent audit of `build/game/`
(every subsystem read and adversarially spot-checked against the code) merged
with knowledge accumulated across debugging sessions. File:line references are
into `build/game/` (the lovely dump + our patches), which is what actually runs
on the phone — tracebacks map here.

> **Regenerating:** `build/game/` is wiped and rebuilt by every
> `./scripts/build.sh build`, so line numbers drift only when the dump or our
> patches change. Patched lines carry grep-able markers (see
> [Our patches](#our-patch-index)).

---

## 1. The two-layer reality

This project is two systems glued together:

1. **The repo** (`~/balatro-cryptid-mobile`): a build pipeline that assembles a
   modded APK from four ingredients — the vanilla `Balatro.love` (from Steam on
   othaos), a community Android LÖVE base APK (`lmm.shorty.systems`,
   WilsontheWolf), GitHub mod releases, and our patch set.
2. **The device runtime**: LÖVE 11.x running a *pre-modded dump* of the game on
   Android (Pixel Fold, Tensor G2 / Mali-G710), where lovely-injector does NOT
   exist.

The single most load-bearing architectural fact: **lovely-injector never runs on
Android** (it's a native `.so` injector; the base APK has none). On desktop,
lovely applies mods' `lovely/*.toml` patches at load time. For Android we use a
**pre-generated lovely dump** (`src/dump/`, ~2.5MB, 31 files) — the fully
patched Lua that lovely produced on a Mac — and bake it over the vanilla game at
build time. Consequences:

- Mods' `lovely/` payloads are dead weight on device; the build strips them.
- Any fix must target the dump copies (`build/game/functions/...`), never the
  mod's `lovely/` folder. (We learned this the hard way: the first
  sticky-fingers guard patched a file that never executes.)
- Updating a mod requires **regenerating the dump on a desktop with lovely**,
  not just bumping the GitHub tag.
- Crash tracebacks cite dump line numbers.

## 2. Build pipeline (`scripts/build.sh`, 943 lines)

`./scripts/build.sh {check|fetch|build|deploy|logs|clean|all}`, driven by
`config.yaml`, runnable via `just`. Reproducible toolchain via `shell.nix`
(androidenv build-tools 34.0.0 → `zipalign`/`apksigner`, apktool, openjdk17,
adb): `nix-shell --run './scripts/build.sh build'`.

**fetch** — pulls `Balatro.love` (scp from othaos), `base.apk` (lmm.shorty.systems),
mod releases/sources from GitHub into `sources/`; `generate_dumps` scp's the
lovely dump from othaos (`~/Library/.../Balatro/Mods/lovely/dump`).

**build** (`build_apk`) — the heart. Order matters:
1. `rm -rf build/game` → re-extract pristine `Balatro.love` → overlay
   `src/dump/` (\*.lua, engine/, functions/, SMODS/, nativefs/). Every build
   starts clean; **all patches re-apply from scratch** (idempotent
   marker-guarded seds — grep for the marker, skip if present).
2. Embed mods into `build/game/Mods/` (Steamodded, Cryptid, Talisman,
   sticky-fingers — each with `lovely/` stripped) + `patches/reserve-shim`.
3. Apply the patch appliers (see [index](#our-patch-index)).
4. apktool-decompile `base.apk`, swap in the game payload, rebuild, `zipalign`,
   `apksigner` (keystore auto-generated via `ensure_keystore`).

**deploy** — `adb install` + push `build/phone-transfer/` (the Mods tree) to the
app's storage (`run-as`, app is `debuggable`) + launch
`systems.shorty.lmm/org.love2d.android.GameActivity`.

### Patch-applier design rules

- One function per concern: `apply_<thing>() { guard-grep; sed(s); verify-grep + log }`.
- Always patch **dump-derived files** in `build/game/`.
- Verify-grep after sed: a sed that silently stopped matching logs `[WARN]`,
  not success — watch build output when the dump changes.
- Test seds against `src/dump/` copies and parse with `luajit -bl` before
  building (LÖVE is LuaJIT — Lua 5.1 syntax).

## 3. Our patch index

Markers are grep-able in `build/game/`. Current appliers in `build_apk` order:

| Applier | Target | What / why |
|---|---|---|
| `apply_crt_fix` | `resources/shaders/CRT.fs` | `time*1000` fp16 overflow on Mali → hash-based noise |
| `apply_android_settings_fix` | `globals.lua` | Android UI settings baseline |
| `apply_mobile_graphics_defaults` | `globals.lua` | texture_scaling=1, shadows off, CRT/bloom 0 (Tensor G2 thermal throttling) |
| `apply_android_video_settings_fix` | `functions/UI_definitions.lua` | hide desktop-only Video tab (Graphics tab stays) |
| `apply_fps_toggle` | `game.lua`, `UI_definitions.lua` | `G.SETTINGS.show_fps` + Settings→Game toggle |
| `apply_debug_overlay` | `game.lua`, `misc_functions.lua`, `UI_definitions.lua` | retarget `F_ENABLE_PERF_OVERLAY`/verbose gates to `G.SETTINGS.perf_mode` + toggle |
| `patch_main_lua.py` | `main.lua` | python patcher (complex main-loop edits) |
| `apply_talisman_dim_fix` | `main.lua` | `G.SCORING_START` — only open Talisman's scoring dim overlay after 0.3s, killing the 1-frame flash on fast hands |
| `apply_tap_description_persist` | `engine/controller.lua` | 5 markers: `TAP_DESC_PERSIST` (popup survives finger lift), `TAP_DESC_HOLDGATE` (hand cards: description only after ≥0.2s hold), `TAP_DESC_TOGGLE` (tap behaviour by area), `TAP_DESC_RELAX` (drop `hover.is` when finger leaves → kills tilt-warp), `TAP_DESC_HOLD_NODRAG` (stationary hold ≠ reorder drag) |
| `apply_drag_select` | `controller.lua`, `globals.lua`, `UI_definitions.lua` | `DRAG_SELECT_*`: slide from empty space across hand to multi-select; `G.SETTINGS.enable_drag_select` toggle |
| `apply_sticky_fingers_guard` | `functions/misc_functions.lua` | `STICKY_GUARD`: nil-guard all 7 `sticky_can_*` wrappers (Pokermon-less builds crashed on Code cards) |
| `apply_glitch_shader_fix` | `Mods/Cryptid/assets/shaders/glitched.fs` | per-variable `highp` + tan-asymptote NaN guard (Mali fp16) |
| `apply_glitched_b_fix` | `Mods/Cryptid/assets/shaders/glitched_b.fs` | per-variable `highp` on math chain + output NaN guard |
| `apply_android_smods_path_fix` | SMODS loader | Android filesystem paths for mod discovery |
| (embed) | `patches/reserve-shim/` | standalone Steamodded mini-mod providing `G.FUNCS.can_reserve_card`/`reserve_card` (extracted from Pokermon, ~25 lines) for Sticky Fingers' Pull target |
| (embed) | `android-telemetry.lua`, `patches/android-nativefs.lua` | telemetry module; nativefs→love.filesystem wrapper |

<!-- WORKFLOW SECTIONS BEGIN — filled from the verified multi-agent audit -->

## 4. Boot & main loop

**Purpose:** Initialises the LÖVE2D game, loads all Lua modules (Steamodded,
Talisman, Cryptid, nativefs wrapper), starts background threads
(save\_manager, sound\_manager, http\_manager), wires the custom `love.run`
frame loop with dt smoothing and FPS cap, and drives `Game:update`/`draw`
every frame. On Android it replaces FFI-based nativefs with a
`love.filesystem` shim and loads the telemetry hook after all other hooks.
The Talisman mod layer replaces `love.update` to drive a scoring coroutine
across frames, using `G.SCORING_START` as a gate before showing the
"calculating…" overlay.

### Key functions

| Function | Where | Role |
|---|---|---|
| `love.conf` | `conf.lua:4` | Sets LÖVE window to 0×0 (fullscreen-resolved at runtime) and disables console in release mode (`_RELEASE_MODE = true`). |
| `loadStackTracePlus` | `main.lua:25` | Defines and returns the StackTracePlus table (STP) that produces rich Lua stack traces including SMODS/lovely source tagging. Called once by `injectStackTrace()`. |
| `injectStackTrace` | `main.lua:589` | Installs `love.errorhandler`: shows crash screen with STP traceback, kills SOUND/SAVE/HTTP\_MANAGER threads, supports touch-tap/ESC/R/Ctrl+C to quit or restart. Idempotent via `stackTraceAlreadyInjected` guard. |
| `love.run` | `main.lua:906` | Custom LÖVE run loop. Calls `love.load` once, then per frame: pumps events (touchpressed consumed immediately as a flag; mousepressed deferred and re-fired after the poll loop with `touched=true`), steps timer, applies dt smoothing (0.8/0.2 EMA capped at 0.1 s), calls `love.update(dt_smooth)` and `love.draw`, then sleeps to enforce `G.FPS_CAP` (default 500). |
| `love.load` | `main.lua:980` | Entry point after SMODS/Talisman/Cryptid module loading. Calls `G:start_up()`, attempts luasteam init on desktop (skipped on Android), hides mouse cursor. |
| `Game:start_up` | `game.lua:13` | Wrapped by Talisman (`main.lua:2368`) to install `safe_str_unpack`. Original: loads `settings.jkr`, inits window, starts SOUND\_MANAGER thread (channel `'sound_request'`), starts SAVE\_MANAGER thread (channel `'save_request'`), starts HTTP\_MANAGER thread (channels `'http_request'`/`'http_response'`), loads shaders. |
| `love.update` (vanilla wrapper) | `main.lua:1025` | Thin wrapper: calls `timer_checkpoint(nil,'update',true)` then `G:update(dt)`. This is the target overridden by Talisman's `love.update` below. |
| `love.update` (Talisman override) | `main.lua:2111` | Calls original `love.update` (which calls `G:update`), then drives the scoring coroutine each frame: if `G.SCORING_COROUTINE` exists, resumes it; if dead or aborted, clears it and calls `exit_overlay_menu`; if running > 0.3 s after `G.SCORING_START`, opens a "calculating…" overlay with abort button. |
| `Game:update` | `game.lua:2616` | Core per-frame update. Runs `nuGC`, advances `TIMERS.REAL`/`UPTIME`/`BACKGROUND`, computes `SPEEDFACTOR` from `GAMESPEED`, `ACC`, pause and screenwipe state, advances `TIMERS.TOTAL` by `dt*SPEEDFACTOR`, runs `E_MANAGER`, dispatches to state-specific update sub-functions, moves/animates all nodes, updates `CONTROLLER`, polls `FILE_HANDLER` for deferred saves. |
| `love.draw` | `main.lua:1031` | Calls `timer_checkpoint` then `G:draw()`. `G:draw()` renders the canvas; at end draws FPS overlay if `G.SETTINGS.show_fps` and perf flamegraph if `G.SETTINGS.perf_mode`. |
| `G.FUNCS.evaluate_play` (Talisman coroutine entry) | `main.lua:2094` | Creates `G.SCORING_COROUTINE` from the original `evaluate_play`, records `G.SCORING_START = love.timer.getTime()`, resets `G.CARD_CALC_COUNTS`, then performs first resume. Subsequent resumes happen in `love.update` each frame. |
| `Card:calculate_joker` (Talisman yield point) | `talisman.lua:785` | Wraps vanilla `calculate_joker`: if more than `TIME_BETWEEN_SCORING_FRAMES` (0.03 s) has elapsed since `G.LAST_SCORING_YIELD` and inside a coroutine, yields back to the frame loop. This is the only `coroutine.yield()` site. |
| `timer_checkpoint` | `functions/misc_functions.lua:65` | No-op unless `G.SETTINGS.perf_mode` is true. Records wall-clock delta between checkpoints for the draw/update profiler flamegraph. |
| `boot_timer` | `functions/misc_functions.lua:107` | Renders a loading progress bar directly to the window during `G:start_up` sequence. Used at shaders/savemanager/window init milestones. |
| `nuGC` | `functions/misc_functions.lua:718` | Budget incremental GC: runs `collectgarbage('step',1)` up to `max_steps` or `time_budget` (default 0.3 ms), with a safety full-collect if heap > 300 MB. Called at the top of `Game:update` every frame; `disable_otherwise=true` stops automatic GC between calls. |
| `nativefs` (Android shim) | `nativefs.lua:14` | On Android, returns a pure-Lua table wrapping `love.filesystem` for all file I/O (read, write, append, load, getDirectoryItems, newFile object). On other platforms, delegates to `require('nativefs.nativefs')` which uses FFI. |
| `love.errorhandler` (telemetry chain) | `android-telemetry.lua:127` | Outermost error handler on Android: logs CRASH event to logcat with state/ante/round context, then chains to the SMODS errorhandler (`injectStackTrace`'s handler) which shows the crash screen UI. |

### Key structures

**`G.TIMERS`** — `globals.lua:136`

Table initialised with all five fields:
`TOTAL` (game-speed-scaled), `REAL` (wall seconds since run/sandbox start),
`REAL_SHADER` (set to the constant 300 every frame when
`G.SETTINGS.reduced_motion` is on; `REAL` itself still advances normally),
`UPTIME` (never paused/reset), `BACKGROUND` (background rotation angle,
advances only while a spin animation is active).
`REAL` and `TOTAL` are reset to 0 on sandbox entry (`game.lua:1317-1318`,
`Game:sandbox`) and also inside a queued Event on run start
(`game.lua:1438-1439`, inside `Game:start_run`). `UPTIME` is never reset.

**`G.SPEEDFACTOR`** — init `game.lua:222`, update `game.lua:2663`

Multiplier applied to `dt` for animation/event advancement. Initialised to 1
in `Game:init`. Set to `SETTINGS.GAMESPEED` when `G.STAGE == RUN` AND not
paused AND not `G.screenwipe` (else 1), plus `ACC` overflow bonus during
`HAND_PLAYED`/`NEW_ROUND` states. Nodes are passed `dt*SPEEDFACTOR` in their
update/animate calls.

**`G.SAVE_MANAGER`** — `game.lua:111`

Table with `thread` (`engine/save_manager.lua`) and `channel`
(`'save_request'`). Thread started at `game.lua:115` via `:start(2)`. Note:
the thread body never reads this start argument — it is silently ignored.
Main loop pushes `save_progress`/`save_settings`/`save_run`/`save_metrics`
messages via `FILE_HANDLER` polling in `Game:update` every `G.F_SAVE_TIMER`
seconds (30 s default, 5 s in dev mode).

**`G.FILE_HANDLER`** — first set at `game.lua:1257`

Per-save-cycle flags: `progress`, `settings`, `run`, `metrics`, `force`,
`update_queued`, `last_sent_time`. Checked in `Game:update` to decide when to
push to SAVE\_MANAGER channel.

**`G.SCORING_COROUTINE` / `G.SCORING_START`** — `main.lua:2095-2096`

`G.SCORING_COROUTINE` holds the active scoring coroutine (nil when idle).
`G.SCORING_START` is set to `love.timer.getTime()` when scoring begins; the
Talisman update hook checks `(getTime()-G.SCORING_START) > 0.3` to gate
showing the "calculating…" overlay (only when `G.OVERLAY_MENU` is also nil).

**`G.SETTINGS.show_fps` / `G.SETTINGS.perf_mode`** — `game.lua:3177`/`3179`

`show_fps` draws a simple green FPS counter post-canvas. `perf_mode` enables
`timer_checkpoint` profiling and draws a flamegraph overlay. Both are our
patches (not in vanilla); defaults not set in `SETTINGS` table (treated as
falsy unless toggled in the options UI).

**`G.SETTINGS.enable_drag_select`** — `globals.lua:152`

Default `true` (marker `DRAG_SELECT_DEFAULT`). Read in `controller.lua:336`
to activate drag-select mode when touch dragging over empty space in the hand
area.

**`dt_smooth`** (love.run local) — `main.lua:913`

Per-frame smoothed dt: `0.8*prev + 0.2*raw`, capped at 0.1 s. This value
(not raw dt) is passed to `love.update`. Prevents single-frame spikes (e.g.,
app resume on Android) from causing simulation jumps. Initial value 1/100.

### Control flow

1. LÖVE reads `conf.lua` before anything: `love.conf` sets window to 0×0,
   `console=false`.

2. `main.lua` executes top-to-bottom at load time:
   - Lines 1–492: `loadStackTracePlus()` function defined.
   - Line 869: `injectStackTrace()` called — `love.errorhandler` installed
     immediately.
   - Line 874: JIT disabled on Apple Silicon.
   - Lines 875–902: All game engine modules required in order: object, bit,
     string\_packer, controller, back, tag, event, node, moveable, sprite,
     animatedsprite, misc\_functions, game, globals, ui, UI\_definitions,
     state\_events, common\_events, button\_callbacks, misc\_functions (again),
     test\_functions, card, cardarea, blind, card\_character, particles, text,
     challenges.
   - Line 904: `math.randomseed(G.SEED)` — G was created at `globals.lua:535`
     during `require "globals"`; `G.SEED = os.time()`.
   - Lines 906–957: `love.run()` defined (custom frame loop, replaces LÖVE
     default).
   - Lines 959–978: Cryptid global tables initialised.
   - Lines 980–1017: `love.load()` defined (not called yet).
   - Lines 1019–1095: `love.quit`, `love.update`, `love.draw`,
     `love.keypressed/released`, `love.gamepadpressed/released`,
     `love.mousepressed/released/moved`, `love.joystickaxis` defined.
   - Lines 1289–1301: Android `package.preload` fixes for SMODS.version /
     SMODS.release, `love.filesystem` require path extended.
   - Lines 1303–1386: SMODS bootstrap — SMODS table, require version/release,
     load SMODS src files (ui/index/utils/overrides/game\_object/logging/compat/loader)
     via NFS.read+load. On Android `SMODS.path` is hardcoded to
     `'Mods/Steamodded/'`; on desktop it is discovered via `find_self()`.
   - Lines 1390–1415: nativefs/lovely re-required; Talisman directory located.
   - Lines 1417–1484: Talisman config loaded, `init_localization` wrapped,
     Talisman table built.
   - Lines 1579–1934: If `Talisman.config_file.break_infinity` set,
     Big/Notations loaded; `math.floor/ceil/sqrt/max/min/abs/log/log10/exp`
     overridden for bignum; `number_format` wrapped.
   - Lines 1987–2099: Talisman animation suppression wrappers;
     `G.FUNCS.evaluate_play` split into phases with `Talisman.scoring_state`
     tracking.
   - Lines 2076–2088: `Game:update` wrapped to flush `G.latest_uht` and handle
     `dollar_update`.
   - Lines 2090–2247: Talisman coroutine scoring system installed:
     `G.FUNCS.evaluate_play` re-wrapped to create coroutine + set
     `G.SCORING_START`, `love.update` re-wrapped to resume coroutine each frame.
   - Lines 2349–2372: `G:start_up` wrapped to install `safe_str_unpack`.
   - Lines 2630–2637: Android telemetry loaded last via
     `pcall(love.filesystem.load('android-telemetry.lua'))` — only runs on
     Android (outer `if` guards the load; inner `line 5` also exits early if
     not Android).

3. LÖVE calls `love.run()` which calls `love.load()` once:
   - `love.load` calls `G:start_up()` (Talisman wrapper → original):
     - Loads `settings.jkr`.
     - `boot_timer` checkpoints render loading bar.
     - Starts SOUND\_MANAGER thread.
     - Starts SAVE\_MANAGER thread (`engine/save_manager.lua`, arg=2, ignored
       by thread).
     - Starts HTTP\_MANAGER thread.
     - Loads shaders.
     - Continues with full game initialisation (window, localisation, UI, etc.).
   - luasteam init attempted (skipped on Android).
   - `love.mouse.setVisible(false)`.

4. `love.run`'s returned closure runs every frame:
   a. Record `run_time = love.timer.getTime()`.
   b. `love.event.pump()`; poll events. `touchpressed` sets `touched=true` flag
      immediately; `mousepressed` is stored for deferred dispatch; all other
      events dispatched immediately via `love.handlers[]`. After the poll loop,
      if a `mousepressed` was seen, it fires with `touched` as its fourth arg.
   c. `dt = love.timer.step()`.
   d. `dt_smooth = min(0.8*dt_smooth + 0.2*dt, 0.1)`.
   e. `love.update(dt_smooth)` → Talisman wrapper → vanilla wrapper →
      `timer_checkpoint` + `G:update(dt)`.
      Inside `G:update`: `nuGC` → advance timers → compute `SPEEDFACTOR` →
      `E_MANAGER:update` → state dispatch → node animate/move/update →
      `CONTROLLER:update` → `FILE_HANDLER` save poll.
      After `G:update` returns to Talisman wrapper: if `G.SCORING_COROUTINE`
      active, resume it (yields inside `Card:calculate_joker` every
      `TIME_BETWEEN_SCORING_FRAMES` = 0.03 s).
   f. `love.draw()` → `timer_checkpoint` + `G:draw()` → optional `show_fps` /
      `perf_mode` overlays.
   g. `love.graphics.present()`.
   h. `run_time = min(elapsed, 0.1)`; if `run_time < 1/G.FPS_CAP` then sleep
      remainder.

### Interactions

- **`game.lua`** (`Game` class): `G:start_up` initialises all subsystems;
  `G:update` is the core per-frame entry called by `love.update`; `G:draw`
  called by `love.draw`.
- **`globals.lua`**: Defines `G = Game()` at file scope during require; sets
  `G.SEED`, `G.TIMERS`, `G.SETTINGS` including `enable_drag_select` default.
- **`engine/save_manager.lua`**: Background thread receiving messages on
  `'save_request'` channel pushed by `Game:update` FILE\_HANDLER polling.
- **`engine/sound_manager.lua`**: Background thread receiving messages on
  `'sound_request'` channel.
- **`engine/http_manager.lua`**: Background thread; responses read from
  `'http_response'` channel in `Game:update`.
- **`engine/controller.lua`**: `G.CONTROLLER:update` called at end of
  `Game:update`; contains `DRAG_SELECT_INIT/ACTIVATE/RESET/LOOP` and
  `TAP_DESC_*` patch markers.
- **`functions/misc_functions.lua`**: Provides `timer_checkpoint` (perf-mode
  profiler), `boot_timer` (load screen), `nuGC` (incremental GC called each
  frame); `STICKY_GUARD` markers guard the 7 `sticky_can_*` predicates.
- **`android-telemetry.lua`**: Loaded last in `main.lua` via pcall on Android;
  wraps `Game:update`, `Game:start_run`, `G.FUNCS.buy/sell/use/play/discard`,
  `love.errorhandler`, `save_run` for logcat telemetry.
- **`nativefs.lua`**: On Android replaces FFI nativefs with `love.filesystem`
  wrapper; exposed as `NFS` global; used by SMODS loader and Talisman config
  read/write.
- **`Mods/Talisman/*.lua`**: Loaded inline in `main.lua` (not via `require`);
  Big/Notations loaded from `talisman_path/big-num`; scoring coroutine
  architecture injected into `love.update`.

### Gotchas

- **`love.run` is fully replaced** (`main.lua:906`). The LÖVE default run loop
  is not used. The custom loop fires `mousepressed` with `touch=true` after
  detecting `touchpressed` in the same pump cycle. Any patch to event handling
  must account for this — the deferred `mousepressed` is the only path; there
  is no separate touch codepath in game logic.

- **`dt_smooth` caps at 0.1 s** and uses a 0.8/0.2 EMA (initial value 1/100).
  On Android app resume a large raw dt spike is absorbed, but `dt_smooth` can
  still be up to 0.1 s. `G.TIMERS.REAL` will accumulate these;
  `TIMERS.TOTAL` accumulates `dt*SPEEDFACTOR` so animation speed is affected.

- **`G.SCORING_START` gate**: the overlay appears only when
  `getTime() - G.SCORING_START > 0.3` AND `G.OVERLAY_MENU` is nil. If the
  menu is already open for any other reason the overlay is suppressed silently
  and the coroutine still runs.

- **Talisman's `love.update` is the outermost update wrapper** — it wraps the
  vanilla `love.update` (`main.lua:1025`) which itself calls `G:update`. The
  `android-telemetry` `Game:update` wrapper is inner (wraps `G:update`
  directly), so the call chain is: Talisman `love.update` → vanilla
  `love.update` → telemetry `Game:update` → original `Game:update`.

- **`nuGC` calls `collectgarbage('stop')`** via `disable_otherwise=true` at
  the top of every `Game:update` frame. The Lua GC is therefore completely
  manual between frames. If `nuGC` is ever skipped (e.g. by bypassing
  `Game:update`), garbage accumulates until the 300 MB safety net fires a full
  collect.

- **`math.floor/ceil/max/min/abs/log/log10/exp/sqrt` are all replaced** by
  Talisman wrappers when `break_infinity` is active. Any code that expects
  these to return plain numbers may receive a bignum table. The wrappers exist
  in `main.lua` at module load time, before `love.load` is called.

- **SAVE\_MANAGER thread start argument is ignored.** `game.lua:115` calls
  `G.SAVE_MANAGER.thread:start(2)` but `save_manager.lua` never reads this
  value — its body is a straight `while true do CHANNEL:demand()` loop. If the
  thread errors, saves silently fail; no error surfaces to the main thread.

- **On Android, `SMODS.path` is hardcoded** to `'Mods/Steamodded/'`
  (`main.lua:1370`) instead of being discovered via `find_self()`. Any
  restructuring of the Mods directory breaks this assumption.

- **`android-telemetry.lua` is only loaded on Android** (outer `if
  love.system.getOS() == 'Android'` at `main.lua:2631` guards the whole load).
  The inner `line 5` early-return is an additional safety; it is loaded via
  `love.filesystem.load` (not `require`), so it does not participate in
  `package.loaded` caching and would re-execute if loaded a second time.

- **`G.FPS_CAP` defaults to 500** (`main.lua:954`) and is checked in
  `love.run`, not in game code. Changing it mid-frame in `G:update` takes
  effect on the next iteration's sleep call.

- **`G.SPEEDFACTOR` falls back to 1** not only when outside the RUN stage, but
  also when the game is paused or a screenwipe is active (`not G.SETTINGS.paused
  and not G.screenwipe` are both required for GAMESPEED to apply).

### Mobile notes

- `nativefs.lua:14` detects Android and returns a full `love.filesystem`-backed
  shim. FFI is not available on Android, so the upstream `nativefs.nativefs`
  (which uses `ffi.load`) is never loaded. The shim covers
  read/write/append/load/newFile/getDirectoryItems/getInfo/createDirectory/remove/mount(noop)/unmount(noop).

- `love.run`'s event pump (`main.lua:929-939`) explicitly intercepts
  `touchpressed` and sets `touched=true`, then injects `mousepressed` with
  `touch=true`. This is the mechanism by which all touch input flows through
  the standard mouse-event path. No separate touch codepath exists in game logic.

- `love.mousemoved` (`main.lua:1082`) checks `love.touch.getTouches()` to
  update `CONTROLLER.last_touch_time`, then classifies HID mode as `'touch'`
  if last touch was within 0.2 s. This time window avoids HID flip-flop when
  stylus/finger input interleaves.

- `android-telemetry.lua` logs all key game events to logcat via `print()` which
  LÖVE routes to the SDL/APP tag. Filter:
  `adb logcat -s SDL/APP | grep TEL`. The CRASH handler (`line 127`) chains to
  the SMODS errorhandler so the crash screen UI still appears.

- `G.SETTINGS.enable_drag_select` defaults `true` (`globals.lua:152`,
  `DRAG_SELECT_DEFAULT` marker). The controller enables drag-select only on
  touch HID when no drag target and no collisions exist (`controller.lua:336`).
  Mobile-only feature suppressed on mouse/gamepad.

- Talisman config write (`talisman_path/config.lua` via `nativefs.write`) is
  guarded by `love.system.getOS() ~= 'Android'` at `main.lua:1515, 1569,
  1575`. On Android the config is read-only from the APK; changes made in the
  settings UI are lost on restart.

- `G.SETTINGS.show_fps` and `G.SETTINGS.perf_mode` draw directly to the
  back-buffer after canvas blit (`game.lua:3177, 3179`), outside the canvas
  transform, so they render at device pixel resolution regardless of
  `G.CANV_SCALE`. Useful for Mali GPU performance diagnosis.

- The coroutine scoring system (`G.SCORING_COROUTINE`) yields every 0.03 s
  inside `Card:calculate_joker`. On Mali/low-end devices this distributes
  scoring work across frames. `TIME_BETWEEN_SCORING_FRAMES=0.03` is a
  module-level constant (`main.lua:2195`); reducing it risks visible jank
  during scoring on slow devices.

### Patch touchpoints

| Marker | Location | Purpose |
|---|---|---|
| `STICKY_GUARD` | `functions/misc_functions.lua:2716/2723/2730/2737/2744/2751/2758` | Guard predicates (`can_use_blind_card`, `can_reserve_card`, `can_select_card`, `can_buy`, `can_buy_and_use`, `can_select_crazy_card`, `can_take_card`) that return false early to prevent double-activation on touch |
| `TAP_DESC_PERSIST` | `controller.lua:414` | Keeps `hovering.target` shown when `cursor_hover.target` is nil (touch lift without moving away) |
| `TAP_DESC_HOLDGATE` | `controller.lua:409` | Suppresses description show on touch unless `cursor_down.duration >= 0.2 s` or not in hand area |
| `TAP_DESC_TOGGLE` | `controller.lua:375` | Toggles `shown_desc` on tap rather than setting it unconditionally |
| `TAP_DESC_RELAX` | `controller.lua:448` | Clears `hover.is` when touch is down on a different target than the hovered card, killing the 3D mesh tilt-warp |
| `TAP_DESC_HOLD_NODRAG` | `controller.lua:378` | Prevents description from sticking when dragging away from a card |
| `DRAG_SELECT_INIT` | `controller.lua:22` | `dragSelectActive` table initialised on CONTROLLER construction |
| `DRAG_SELECT_ACTIVATE` | `controller.lua:336` | Set `active=true` when touch drag starts over empty space with `enable_drag_select` on |
| `DRAG_SELECT_RESET` | `controller.lua:341` | Clear on cursor release |
| `DRAG_SELECT_LOOP` | `controller.lua:395` | Per-frame drag select logic runs while active |
| `DRAG_SELECT_DEFAULT` | `globals.lua:152` | `G.SETTINGS.enable_drag_select = true` as default |
| `G.SETTINGS.show_fps` | `game.lua:3177` | FPS counter drawn post-canvas; settings toggle at `UI_definitions.lua:2479` |
| `G.SETTINGS.perf_mode` | `game.lua:3179` | Flamegraph overlay; settings toggle at `UI_definitions.lua:2480`; `timer_checkpoint` early-returns when off |
| `G.SCORING_START` (Talisman dim gate) | `main.lua:2096` (set), `main.lua:2134` (checked) | Timestamps coroutine creation; overlay deferred 0.3 s to avoid flash on fast scores |

### Unknowns

- Whether `G.FPS_CAP` is ever set to a value other than 500 (no other write
  found in the scoped files; may be set by a mod or settings UI not visible
  here).
- The exact `lovely.apply_patches` call path — `lovely.lua` only declares
  repo/version/mod\_dir; the actual patch engine is the C injector baked at
  dump time and not present in these files.
- How `SMODS loader.lua` enumerates and loads Cryptid/Talisman/sticky-fingers
  mod Lua files — that is in `SMODS src/loader.lua` which was not read in full.

## 5. The G object & state machine

### G is the Game singleton

`Game = Object:extend()` (`game.lua:4`). The constructor (`Game:init`, `game.lua:7`) does
exactly two things:

```lua
G = self          -- game.lua:8
self:set_globals()
```

`G` is a module-level global assigned once at construction. There is no separate registry or
dependency-injection layer — every subsystem reaches into `G` directly. The construction is
triggered at the bottom of `globals.lua:535`:

```lua
G = Game()
```

`Game:init` calls `set_globals()` (defined in `globals.lua`), which populates every field on
`G` (= `self`). By the time `globals.lua:535` returns, G is fully initialized. `Game:start_up`
(`game.lua:13`) is called later from `love.load` and does the second-phase work (loads
settings.jkr, creates managers, loads shaders).

### Feature flags

All F_ flags are set first in `set_globals` (`globals.lua:15-44`) with desktop defaults, then
overridden by platform blocks:

| Platform | Notable overrides |
|---|---|
| Windows | F_DISCORD=true, F_SAVE_TIMER=5 |
| **Android** (`globals.lua:54`) | F_MOBILE_UI=true, F_DISCORD=false, F_CRASH_REPORTS=false, F_RUMBLE=false |
| OS X | F_DISCORD=true, F_SAVE_TIMER=5 |
| Nintendo Switch | F_HIDE_BG=true, F_QUIT_BUTTON=false, F_VIDEO_SETTINGS=false, F_RUMBLE=0.7 |

On this build F_MOBILE_UI is always true (Android path). F_SAVE_TIMER defaults 30 on Android
(vs. 5 on desktop) — this is the save-coalescing interval in seconds polled inside `Game:update`.

### STATES enum (`globals.lua:295-317`)

19 vanilla numeric states plus two SMODS extension slots:

```
SELECTING_HAND=1   HAND_PLAYED=2    DRAW_TO_HAND=3   GAME_OVER=4
SHOP=5             PLAY_TAROT=6     BLIND_SELECT=7   ROUND_EVAL=8
TAROT_PACK=9       PLANET_PACK=10   MENU=11          TUTORIAL=12
SPLASH=13          SANDBOX=14       SPECTRAL_PACK=15 DEMO_CTA=16
STANDARD_PACK=17   BUFFOON_PACK=18  NEW_ROUND=19
SMODS_REDEEM_VOUCHER=998   SMODS_BOOSTER_OPENED=999
```

SPLASH=13 carries a code comment: `--DO NOT CHANGE, this has a dependency in the SOUND_MANAGER`
(`globals.lua:310`). Changing it would silently break sound initialization.

Initial state: `G.STATE = G.STATES.SPLASH` (`globals.lua:328`).

### STAGES enum (`globals.lua:319-323`)

```
MAIN_MENU=1    RUN=2    SANDBOX=3
```

Initial stage: `G.STAGE = G.STAGES.MAIN_MENU` (`globals.lua:327`).

`G.STAGE_OBJECTS` is a table of three arrays (one per stage index) tracking which game objects
belong to the current stage (`globals.lua:324-326`).

### State transition semantics

`G.STATE` and `G.STATE_COMPLETE` are plain globals mutated in-place. There is no transition
guard or validated FSM — any code anywhere can write `G.STATE = G.STATES.SHOP`. The dispatch
loop in `Game:update` uses separate `if` statements (not `elseif`), so if a state handler writes
`G.STATE` mid-frame, the new state's handler can also fire in the same frame (see §6).

`G.STATE_COMPLETE` (`globals.lua:330`) is a boolean gate used by some state handlers to sequence
multi-step work within a single state. It does not automatically advance STATE.

`G.TAROT_INTERRUPT` (`globals.lua:329`) is a nil/value flag used to break out of certain
pack-opening flows.

### SETTINGS (`globals.lua:151-206`)

Selected fields relevant to frame behavior:

| Field | Default | Effect |
|---|---|---|
| `GAMESPEED` | 1 | Multiplier for SPEEDFACTOR in RUN stage |
| `paused` | false | When true: dt→0 inside fbf gate, SPEEDFACTOR→1 |
| `enable_drag_select` | true | Slide-to-select touch feature gate |
| `GRAPHICS.crt` | 0 | CRT shader intensity (0=off) |
| `GRAPHICS.bloom` | 0 | Bloom intensity |
| `GRAPHICS.shadows` | 'Off' | Shadow rendering |
| `GRAPHICS.texture_scaling` | 1 | Atlas scale (1 or 2) |

### Timers (`globals.lua:136-142`)

Five timers, all initialized to 0:

| Timer | Advances when | Purpose |
|---|---|---|
| `TIMERS.REAL` | Always, by raw dt | Wall-clock; drives REAL_SHADER; shader time base |
| `TIMERS.REAL_SHADER` | Always | = REAL normally; set to constant 300 every frame when reduced_motion is on |
| `TIMERS.UPTIME` | Always, by raw dt | Session uptime (same as REAL in practice) |
| `TIMERS.TOTAL` | Inside fbf gate, by `dt * SPEEDFACTOR` | Game-speed-scaled time; drives Events and ease animations |
| `TIMERS.BACKGROUND` | Always, scaled by spin amount | Background animation rotation speed |

Events default to `TIMERS.TOTAL`; if `created_on_pause` is true (or `pause_force` is set),
they use `TIMERS.REAL` so they advance even when the game is paused (`event.lua:24-25`).

### SPEEDFACTOR (`game.lua:2663-2664`)

```lua
self.SPEEDFACTOR = (G.STAGE == G.STAGES.RUN
    and not G.SETTINGS.paused
    and not G.screenwipe) and self.SETTINGS.GAMESPEED or 1
self.SPEEDFACTOR = self.SPEEDFACTOR + math.max(0, math.abs(G.ACC) - 2)
```

SPEEDFACTOR equals GAMESPEED only when **all three** conditions hold: stage is RUN, game not
paused, no screenwipe active. Outside RUN (menus, sandbox) it is always 1. The second line adds
the ACC bonus — ACC accretes during HAND_PLAYED and NEW_ROUND states, creating a speed-up
effect as animations resolve.

### EventManager queues (`event.lua:109-115`)

Five named queues processed independently each tick:

```
unlock    base    tutorial    achievement    other
```

Each queue is a FIFO array. `blocking=true` on an event stops later events in the **same queue**
from being processed until it completes; `blockable=false` lets an event skip past a blocker in
its queue. Cross-queue blocking does not exist — all five queues advance in parallel each tick.

Event trigger types: `immediate` (fires once, done), `after` (fires after `delay` seconds on its
timer), `ease` (interpolates a field over `delay` seconds), `condition` (polls until a condition
is true), `before` (fires func each tick until delay elapsed, then removes).

EventManager processes at most once per `queue_dt = 1/60` seconds (`event.lua:117, 175`) —
i.e., at most 60 event-ticks per second regardless of frame rate. If the game runs at 120 FPS,
every other frame skips event processing entirely.

### Instance registries (`globals.lua:337-351`)

`G.I` holds named arrays of live objects by type:

```lua
G.I = { NODE={}, MOVEABLE={}, SPRITE={}, UIBOX={}, POPUP={}, CARD={}, CARDAREA={}, ALERT={} }
```

`G.MOVEABLES` and `G.ANIMATIONS` are flat arrays iterated every frame. `G.DRAW_HASH` is a
spatial hash used to cull draw calls.

### Color palette (`globals.lua:366-482`)

`G.C` is a table of named RGBA arrays (from `HEX()` or literals). The two edition colors
(`G.C.EDITION` and `G.C.DARK_EDITION`) are animated in-place each frame in `Game:update`.
`G.C.SUITS` colors are replaced at startup from `G.C.SO_1` or `SO_2` depending on the
colorblind setting.

### UIT enum (`globals.lua:489-499`)

Layout node types: T=1 (text), B=2 (box), C=3 (column), R=4 (row), O=5 (object/Node), ROOT=7,
S=8 (slider), I=9 (input). Note the gap: 6 is not used. `padding=0` is a default stored here
for historical reasons.

---

## 6. Frame anatomy

Every frame is driven by LÖVE's event pump calling `love.update(dt)` then `love.draw()`. The
Talisman mod installs its own `love.update` wrapper (see §4 / `main.lua:2111`) before the
vanilla path runs; the anatomy below describes the vanilla `Game:update` / `Game:draw` flow
that executes inside that wrapper.

### Update pass (`game.lua:2616–2942`)

**Step 1 — nuGC** (`game.lua:2617`)

```lua
nuGC(nil, nil, true)
```

Forces a Lua GC step at the top of every frame. `nuGC` is in
`functions/misc_functions.lua:718`.

**Step 2 — Frame counter and housekeeping** (`game.lua:2619–2637`)

`G.FRAMES.MOVE` increments. Tutorial controller runs if tutorial not complete. `modulate_sound`
adjusts audio. `SMODS.enh_cache:clear()` resets the enhancement cache. Canvas juice updates.
Then the four always-advancing timers update:

```lua
self.TIMERS.REAL     += dt
self.TIMERS.REAL_SHADER = reduced_motion and 300 or self.TIMERS.REAL
self.TIMERS.UPTIME   += dt
self.TIMERS.BACKGROUND += dt * spin_amount
self.real_dt = dt
```

**Step 3 — fbf gate** (`game.lua:2640`)

```lua
if not G.fbf or G.new_frame then
    G.new_frame = false
    -- ... bulk of update ...
end
```

When `G.fbf` is nil/false (normal), this block always executes. When frame-by-frame debug mode
is active (`G.fbf = true`), the block only executes when `G.new_frame` is set (typically by a
keypress). `G.new_frame` is cleared immediately on entry so it fires for exactly one frame.

Everything from here through MOVEABLES:update is inside this gate.

**Step 4 — Pause gate** (`game.lua:2652`)

```lua
if G.SETTINGS.paused then dt = 0 end
```

Zeroing the local `dt` propagates to TIMERS.TOTAL (step 6) and all dt-parameterized state
handlers called in step 8. It does NOT affect `self.real_dt`, which is already captured.

**Step 5 — ACC accumulator** (`game.lua:2654–2664`)

ACC resets to 0 whenever STATE changes. During HAND_PLAYED and NEW_ROUND it accretes at
`0.2 * dt * GAMESPEED`, capped at 16. SPEEDFACTOR is then set (using the three-condition check
detailed in §5), and ACC above 2 adds directly to SPEEDFACTOR, creating a runaway speed-up as
long scoring animations pile up.

**Step 6 — TIMERS.TOTAL** (`game.lua:2666`)

```lua
self.TIMERS.TOTAL += dt * self.SPEEDFACTOR
```

TOTAL is the time base for all Events and ease animations. Because `dt` was zeroed in step 4
when paused, TOTAL freezes while paused (unless an event was created with `pause_force=true`,
in which case it uses TIMERS.REAL and advances anyway).

**Step 7 — Edition color animation** (`game.lua:2668–2674`)

`G.C.DARK_EDITION` and `G.C.EDITION` RGB components are written as sin(REAL * constant)
expressions every frame. This is the pulsing holographic color effect.

**Step 8 — E_MANAGER:update** (`game.lua:2690`)

```lua
self.E_MANAGER:update(self.real_dt)
```

Processes all five event queues. The EventManager has its own internal `queue_dt = 1/60` rate
limiter (`event.lua:117`): it only advances if at least 1/60 s has elapsed since the last
event tick, so at >60 FPS some frames skip event processing.

**Step 9 — State dispatch** (`game.lua:2746–2813`)

All 19 vanilla states plus SMODS_BOOSTER_OPENED are checked with **separate `if` statements**,
not `elseif`. The critical consequence: if a state handler sets `G.STATE` to a new value before
the dispatch loop reaches that new state's `if` check, the new handler also fires in the same
frame. This is intentional — `SELECTING_HAND` auto-transitions to `DRAW_TO_HAND` mid-loop when
the hand is empty (`game.lua:2747–2749`).

```lua
-- Example of same-frame double-dispatch
if self.STATE == self.STATES.SELECTING_HAND then
    if (not G.hand.cards[1]) and G.deck.cards[1] ... then
        G.STATE = G.STATES.DRAW_TO_HAND   -- transition here
        G.STATE_COMPLETE = false
    ...
end
if self.STATE == self.STATES.DRAW_TO_HAND then  -- fires in same frame
    self:update_draw_to_hand(dt)
end
```

Unhandled states (SPLASH, TUTORIAL, MENU in specific conditions, etc.) fall through all checks
silently — no `else` error branch.

**Step 10 — ANIMATIONS** (`game.lua:2816–2820`)

```lua
remove_nils(self.ANIMATIONS)
for k, v in pairs(self.ANIMATIONS) do
    v:animate(self.real_dt * self.SPEEDFACTOR)
end
```

Animated objects (sprite sheet tickers, etc.) advance by `real_dt * SPEEDFACTOR`. Pausing sets
SPEEDFACTOR to 1 (not 0), so animations continue at 1× during pause.

**Step 11 — exp_times pre-computation** (`game.lua:2824–2826`)

Three exponential-decay constants are pre-computed once per frame and stored in `G.exp_times`:

```lua
G.exp_times.xy    = math.exp(-50 * real_dt)
G.exp_times.scale = math.exp(-60 * real_dt)
G.exp_times.r     = math.exp(-190 * real_dt)
```

These are the lerp decay factors used by every Moveable's spring physics. Pre-computing once
avoids redundant `exp()` calls across potentially hundreds of objects.

**Step 12 — MOVEABLES:move** (`game.lua:2828–2834`)

```lua
local move_dt = math.min(1/20, self.real_dt)
for k, v in pairs(self.MOVEABLES) do
    if v.FRAME.MOVE < G.FRAMES.MOVE then v:move(move_dt) end
end
```

`move_dt` is capped at 1/20 s (50 ms) — prevents position teleporting during long frames.
The `FRAME.MOVE < G.FRAMES.MOVE` guard ensures each object moves exactly once per frame even
if it was added to MOVEABLES during the same iteration.

**Step 13 — MOVEABLES:update** (`game.lua:2837–2840`)

```lua
for k, v in pairs(self.MOVEABLES) do
    v:update(dt * self.SPEEDFACTOR, self.real_dt)
    v.states.collide.is = false
end
```

`update` receives the speed-scaled dt as first arg and raw real_dt as second. Collision state
is cleared here unconditionally each frame; CONTROLLER:update in the next step re-sets it for
objects under the cursor.

**Step 14 — CONTROLLER:update** (`game.lua:2844`)

```lua
self.CONTROLLER:update(self.real_dt)
```

Outside the fbf gate. Runs every frame regardless of pause or fbf state. Processes input,
updates `CONTROLLER.hovering`, `CONTROLLER.focused`, `CONTROLLER.dragging`, handles
touch-to-mouse translation, fires button callbacks. Takes `real_dt` (not scaled).

**Step 15 — FILE_HANDLER poll** (`game.lua:2892–2940`)

Coalesced save dispatch. Pushes to `G.SAVE_MANAGER.channel` when `FILE_HANDLER.update_queued`
is set AND either: forced, stage changed, pause state changed (during a run), or
`F_SAVE_TIMER` seconds elapsed since last save. The save manager thread runs independently
and handles the actual disk write.

---

### Draw pass (`game.lua:2940–3220`)

`G.FRAMES.DRAW` increments first. `reset_drawhash()` clears spatial-hash cull state.

**Canvas architecture:** Two canvases. `G.CANVAS` is the game canvas (drawn at `G.CANV_SCALE`).
`G.AA_CANVAS` is the post-process canvas (CRT/bloom blit target). Both are set up in
`Game:start_up`.

**Draw order (objects rendered onto G.CANVAS):**

1. **Background clear** — `love.graphics.clear(0,0,0,1)`, then `G.SPLASH_BACK:draw()` if present.
2. **NODE** (`G.I.NODE`) — root-only, no parent filter (SPLASH screen logos etc.).
3. **MOVEABLE** (`G.I.MOVEABLE`) — root-only.
4. **UIBOX (background pass)** (`G.I.UIBOX`) — all UIBoxes except: `attention_text=true`,
   parented, OVERLAY_MENU, screenwipe, OVERLAY_TUTORIAL, debug_tools, achievement_notification,
   online_leaderboard. This excludes boss-warning text and similar overlays.
5. **CARDAREA** (`G.I.CARDAREA`) — root-only. Draws hand area, deck area, shop areas, etc.
   (CARDAREA draws its contained CARDs).
6. **SPLASH_FRONT** — drawn after CardAreas if present.
7. **OVERLAY_TUTORIAL** — tutorial highlight overlay + its highlight rectangles.
8. **OVERLAY_MENU** — pause menu / settings overlay (if not being dragged).
9. **debug_tools** — (if present and not dragged).
10. **ALERT** (`G.I.ALERT`) — toast notifications.
11. **Dragged card** — `CONTROLLER.dragging.target` drawn last among non-cursor objects so it
    floats above everything.
12. **Focused card** — `CONTROLLER.focused.target` if it's a Card and not the hand's card OR is
    the dragging target (draws the card being held above the hand).
13. **POPUP** (`G.I.POPUP`) — description popups, tooltips.
14. **achievement_notification** — achievement banner.
15. **screenwipe** — transition wipe overlay.
16. **CURSOR** — always drawn last in game-space.

**Post-process blit** (`game.lua:3140–3173`):

`G.CANVAS` is drawn into `G.AA_CANVAS` with the `CRT` shader active (distortion, bloom, scan
lines, glitch intensity). `G.AA_CANVAS` is then drawn to the real framebuffer at `1/CANV_SCALE`
— this is the final screen-scale step.

**CRT shader time input** (`game.lua:3151`): `400 + G.TIMERS.REAL` — offset by 400 to avoid
the near-zero startup period where the time value interacts badly with the shader math.

**Post-draw overlays** (drawn directly to framebuffer, not via canvases):
- FPS display: `love.graphics.print` if `G.SETTINGS.show_fps` (`game.lua:3177`).
- Performance overlay: timer-checkpoint trend charts if `G.SETTINGS.perf_mode` (`game.lua:3179`).

---

### Timer summary

| Timer | Gate | Scaled by | Freezes when |
|---|---|---|---|
| REAL | always | 1× | never |
| REAL_SHADER | always | 1× (or pinned to 300) | never |
| UPTIME | always | 1× | never |
| TOTAL | fbf gate | SPEEDFACTOR | paused OR not RUN |
| BACKGROUND | always | spin_amount | spin=0 |

## 7. Object model: Object → Node → Moveable → Card/CardArea

The entire visible game is built from four concrete base classes arranged in a
single inheritance chain. All classes use the SNKRX-derived prototype system
(`object.lua`): `Object:extend()` copies only `__`-prefixed metamethods into the
subclass table and sets `cls.super = self`, so upcalls are `ClassName.super.method(self, ...)`.
Instantiation goes through `Object:__call`, which does `setmetatable({}, self)` then
calls `obj:init(...)`.

### Inheritance chain

```
Object          (object.lua)
  └─ Node       (engine/node.lua)       -- transform, collision, hover/click states, children
       └─ Moveable (engine/moveable.lua) -- VT easing, Major/Minor role system, juice
            ├─ Card        (card.lua)         -- playing card / joker / consumable
            ├─ CardArea    (cardarea.lua)      -- ordered card container
            ├─ Blind       (blind.lua)         -- boss blind display object
            ├─ Card_Character (card_character.lua) -- character card
            ├─ Sprite      (engine/sprite.lua) -- atlas-backed sprite
            ├─ DynaText    (engine/text.lua)   -- dynamic text
            ├─ UIBox       (engine/ui.lua)     -- root UI container
            ├─ UIElement   (engine/ui.lua)     -- leaf UI node
            └─ Particles   (engine/particles.lua)
```

Classes outside this chain that use `Object:extend()` directly (no move/draw
machinery): `Game`, `Back`, `Tag`, `Controller`, `Event`, `EventManager`,
`SMODS.GameObject`.

### Object (object.lua)

Provides `extend()`, `is()`, and `__call`. No state. Sourced from SNKRX (MIT).

- `extend()` — copies `__`-keys, sets `super`, returns new class table.
- `is(T)` — walks metatables to check type membership (Lua's equivalent of `instanceof`).
- `__call(...)` — constructs an instance: `setmetatable({}, self)` + `init(...)`.

### Node (engine/node.lua)

Everything visible (and some invisible things like `G.ROOM`) is a Node.

| Field | Description |
|---|---|
| `T` | Logical transform: `{x, y, w, h, r, scale}` in game units |
| `CT` | Collision transform; defaults to `T`, Card overrides to `VT` (`card.lua:12`) |
| `states` | `{visible, collide, hover, click, drag, release_on}` each with `.can`/`.is` |
| `container` | Parent Node for coordinate translation; defaults to `G.ROOM` |
| `children` | Table of child Nodes; tree is walked by `Node:draw()` and `Node:remove()` |
| `FRAME` | `{DRAW, MOVE}` frame counters to skip redundant recalculations |
| `ID` | Unique integer, incremented from `G.ID` at construction |
| `ARGS` / `RETS` | Reusable scratch tables (avoids garbage in the hot path) |
| `created_on_pause` | If true, node moves even when `G.SETTINGS.paused` |

Key methods:

- `Node:draw()` — calls `add_to_drawhash(self)`, then recurses into `children`.
- `Node:collides_with_point(point)` — applies container translation/rotation then
  axis-aligned rectangle test (or rotated rectangle when `T.r` is significant).
- `Node:hover()` / `Node:stop_hover()` — creates/removes `children.h_popup` UIBox.
  `stop_hover` respects Cryptid's `force_popup`/`force_tooltips` flag.
- `Node:remove()` — pulls self from `G.I.NODE`, `G.STAGE_OBJECTS`, all
  `G.CONTROLLER.*` targets, sets `self.REMOVED = true`, then recurses into children.

Concrete Nodes register in `G.I.NODE` only when `getmetatable(self) == Node`
(bare Node instances, not subclass instances).

### Moveable (engine/moveable.lua)

Adds a **Visible Transform** (`VT`) that eases toward the logical `T` each frame,
plus a Major/Minor role system for attaching objects to each other.

| Field | Description |
|---|---|
| `VT` | Visible transform: `{x, y, w, h, r, scale}`; eases to `T` via `move_xy`/`move_r`/`move_scale`/`move_wh` |
| `velocity` | `{x, y, r, scale, mag}` — intermediate velocity for easing |
| `role` | `{role_type, major, offset, xy_bond, wh_bond, r_bond, scale_bond}` |
| `pinch` | `{x, y}` — when true, VT dimension eases toward 0 (used for card flip) |
| `juice` | Animation burst: scale + rotation oscillation for 0.4 s; set via `juice_up()` |
| `offset` | Additional positional offset (separate from role.offset) |
| `STATIONARY` | Set true when VT has converged to T; used to skip unnecessary draw work |

**Role system.** Every Moveable has a `role_type`:
- `Major` — calculates its own VT movement each frame via `move_xy`, `move_r`, etc.
- `Minor` — welded to a `major` Moveable; calls `move_with_major(dt)` which copies
  or derives VT from the major's VT plus a role offset. Bond types control per-axis
  inheritance: `Strong` copies, `Weak` calculates independently.
- `Glued` — directly shares the major's `T` table pointer (e.g. Card sprite
  children use `draw_major` via `Sprite:set_role({role_type='Glued', ...})`).

`Moveable:move(dt)` guards on `FRAME.MOVE >= G.FRAMES.MOVE` to run at most once
per frame per object.

All Moveables are inserted into `G.MOVEABLES` (iterated in the move pass) and
into `G.I.MOVEABLE` only for bare `Moveable` instances.

### Card (card.lua)

`Card = Moveable:extend()`. Represents every card type: playing cards (`ability.set
= 'Default'` or `'Enhanced'`), jokers (`'Joker'`), consumables (tarot/planet/
spectral), vouchers, boosters, and editions.

Key additions over Moveable:

| Field | Description |
|---|---|
| `config.card` | Playing-card suit/rank data (`G.P_CARDS` entry) |
| `config.center` | Ability/identity data (`G.P_CENTERS` entry) |
| `ability` | Active state table; `ability.set` is the card type discriminant |
| `tilt_var` | `{mx, my, dx, dy, amt}` — 3D mesh tilt parameters fed to the sprite shader |
| `area` | The `CardArea` this card currently belongs to (nil when unattached) |
| `facing` / `sprite_facing` | `'front'` or `'back'`; `sprite_facing` lags behind `facing` during flip animation |
| `children.front` | Sprite for the playing-card face (nil for non-playing-cards) |
| `children.back` | Sprite for the card back |
| `children.center` | Sprite for the joker/consumable/voucher art |
| `sort_id` | Monotonically increasing; used for stable sort ordering |

**`CT = self.VT` (card.lua:12)** — Card overrides the collision transform to
track the visible position, not the logical position, so collision detection uses
where the card appears on screen.

**`Card:draw(layer)`** draws in layers (`'shadow'`, `'card'`, `'both'`).
Steamodded wraps `Card:draw` and runs `SMODS.DrawSteps` in sorted order
(`card_draw.lua`). Each DrawStep has an `order` and optional `conditions`.
The tilt shader parameters (`tilt_var.mx`/`my`) are computed in DrawStep
`'card_tilt'` (`card_draw.lua:76`) when `states.hover.is` is true — this is the
anchor for the 3D warp (relevant to the TAP_DESC_RELAX touch fix).

**`Card:remove()`** calls `self.area:remove_card(self)` if attached, then
`remove_from_deck`, then removes from `G.I.CARD` and calls `Node.remove`.

Card instances register in `G.I.CARD` when `getmetatable(self) == Card`.

### CardArea (cardarea.lua)

`CardArea = Moveable:extend()`. Ordered container for Card objects.

| Field | Description |
|---|---|
| `cards` | Array of Card objects in draw/layout order |
| `highlighted` | Array of currently highlighted cards |
| `config.type` | Area identity: `'hand'`, `'joker'`, `'consumeable'`, `'deck'`, `'discard'`, `'play'`, `'shop'`, etc. |
| `config.card_limit` | Virtual read via `__index`; returns `card_limits.total_slots - card_limits.extra_slots_used`. Write via `__newindex` updates `card_limits.mod`. |
| `config.card_limits` | Underlying table: `{base, mod, extra_slots, extra_slots_used, total_slots}` |
| `config.highlighted_limit` | Max simultaneous highlights (stored under this name; the constructor *param* is `highlight_limit`) |
| `card_w` | Per-area card width (default `G.CARD_W`); used by `align_cards` layout |

**Key methods** (all in cardarea.lua):

| Method | Line | Summary |
|---|---|---|
| `CardArea:init` | 7 | Moveable.init + state flags off + config metatable + cards/highlighted/children tables. Registers in `G.I.CARDAREA` via `table.insert`. |
| `CardArea:emplace` | 50 | Pushes to `self.cards[]` (front or back depending on `location`/type), handles flip state, calls `set_card_area`, `set_ranks`, `align_cards`. Has deck/joker side-effect checks. No `add_to_deck` call. |
| `CardArea:remove_card` | 85 | Reverse-iterates `self.cards[]`, calls `card:remove_from_area()`, `table.remove`, then `remove_from_highlighted(card, true)`, then `set_ranks()`. Does **not** call `align_cards` — realignment happens each frame via `move()`. Returns the removed card. |
| `CardArea:change_size` | 113 | Adjusts `card_limits.mod`. The old event-based branch is dead (guarded by `if true then … return end`). |
| `CardArea:can_highlight` | 136 | Returns true for hand/joker/consumeable/shop types; controller path narrows to hand only. |
| `CardArea:add_to_highlighted` | 154 | Behaviour differs by type: shop/joker/consumeable **evict** `highlighted[1]` when at limit, then add; hand/other silently skip (no return value). Calls `card:highlight(true)`. |
| `CardArea:remove_from_highlighted` | 232 | Reverse-iterates `highlighted[]`, splices out, calls `card:highlight(false)`. Respects `forced_selection` guard on hand. |
| `CardArea:set_ranks` | 259 | Assigns sequential `card.rank = k` to all cards; sets drag/collide state flags by area type. |
| `CardArea:move` | 278 | Per-frame: hand Y-slide logic, then `Moveable.move`, then `align_cards()`. **This is where realignment after remove happens.** |
| `CardArea:align_cards` | 463 | Computes `T.x/T.y/T.r` for each card by type (deck/discard/hand/play/shop/joker/consumeable). Also calls `table.sort` on `self.cards` for drag-reorder on joker/shop/consumeable/play areas. |
| `CardArea:sort` | 633 | Sorts by `get_nominal()` (desc/asc), suit nominal, or `.order` field. **Does not use `sort_id`.** Does not call `align_cards` — the next `move()` frame handles that. |
| `CardArea:shuffle` | 628 | `pseudoshuffle` + `set_ranks()`. |
| `CardArea:draw_card_from` | 648 | Removes top card from another CardArea and emplaces it into self. Capacity-checked. |
| `CardArea:save` | 675 | Returns `{cards={...}, config=self.config}` — serialises both the cards array and the config table. |
| `CardArea:load` | 688 | Clears cards/children, restores config metatable, then for each saved card: sets global `loading=true`, constructs `Card(...)`, sets `loading=nil`, calls `card:load(cardTable)`, pushes to `self.cards[]`, restores `highlighted[]` if flagged, calls `card:set_card_area(self)`. Ends with `set_ranks` + `align_cards` + `hard_set_cards`. |
| `CardArea:remove` | 728 | `remove_all(cards)`, `remove_all(children)`, splices self from `G.I.CARDAREA`. |

CardArea disables its own `drag`, `hover`, and `click` states by default (it is a
container, not an interactive object). `G.deck` re-enables them each frame in
`CardArea:update(dt)`.

**`CardArea:draw()`** draws `children.area_uibox` (the card-count overlay) and
then iterates `self.cards` directly to call `card:draw(layer)` — the draw pass
is inside CardArea:draw, not delegated to a separate draw hash. (Deck area draws
every Nth card for the thin-stack visual.)

CardArea instances register in `G.I.CARDAREA` (sequential array, same structure as
`G.I.CARD`) when `getmetatable(self) == CardArea`.

### Instance registries

| Registry | Contains | Populated by |
|---|---|---|
| `G.I.NODE` | Bare `Node` instances only | `Node:init` |
| `G.I.MOVEABLE` | Bare `Moveable` instances only | `Moveable:init` |
| `G.I.CARD` | All `Card` instances | `Card:init` |
| `G.I.CARDAREA` | All `CardArea` instances | `CardArea:init` |
| `G.MOVEABLES` | All `Moveable` subclass instances (everything with VT) | `Moveable:init` |

Note: `G.MOVEABLES` is iterated in `love.update` for the move pass (all Moveables
including Cards, CardAreas, Sprites, etc.). `G.I.*` registries are for
type-specific lookup. Removal from all registries is handled by the respective
`remove()` chain.

### Gotchas

- **`Object:extend()` copies only `__`-prefixed keys** into the subclass. Regular
  methods are inherited through the metatable chain, not copied. Adding a method to
  a superclass after subclasses are created affects all subclasses that haven't
  shadowed that method.

- **`getmetatable(self) == ClassName` guards on `init` and `remove`** mean that
  only direct instances (not subclass instances) register in the bare-class
  registries (`G.I.NODE`, `G.I.MOVEABLE`). A `Card` instance does not appear in
  `G.I.MOVEABLE`; it appears in `G.I.CARD` and `G.MOVEABLES`.

- **`Node:remove()` clears all `G.CONTROLLER.*` targets.** This means removing
  any Node while it is focused/hovered/dragged/clicked silently nils the
  controller's reference, preventing a use-after-free but potentially missing a
  stop-hover call for the popup if `stop_hover` wasn't called before removal.

- **`G.I.CARD` is a plain sequential array**, not an ID-keyed table. Registration
  uses `table.insert(G.I.CARD, self)`; removal uses an `ipairs` scan +
  `table.remove`. There is no O(1) lookup by `self.ID` — code that tries
  `G.I.CARD[self.ID]` will hit the wrong card or nil.

- **`Card:update` flip detection has a vacuous `or true`** (card.lua:4649, 4657).
  Both the `f2b` and `b2f` blocks guard the sprite-swap with
  `if self.sprite_facing == 'front'/'back' or true then` — the `or true` makes
  the sprite-facing check always pass. Only the inner `VT.w <= 0` gate is real.
  This is a debug remnant; the flip state machine fires every frame while
  `self.flipping` is set, regardless of which way the card faces.

- **`set_edition` negative path increments `card_limit` only when both
  `self.edition == nil` AND `self.added_to_deck` are true** (card.lua:549–557).
  The nil-edition check distinguishes "card was already vanilla" from "card just
  had its edition removed", but a freshly constructed negative joker that has not
  yet been added to deck skips the increment entirely. The nil-sentinel alone is
  not sufficient to predict the card_limit outcome.

- **`Card.CT = self.VT`** means collision detection tracks the card's visible
  (eased) position, not its logical target position. Fast-moving cards may have
  collisions lag slightly behind `T`.

- **`Moveable:move(dt)` is guarded by `FRAME.MOVE`** to prevent double-move in
  one frame. However the `--WHY ON EARTH DOES THIS LINE MAKE IT RUN 2X AS FAST?`
  comment at `moveable.lua:286` documents a profiling artefact: uncommenting
  `love.timer.getTime()` near that guard causes movement to run at 2× speed.
  The cause is not documented; the line remains commented out.

- **`move_wh` has a Talisman-inserted guard** (`moveable.lua:435`): when `Big`
  (OmegaNum) exists and the state is `MENU`, it calls `to_number()` on all four
  VT/T width/height values. This is a safety cast to prevent big-number types
  from leaking into layout arithmetic.

- **`CardArea:remove_card` does not call `align_cards()`** (cardarea.lua:85).
  It only calls `set_ranks()`. Realignment happens passively each frame via
  `CardArea:move() → align_cards()`. Code that removes a card and then
  immediately reads card positions will see stale `T.x/T.y`.

- **`CardArea:add_to_highlighted` never returns false** (cardarea.lua:154).
  The old early-return guard is commented out (line 155). Current behaviour:
  shop/joker/consumeable areas **evict** `highlighted[1]` when the limit is hit
  and add the new card anyway; hand/other areas silently discard the highlight
  request with no return value. Callers cannot rely on a return value to detect
  rejection.

- **`CardArea:load` bypasses emplace** (cardarea.lua:712–721). It pushes
  directly to `self.cards[]` and calls `card:set_card_area(self)` separately.
  emplace's flip logic, joker-tally check, and deck unlock check are all
  skipped. The `loading` flag is a global (`loading = true` / `loading = nil`
  around the `Card()` constructor call), not a constructor parameter.

- **`CardArea:sort` does not use `sort_id`** (cardarea.lua:633). It sorts by
  `get_nominal()` (rank value), suit nominal, or `.order` field. `sort_id` only
  appears inside `align_cards` as a drag-pin tiebreaker weight, not as a sort key.

- **`change_size`'s event-based branch is dead code** (cardarea.lua:114).
  The function opens with `if true then … return end`, making the entire
  remainder of the function (the `G.E_MANAGER:add_event` path with draw-to-hand
  side-effects) permanently unreachable.

- **`config.highlighted_limit` vs `config.highlight_limit`**: the constructor
  accepts a `highlight_limit` param but stores it as `config.highlighted_limit`
  (with a `d`). All internal reads use the stored name. Reading
  `config.highlight_limit` returns nil.

## 8. Round flow & the scoring pipeline

Scoring in this build is a Lua coroutine that runs across multiple frames (≈30 ms
per yield on Android). The base game's `G.FUNCS.evaluate_play` is overwritten six
times before the final version runs; each layer captures the previous definition in
a local closure. Talisman replaces vanilla `number` arithmetic with
`OmegaNum`-backed big-number objects and inserts the coroutine harness. Steamodded
provides an extensible `Scoring_Parameter` registry (chips/mult and their x-
multipliers) with a five-layer `calculate_individual_effect` chain. Cryptid adds
one more wrapper to that chain and hooks `calculate_round_score` to insert
Ascension scaling.

### Key functions

| Function | File:line | Purpose |
|---|---|---|
| `G.FUNCS.evaluate_play` (active) | `talisman.lua:822` | `Talisman.calculating_score` guard wrapper — outermost definition at runtime |
| `G.FUNCS.evaluate_play` (coroutine wrapper) | `talisman.lua:676` | Creates `G.SCORING_COROUTINE`, sets `G.LAST_SCORING_YIELD`, resets `G.CARD_CALC_COUNTS`, first-resumes the coroutine |
| `G.FUNCS.evaluate_play` (inner body) | `main.lua:2059` | Calls `evaluate_play_intro → evaluate_play_main → evaluate_play_debuff → evaluate_play_final_scoring → evaluate_play_after` unconditionally |
| `evaluate_play_intro` | `state_events.lua:715` | Resolves poker hand (`get_poker_hand_info`), sets hand text, fires context `{type="before"}` — **does not** fire `context.before` |
| `evaluate_play_main` | `state_events.lua:782` | Seeds `hand_chips`/`mult` (lines 783-784), fires `context.before` (line 797), re-seeds (lines 803-804), iterates scoring hand calling `SMODS.score_card` per card |
| `evaluate_play_debuff` | `state_events.lua:947` | Handles debuffed-card animation |
| `evaluate_play_final_scoring` | `state_events.lua:972` | Applies edition multipliers, fires context `{type="final_scoring_no_retrigger"}`, calls `evaluate_play_after` only when Talisman is **not** loaded (line 1019) |
| `evaluate_play_after` | `state_events.lua:1024` | Resets Scoring_Parameters via `G.E_MANAGER` immediate event (lines 1026-1034); always called from `main.lua:2072` when Talisman is loaded |
| `SMODS.score_card` | `utils.lua:1930` | Per-card scoring: calls `calculate_edition`, `calculate_main_scoring`, `calculate_repetitions`/`retriggers` |
| `SMODS.calculate_individual_effect` (base) | `utils.lua:1234` | Iterates `G.SMODS_SCORING_PARAMS`, dispatches to the active `Scoring_Parameter` instance |
| `SMODS.calculate_effect` | `utils.lua:1415` | Drives `trigger_effects`, calls `calculate_individual_effect` |
| `SMODS.calculate_round_score` | `utils.lua:2838` | Reads final chips/mult from Scoring_Parameters, applies to `G.GAME.current_round` |
| `SMODS.Scoring_Parameter:modify` | `Mods/Steamodded/src/game_object.lua:3582` | Applies a delta to a scoring accumulator |
| `Cryptid.ascend` | `ascended.lua:164` | Applies Ascension scaling (two modes) |
| `G.FUNCS.get_poker_hand_info` | `state_events.lua:684` | Identifies the played hand type |

### Key structures

| Structure | Description |
|---|---|
| `G.SMODS_SCORING_PARAMS` | Registry of `SMODS.Scoring_Parameter` instances; chips instance (`game_object.lua:3638`) and mult instance (`game_object.lua:3689`) are the core accumulators |
| `SMODS.Scoring_Parameter` (chips) | Keys: `chips`, `h_chips`, `chip_mod`, `x_chips`, `xchips`, `Xchip_mod` |
| `SMODS.Scoring_Parameter` (mult) | Keys: `mult`, `h_mult`, `mult_mod`, `x_mult`, `Xmult`, `xmult`, `x_mult_mod`, `Xmult_mod` |
| `G.SCORING_COROUTINE` | The active coroutine; guards in `state_events.lua` at lines 423, 585, 663, 674 skip conflicting operations while it runs |
| `G.SCORING_START` | **No longer exists.** Was written by the removed main.lua coroutine wrapper; the talisman.lua copy never wrote it. All references gone. |
| `G.LAST_SCORING_YIELD` | Set at coroutine launch (`talisman.lua:678`); refreshed before each frame resume (`talisman.lua:764`); read by the yield check in `Card:calculate_joker` (`talisman.lua:796`) |
| `G.CARD_CALC_COUNTS` | Reset at coroutine launch (`talisman.lua:679`); tracks per-card calculation counts for animation |
| `TIME_BETWEEN_SCORING_FRAMES` | `0.03` (30 ms); defined in `talisman.lua:776` |

### Control flow

1. User plays cards. `G.FUNCS.evaluate_play` (the active wrapper at `talisman.lua:822`,
   the `Talisman.calculating_score` guard) calls the coroutine wrapper at `talisman.lua:676`,
   which creates `G.SCORING_COROUTINE` around `oldplay` (= `main.lua:2059` body),
   sets `G.LAST_SCORING_YIELD`, resets `G.CARD_CALC_COUNTS`, and does the first resume.

2. `love.update` resumes the coroutine each frame. The `coroutine.resume` call
   is at `talisman.lua:766` (inside talisman's `love.update` wrapper, after updating
   `G.LAST_SCORING_YIELD` at line 764). The Card `calculate_joker` yield wrapper
   (`talisman.lua:785`) yields **before** calling the original joker handler when
   `30 ms` have elapsed since `G.LAST_SCORING_YIELD`.

3. Inside the coroutine the inner body (`main.lua:2059`) calls all five phases in
   order: `evaluate_play_intro → evaluate_play_main → evaluate_play_debuff →
   evaluate_play_final_scoring → evaluate_play_after`.

   **`evaluate_play_after` is always called from this inner body (`main.lua:2072`)**
   when Talisman is loaded; it is not on a separate "coroutine cleanup path".

4. `evaluate_play_intro` (`state_events.lua:715`) identifies the hand via
   `get_poker_hand_info` and sets display text. It does **not** fire
   `context.before`.

5. `evaluate_play_main` (`state_events.lua:782`) seeds `hand_chips` and `mult`
   **twice**: first at lines 783-784 (before `context.before`), then again at
   lines 803-804 (after `context.before` fires at line 797). The second seed is
   the effective starting value for card iteration.

6. For each card in the scoring hand, `SMODS.score_card` calls
   `SMODS.calculate_main_scoring` (iterating `cardarea.cards`, not `scoring_hand`
   directly), which calls `SMODS.calculate_effect`, which calls
   `SMODS.calculate_individual_effect` through a five-layer wrapper chain (see
   Gotchas).

7. x_chips and x_mult are applied as deltas, not direct multiplications:
   - x_chips: `hand_chips * (amount - 1)` added via `Scoring_Parameter:modify`
     (`Mods/Steamodded/src/game_object.lua:3665`)
   - x_mult: `mult * (amount - 1)` added via `Scoring_Parameter:modify`
     (`Mods/Steamodded/src/game_object.lua:3717`)

8. `evaluate_play_final_scoring` fires `context.final_scoring_no_retrigger` and
   calls `evaluate_play_after` only when Talisman is **not** loaded (`state_events.lua:1019`).

9. `evaluate_play_after` (`state_events.lua:1024`) defers Scoring_Parameter reset
   through a `G.E_MANAGER` immediate event (lines 1026-1034).

10. `SMODS.calculate_round_score` (with Cryptid override at `overrides.lua:2275-2276`)
    reads the final accumulators and applies Ascension scaling via `Cryptid.ascend`
    (`ascended.lua:164`) in two modes:
    - **not_modest** (default, exponential): `num * (1.25 + sunnumber.not_modest) ^ cry_asc_num`
    - **modest** (linear): `num * (1 + (0.25 + sunnumber.modest) * cry_asc_num)`

### Interactions

- Talisman replaces all numeric types with `OmegaNum` via `to_big` (first defined
  `main.lua:1942`, then overwritten by `talisman.lua:524`; the talisman.lua version
  is active).
- Talisman's `SMODS.calculate_individual_effect` wrapper (`talisman.lua:984`)
  converts vanilla number results to big numbers.
- Talisman's `SMODS.calculate_effect` wrapper (`talisman.lua:1186`) sets
  `effect.juice_card = nil` but does not suppress all animations.
- The `mod_mult` trophy-cap wrapper in Cryptid (`overrides.lua:1398-1399`) applies
  a ceiling to mult accumulation.
- Talisman provides extended Card methods (`get_chip_e_bonus`, etc., lines
  860-910) used during `evaluate_play_main` card iteration.

### Gotchas

- **`G.FUNCS.evaluate_play` is defined four times.** The chain, in
  definition order (each capturing the previous in a closure):
  1. `talisman.lua:641` — inner evaluate_play body (calls all phases including
     `evaluate_play_after` unconditionally at line 654)
  2. `talisman.lua:676` — Talisman coroutine wrapper (creates `G.SCORING_COROUTINE`,
     sets `G.LAST_SCORING_YIELD`, resets `G.CARD_CALC_COUNTS`, first-resumes)
  3. `main.lua:2059` — inner body redefinition (calls all five phases including
     `evaluate_play_after` at line 2072); this is what runs inside the coroutine
  4. `talisman.lua:822` — active `Talisman.calculating_score` guard wrapper

  The **active** `G.FUNCS.evaluate_play` at runtime is definition #4
  (`talisman.lua:822`), which calls #2's coroutine wrapper, which runs #3
  inside the coroutine. Definition #1 is the original inner body that #2
  captured before #3 overwrote it; #3 is what actually executes.

  Note: `main.lua` previously duplicated definitions #2 and #4 (plus a second
  `love.update` wrapper), causing double coroutine launch, double resume, and
  double `CARD_CALC_COUNTS` increment. These duplicates were removed by
  `patch_main_lua.py` step 10.

- **`evaluate_play_after` is called from inside the coroutine body, not on a
  separate cleanup path.** `main.lua:2072` calls it unconditionally at the end of
  the inner body that runs inside the coroutine. There is no post-coroutine
  teardown that calls it separately.

- **`context.before` fires in `evaluate_play_main`, not `evaluate_play_intro`.**
  It is dispatched at `state_events.lua:797`, inside `evaluate_play_main`.
  `evaluate_play_intro` fires a `{type="before"}` context (unrelated to the
  `context.before` dispatch), not `context.before` itself.

- **`hand_chips` and `mult` are seeded twice in `evaluate_play_main`.** First
  seed at lines 783-784 (before `context.before`), second seed at 803-804 (after
  `context.before`). Mods that modify the starting values must fire during or
  after `context.before` to affect the second (effective) seed.

- **Five layers wrap `SMODS.calculate_individual_effect`**, in load order:
  1. `utils.lua:1234` — Steamodded base
  2. `exotic.lua:350-351` — Cryptid exotic items wrapper #1
  3. `exotic.lua:1693-1694` — Cryptid exotic items wrapper #2
  4. `overrides.lua:2183-2184` — Cryptid overrides wrapper
  5. `talisman.lua:984` — Talisman big-number conversion wrapper

- **`Cryptid.ascend` has two distinct scaling modes.** `not_modest` (default)
  is exponential; `modest` is linear. The base factor and exponent/multiplier are
  further modified by `sunnumber.not_modest` / `sunnumber.modest` respectively.

- **`SMODS.calculate_main_scoring` iterates `cardarea.cards`, not `scoring_hand`
  directly** (`utils.lua:1971`). Cards present in the area but not in the scoring
  hand are filtered inside the loop.

- **`Card:calculate_joker`, `Card:use_consumable`, and the `calculating_score`
  guard for `G.FUNCS.evaluate_play` were triple-defined (talisman.lua then
  main.lua); the main.lua re-definitions have been removed.** The main.lua copies
  were verbatim copies that double-wrapped: `Card:calculate_joker` double-incremented
  `G.CARD_CALC_COUNTS` per joker call (making `totalCalcs` 2x real and
  `jokersYetToScore` go negative prematurely). The active `Card:calculate_joker`
  is now `talisman.lua:785`.

- **`love.update` was double-wrapped; the outer main.lua wrapper caused a double
  coroutine resume and double `G.CURRENT_CALC_TIME` accumulation — removed by
  `patch_main_lua.py` step 10.**
  `talisman.lua:690` wraps vanilla `love.update` and handles all three coroutine
  states (dead, aborted, live) including the `coroutine.resume` at `talisman.lua:766`
  and the scoring overlay. The former `main.lua:2109` wrapper called `oldupd`
  (talisman's wrapper) first, then ran an identical `G.SCORING_COROUTINE` block —
  resulting in two `coroutine.resume` calls per frame (~60 ms Lua execution instead
  of ~30 ms) and `G.CURRENT_CALC_TIME` incrementing twice per frame (doubling the
  displayed elapsed time). `patch_main_lua.py` step 10 removes the entire duplicate
  `F_NO_COROUTINE` block from main.lua (evaluate_play coroutine wrapper, tal_abort,
  love.update driver, Card:calculate_joker, Card:use_consumable, and the
  calculating_score guard) on every build. All coroutine driving logic lives
  exclusively in `talisman.lua` (`talisman.lua:692`).

### Mobile notes

- The 30 ms yield threshold (`TIME_BETWEEN_SCORING_FRAMES = 0.03`) exists
  specifically for Android frame pacing; desktop builds typically complete scoring
  in a single frame.
- `G.SCORING_COROUTINE` guards at `state_events.lua` lines 423, 585, 663, 674
  prevent re-entrant scoring operations while the coroutine is active.
- The 0.3 s overlay check **no longer exists**. It was in the duplicate main.lua
  `love.update` wrapper (at what was `main.lua:2134`) that `patch_main_lua.py`
  removes. `G.SCORING_START` (which it read) is also gone — the only write site
  was in the removed duplicate coroutine wrapper.

### Patch touchpoints

| Patch | File | What it does |
|---|---|---|
| Talisman big-number injection | `talisman.lua:524` | Redefines `to_big`; active version overwrites `main.lua:1942` |
| Build coroutine augmentation | `main.lua` (~2025) | Minimal `G.FUNCS.evaluate_play` wrapper that stamps `G.SCORING_START = love.timer.getTime()` then calls through to talisman's version. `G.SCORING_START` is load-bearing for talisman's 0.3-second scoring-progress overlay (`talisman.lua:734`). All other coroutine machinery (coroutine.create/resume, love.update driver, Card:calculate_joker yield, CARD_CALC_COUNTS, LAST_SCORING_YIELD) lives solely in talisman.lua:673-820. Verbatim copies of the full talisman.lua:569-979 block were removed to fix double-wrapping bugs: `love.update` re-wrap (double coroutine.resume, double G.CURRENT_CALC_TIME); `Card:calculate_joker` re-wrap (double G.CARD_CALC_COUNTS increment); `card_eval_status_text`/`juice_card`/`Card:juice_up` re-wraps (double animations); `G.FUNCS.evaluate_round` re-wrap (double round-eval, duplicate summary rows); `Card:use_consumable`, `G.FUNCS.evaluate_play` calculating_score guard, `tal_uht`, `Game:start_run`, `Card:start_materialize/start_dissolve/set_seal` re-wraps (harmless but redundant). `SMODS.calculate_individual_effect` re-wrap retained (Cryptid-specific e_chips/hyper_chips handling not in talisman; early-return guard prevents double-firing). |
| Cryptid Ascension hook | `overrides.lua:2275-2276` | Wraps `SMODS.calculate_round_score` to call `Cryptid.ascend` |
| Cryptid trophy-cap | `overrides.lua:1398-1399` | Caps mult via `mod_mult` wrapper |
| Reserve shim | `patches/reserve-shim/` | Provides `G.FUNCS.can_reserve_card` / `reserve_card` (needed by Sticky Fingers joker; extracted from Pokermon) |

### Unknowns

- The exact interaction order between the five `calculate_individual_effect`
  wrappers when multiple Cryptid exotic jokers are simultaneously active has
  not been audited.
- Whether `sunnumber.not_modest` / `sunnumber.modest` are ever non-zero in a
  standard Cryptid run (i.e., whether the base factors `1.25` / `0.25` are the
  only values in practice).

## 9. Rendering & shaders


### Mali-G710 (Tensor G2) shader constraints — session-verified

- LÖVE fragment shaders run **fp16 mediump** by default on Mali; locals max at
  ~65504. Chaotic math (pow^3/^5, ÷~0, `tan` asymptotes, unbounded `time`)
  produces inf/NaN → `Texel` returns black → black cards.
- Global `precision highp float;` **crashes** ("overloaded functions must have
  the same parameter precision qualifiers") — LÖVE's Texel/sampler built-ins
  use default precision and Mali rejects the mix. Fix = per-variable `highp` on
  the math chain only.
- NaN comparisons are false in GLSL — guards must be written
  `(abs(x) < limit) ? x : fallback`.
- A local named `mod` (glitched_b.fs declares `float mod`) shadows the built-in
  `mod()` — calling it is a compile error → boot crash-loop.
- glslang compile-checks catch syntax/shadowing but NOT Mali's precision
  strictness; only on-device truth is truth.
- The menu "glitched" joker uses `glitched_b.fs`, not `glitched.fs`.

## 10. UI system: layout engine, screens, callbacks

The UI is a retained-mode tree of `UIElement` nodes managed by `UIBox` root
containers. Screen layout is declarative: callers build a Lua table of nested
`{n=G.UIT.X, config={...}, nodes={...}}` entries and pass it to `UIBox{definition=...}`.
The engine walks that table at construction time, creates one `UIElement` per
entry, computes sizes and positions, and thereafter updates the tree every frame
via `UIElement:update`.

### Classes

**`UIBox`** (`Moveable:extend()`, `engine/ui.lua:4`) — the root container. Owns
the `UIRoot` `UIElement` and anchors the whole tree to the game world. It is a
Moveable with `wh_bond='Weak'` and `scale_bond='Weak'`, so its VT eases toward
T independently of its parent.

**`UIElement`** (`Moveable:extend()`, `engine/ui.lua:354`) — a single node in
the layout tree. Every `{n=..., config=...}` entry in a definition table becomes
one `UIElement`. UIElements are Minor Moveables welded to the UIBox (not to their
parent UIElement), with offsets computed by the layout pass.

### UIT node types (`G.UIT`, defined in `globals.lua:489`)

| Type | Value | Purpose |
|---|---|---|
| `G.UIT.T` | 1 | Text leaf. Measures itself from the font; renders a `love.graphics.Text` drawable |
| `G.UIT.B` | 2 | Box leaf. Fixed-size rectangle, can be rounded (`r=`) and coloured |
| `G.UIT.C` | 3 | Column container. Stacks children vertically |
| `G.UIT.R` | 4 | Row container. Stacks children horizontally |
| `G.UIT.O` | 5 | Object leaf. Embeds any `Node` subclass; the embedded object gets a Minor role on the UIElement |
| `G.UIT.ROOT` | 7 | Root container (one per UIBox; always the `UIRoot`) |
| `G.UIT.S` | 8 | Slider |
| `G.UIT.I` | 9 | Text input box |

Containers (`C`, `R`, `ROOT`) recurse into `nodes={...}`. Leaves (`T`, `B`, `O`)
are terminal.

### Construction sequence (inside `UIBox:init`)

1. `set_parent_child(definition, nil)` — walks the definition table recursively,
   calling `UIElement(parent, self, node.n, node.config)` for each entry. Sets
   `self.UIRoot` to the root element. Propagates `button`, `group`, and `mid`
   config keys downward.

2. `calculate_xywh(UIRoot, self.T)` — bottom-up size pass. Leaf nodes measure
   themselves (text from font metrics, boxes from `config.w`/`config.h`, objects
   from `config.object.T.w/h`). Container nodes sum their children plus padding.
   Result: every node has a correct `T.x/y/w/h`.

3. `UIRoot:set_wh()` — propagates any container size fixups.

4. `UIRoot:set_alignments()` — top-down alignment pass. Applies `align` strings
   (`c`=center-vertical, `m`=center-horizontal, `b`=bottom, `r`=right) to offset
   children within their containers.

5. `align_to_major()` + hard-set `VT` — positions the UIBox in the world
   relative to its `config.major` anchor.

6. `UIRoot:initialize_VT(true)` — sets all UIElement VTs to match their T
   (instant, no easing on first frame).

### Per-frame update

`UIElement:update(dt)` runs every frame for every UIElement (called from the
`G.MOVEABLES` move pass via `Moveable:update`):

- **`func` callbacks**: if `config.func` is set, `G.FUNCS[config.func](self)` is
  called every frame. These are visibility/enable gate functions (e.g.
  `can_buy`, `HUD_blind_visible`). A `FUNC_TRACKER` table counts calls per func
  per frame to catch runaway polling.
- **Text update**: `T` nodes with `config.ref_table`/`config.ref_value` poll for
  changes and update the `Text` drawable; triggers `UIBox:recalculate()` if the
  string length changes.
- **Object update**: `O` nodes with `config.ref_table`/`config.ref_value` swap
  the embedded object if the referenced value changes, then recalculate.

`UIBox:recalculate()` re-runs `calculate_xywh` + `set_wh` + `set_alignments` on
the live tree to re-flow the layout without rebuilding UIElements.

### Click callbacks

When a `UIElement` with `config.button` is clicked, `UIElement:click()` calls
`G.FUNCS[config.button](self)` (`ui.lua:1008`). All interactive callbacks are
plain Lua functions stored in `G.FUNCS`. The element itself is passed as the
argument `e`; callers reach game state through `G.*` globals, not through `e`.

A 0.1 s debounce (`last_clicked`) prevents double-fire. `config.one_press` sets
`disable_button = true` after the first click to prevent re-use.

`config.choice` + `config.group` implement radio-button groups: on click, all
siblings sharing `config.group` have their `chosen` cleared, and this element's
`chosen` is set.

### Named screens and UIBox registry

All UIBoxes are inserted into `G.I.UIBOX` unless `config.instance_type` redirects
them to another registry (e.g. `POPUP`). Commonly accessed UIBoxes are stored on
`G` directly:

| Global | Created in | Definition function |
|---|---|---|
| `G.HUD` | `Game:start_run` | `create_UIBox_HUD()` |
| `G.HUD_blind` | `Game:start_run` | `create_UIBox_HUD_blind()` |
| `G.MAIN_MENU_UI` | `Game:start_up` | menu definition |
| `G.OVERLAY_MENU` | Various state entries | varies per state |
| `G.shop` | shop state | shop definition |
| `G.blind_select` | blind-select state | `create_UIBox_blind_select()` |
| `G.round_eval` | round-eval state | `create_UIBox_round_evaluation()` |
| `G.booster_pack` | pack-open states | various pack definitions |

`G.HUD:get_UIE_by_ID('id')` is the standard way to reach a specific element after
construction (e.g. `G.HUD:get_UIE_by_ID('hand_chips')` for the chip display).

### Hover and stop_hover on UIElement

`UIElement:hover()` and `UIElement:stop_hover()` delegate to `Node`'s
implementations (create/remove `children.h_popup`). The popup is a UIBox
constructed from `config.h_popup` with its `instance_type = 'POPUP'`, so it
registers in `G.I.POPUP` rather than `G.I.UIBOX`.

UIElement collision is guarded: `collides_with_point` returns false if
`self.UIBox.states.collide.can` is false (line 988), so disabling collision on
the UIBox disables all its children at once.

### Gotchas

- **`func` callbacks run every frame for every UIElement that has one.** There is
  no dirty-flag mechanism. Expensive operations in `G.FUNCS.*` gate functions
  directly slow the UI pass. `G.ARGS.FUNC_TRACKER` records call counts but
  doesn't throttle them.

- **`UIBox:recalculate()` is relatively cheap** (re-runs the layout math on the
  existing UIElement tree) but does not handle changes in tree structure. Adding
  or removing nodes requires removing the UIBox and constructing a new one.

- **`O` nodes embed a Node by reference.** The embedded object's `T` is used for
  sizing at construction time. If the object's size changes after construction,
  the UIBox will not automatically resize unless `recalculate()` is triggered.

- **Text drawables (`love.graphics.newText`) are cached per UIElement** and
  mutated in-place via `setText` when `ref_value` changes. Creating them is
  expensive; the cache avoids per-frame allocation.

- **UIBox draw is guarded by `FRAME.DRAW`** (`ui.lua:290`), so each UIBox draws
  at most once per frame even if `draw()` is called multiple times (e.g. from
  both the normal draw pass and a child's draw cascade). Exception: when
  `G.OVERLAY_TUTORIAL` is set, the guard is bypassed.

## 11. Input: controller & the touch layer

`G.CONTROLLER` is a `Controller = Object:extend()` instance (`engine/controller.lua`).
It is updated once per frame from `Game:update` (outside the frame-budget gate —
it always runs). It handles mouse, touch, keyboard, and gamepad under a unified
cursor model; the game code only ever sees `hovering.target`, `clicked.target`,
etc., not raw input events.

### HID flag system

Every input event sets `G.CONTROLLER.HID.*` flags via `set_HID_flags(type)`:

| Flag | Set when |
|---|---|
| `HID.mouse` | `love.mousepressed/moved` with non-touch source |
| `HID.touch` | `love.mousepressed` with `touch=true`; `love.mousemoved` within 200 ms of a touch (`last_touch_time`) |
| `HID.controller` | gamepad axis or dpad button input |
| `HID.pointer` | any of mouse / touch / axis_cursor |

On Android, LÖVE maps finger events to `love.mousepressed/mousereleased/mousemoved`
with `istouch=true`. There are no separate `love.touchpressed` handlers in the
game logic. `HID.touch` flips whenever `mousemoved` fires within 200 ms of a
touch timestamp (`last_touch_time`), so the flag stays `true` for the duration
of a drag.

### Input event flow (per frame, inside Controller:update)

The numbered steps below map to code in `controller.lua:191–496`:

1. **Lock evaluation** — `self.locked` aggregates all `self.locks.*` booleans.
   `locks.wipe` is set during screen transitions; `locks.frame` is set by
   `locks.frame_set` and cleared after 0.1 s via an E_MANAGER event.

2. **Axis update** — `update_axis(dt)` reads gamepad thumbsticks and synthesises
   directional button presses into `pressed_buttons`.

3. **Key/button dispatch** — pressed, held, and released keys/buttons are
   dispatched to `key_press_update`, `key_hold_update`, `key_release_update` (and
   button equivalents). Lists are cleared after dispatch.

4. **Collision pass** — `get_cursor_collision(G.CURSOR.T)` clears `collision_list`,
   then iterates `G.DRAW_HASH` in reverse-draw order. Each node that passes
   `collides_with_point` and has `states.collide.can` is added to
   `collision_list` and its `states.collide.is` is set true. The draw hash is
   therefore the visibility-sorted collision list — only drawn nodes are
   hittable.

5. **Focus update** — `update_focus()` (gamepad only; early-returns immediately
   when `HID.controller` is false): manages `self.focused.target` for dpad/axis
   navigation. On Android touch, `HID.controller` is never set, so this function
   is a no-op for the entire session unless a physical gamepad is connected.
   Does not touch `hovering.target`.

6. **Hover target** — `set_cursor_hover()` walks `collision_list` top-to-bottom
   and picks the first node with `states.hover.can` (and not dragging, unless
   `HID.touch`). Result stored in `cursor_hover.target`.

7. **Queued press** — if `L_cursor_queue` is set (populated by
   `queue_L_cursor_press` from `love.mousepressed`), `L_cursor_press` runs now.
   This one-frame delay ensures the collision pass above has already run for the
   current cursor position.

8. **`cursor_down` handling** — when `cursor_down.handled` is false (set by
   `L_cursor_press`): enables drag on the target, sets `dragging.target`.
   On touch with no collision and `enable_drag_select`, activates
   `dragSelectActive` (slide-to-select mode).

9. **`cursor_up` handling** — when `cursor_up.handled` is false (set by
   `L_cursor_release`): stops drag, then decides between click (travel <
   `G.MIN_CLICK_DIST` and within `click_timeout`) or release_on (dropped on
   another node). On touch, also evaluates `s_tap` (short tap with distance <
   `MIN_CLICK_DIST` and duration < 0.2 s) and the `TAP_DESC_HOLD_NODRAG` guard
   (long hold with travel < `MIN_CLICK_DIST` is not treated as a drag release).

10. **Drag-select loop** — on touch with `dragSelectActive.active`, the closest
    hand card to the cursor is highlighted or dehighlighted each frame
    (TAP_DESC_HOLD_NODRAG / DRAG_SELECT_LOOP).

11. **Hover block (TAP_DESC_HOLDGATE)** — on touch, `hovering.target` is only
    set while `is_cursor_down` is true and cursor duration ≥ 0.2 s (suppresses
    hover popup from accidental short-touch on hand cards). On mouse, hover
    applies unconditionally when `cursor_hover.target` exists.

12. **TAP_DESC_RELAX** — on touch, immediately after the hover block: if
    `hovering.target` is set but the finger is no longer over that card (i.e.
    `is_cursor_down` is false or `cursor_hover.target` differs), `states.hover.is`
    is cleared on the old target. This allows the description popup
    (`hovering.target`) to persist without keeping the 3D tilt shader active on
    the old card.

13. **Dispatch** — in order:
    - `clicked.target:click()` if `clicked.handled` is false
    - `process_registry()` (button registry clicks)
    - drag creation (`create_drag_target_from_card` after 0.1 s hold)
    - `dragging.target:drag()` if dragging
    - `released_on.target:release(dragging.prev_target)` if released on another node
    - `hovering.target:hover()` (creates `h_popup`) if newly hovered; on touch this
      is deferred by `G.MIN_HOVER_TIME` (0.1 s) via an E_MANAGER event
    - `hovering.prev_target:stop_hover()` if hover changed

### Key targets

| Field | Type | Lifetime |
|---|---|---|
| `hovering.target` | Node | Lives until `cursor_hover.target == nil` clears it (TAP_DESC_PERSIST), or until hover changes, or TAP_DESC_RELAX clears `states.hover.is` |
| `clicked.target` | Node | Set on press-release within distance/time threshold; cleared after `click()` dispatched |
| `dragging.target` | Node | Set on `cursor_down` with `states.drag.can`; cleared on `cursor_up` |
| `released_on.target` | Node | Set when drag is released onto a different node |
| `touch_control.s_tap` | `{target, time, handled}` | Set on short touch-tap; `handled` is flipped false but is never consumed outside controller (the flag is informational for any code that polls it) |

### The hover/tilt split (TAP_DESC bug root cause)

Two independent flags control two independent systems:
- `hovering.target` — drives the description popup (`h_popup`); lives on
  `G.CONTROLLER`
- `card.states.hover.is` — drives the 3D mesh tilt anchor (`tilt_var.mx/my` in
  `Card:draw` / SMODS DrawStep `'card_tilt'`); lives on the Card itself

On desktop, both flags are set and cleared together. On touch, TAP_DESC_RELAX
(controller.lua:448) clears `states.hover.is` once the finger is off the card
while leaving `hovering.target` pointing to the card. This is the fix: the popup
persists, but the tilt falls through to the ambient branch (sinusoidal idle),
eliminating the warp that occurred when `states.hover.is` remained true and
`tilt_var.mx/my` tracked the moving finger.

### Locks

| Lock key | Set by | Effect |
|---|---|---|
| `locks.wipe` | `G.screenwipe` being truthy | Full input block |
| `locks.frame` | `locks.frame_set` (cleared after 0.1 s event) | Blocks queued presses and hover |
| `locks.frame_set` | Various state transitions | Triggers `locks.frame` with deferred clear |

### Gotchas

- **`L_cursor_press` is queued, not immediate.** `love.mousepressed` calls
  `queue_L_cursor_press`, which sets `L_cursor_queue`. The actual press fires
  next frame inside `Controller:update`, after the collision pass for that
  frame has run. This means a press always lands on the collision list from
  the same frame, never the previous one.

- **`touch_control.s_tap.handled` is set false but never consumed** in the
  base-game code. It is available for any code that polls
  `G.CONTROLLER.touch_control.s_tap`, but the base click dispatch uses
  `clicked.target:click()` — the `s_tap` field is not part of the primary
  dispatch path.

- **`G.DRAW_HASH` is rebuilt every frame** via `reset_drawhash()` (called from
  the game's draw pass in `misc_functions.lua:697`) and `add_to_drawhash(obj)`
  (called from `Node:draw`). Collision is only possible on nodes that were drawn
  in the previous frame.

- **On touch, `cursor_hover.target` is resolved from `collision_list` during
  `set_cursor_hover`, but `L_cursor_press` sets `cursor_down.target` from
  `cursor_hover.target` (line 1179)**, not from `hovering.target`. This means
  the press target is the topmost hoverable node at press time, regardless of
  whether a description popup is showing.

- **The `dragSelectActive` slide-to-select feature** (TAP_DESC_HOLD_NODRAG) only
  activates when the finger goes down with zero collision (`#collision_list == 0`)
  and `G.SETTINGS.enable_drag_select` is true. If a hand card is already under
  the finger, normal press/drag logic applies.

## 12. Persistence: saves, settings, profiles

Three orthogonal save scopes, all handled by a dedicated background thread.

### Save thread (`engine/save_manager.lua`)

Spawned at boot (`game.lua:115`) as a LÖVE thread on channel `save_request`.
Runs a `while true / CHANNEL:demand()` loop — blocks until a request arrives,
writes synchronously, then loops. All I/O is off the game thread.

The game loop flushes at most once per `G.F_SAVE_TIMER` seconds (`globals.lua:42`;
30s on Android, 5s on macOS/Windows). Earlier flush is triggered by stage
change, pause toggle, or `G.FILE_HANDLER.force`. `G.FILE_HANDLER` is a flag
table: `.run`, `.progress`, `.settings`, `.metrics`, `.update_queued`. Setting
any flag + `update_queued = true` queues a flush; `game.lua:2892–2936` drains
in priority order (metrics → progress/settings → run) then clears all flags.

### Serialisation (`engine/string_packer.lua`)

- **`STR_PACK(data)`** — recursive table→Lua-string serialiser. Output is
  directly `loadstring`-able (`return { [key]=value, ... }`). OmegaNum/BigNumber
  tables (`v.m`+`v.e` or `v.array`+`v.sign`) serialise as `to_big(...)` calls;
  live `Object` instances become `"MANUAL_REPLACE"` (excluded intentionally).
- **`STR_UNPACK(str)`** — `assert(loadstring(str))()`. No sandboxing.
- **`compress_and_save(_file, _data)`** — `STR_PACK` → `love.data.compress
  ('deflate', level=1)` → `love.filesystem.write`.
- **`get_compressed(_file)`** — reads file; if first 6 bytes != `return`,
  decompresses via `love.data.decompress('deflate')`; returns raw Lua string.

All `.jkr` files are binary on disk — not grep-able.

### Save scopes

| File | Content | Written by |
|---|---|---|
| `<profile>/save.jkr` | Full run state: all CardAreas, tags, G.GAME, STATE, BLIND, BACK, VERSION | `save_run()` → `tal_compress_and_save` (Talisman adds bignum guard header) |
| `<profile>/profile.jkr` | Career stats, high scores, unlocks/discoveries/alerts | `save_progress` / `save_settings` request |
| `settings.jkr` | `G.SETTINGS` table (language, graphics, GAMESPEED, etc.) | same requests as profile |
| `<profile>/meta.jkr` | `{unlocked, discovered, alerted}` key tables | appended incrementally on new unlock/discovery |

Profile directory = `G.SETTINGS.profile` string (e.g. `"cry_profile_1"`,
set by Cryptid's prefix logic at `game.lua:164`).

### `save_run()` (`misc_functions.lua:1632`)

Collects all `CardArea` instances from `G` by duck-typing `v:is(CardArea)`,
all `Tag` instances from `G.GAME.tags`, calls `recursive_table_cull` to strip
live objects. Sets `G.FILE_HANDLER.run = true` + `update_queued = true`.
Returns early during pack-open states. Does NOT write immediately.

Talisman's `tal_compress_and_save` prepends:
`"if not OmegaMeta then <minimal fallback> end <real save>"` so a save loaded
without Talisman won't crash.

### `remove_save()` (`misc_functions.lua:1670`)

`love.filesystem.remove(<profile>/save.jkr)` + `G.FILE_HANDLER.run = nil`.
Called on loss (`update_game_over`) and win (`win_game`).

### Gotchas

- `G.F_NO_SAVING = true` suppresses all saves (sandbox/Cryptid debug).
- The save thread calls `jit.off()` on ARM64 macOS inside the thread, not the
  main thread.
- `save_run` guards against pack-open states but NOT against
  `G.SCORING_COROUTINE` — saves can fire mid-scoring. The saved `G.STATE` is
  `HAND_PLAYED`; a resumed run re-enters `update_hand_played` cleanly.
- Deleting `settings.jkr` regenerates defaults from `globals.lua` and does NOT
  touch run or profile saves.

---

## 13. RNG & determinism

All gameplay randomness flows through a single deterministic chain seeded at
run start — never `math.random()` directly.

### Seed initialisation (`game.lua:2268`)

```lua
G.GAME.pseudorandom.seed =
    args.seed          -- explicit seed (seeded/challenge run)
    or "TUTORIAL"      -- if tutorial not yet complete
    or generate_starting_seed()   -- normal: cursor-position entropy
```

Two derived values computed immediately after:

```lua
-- Bootstrap any per-key state still == 0:
G.GAME.pseudorandom[k] = pseudohash(k .. G.GAME.pseudorandom.seed)
-- Global additive offset used in every pseudoseed call:
G.GAME.pseudorandom.hashed_seed = pseudohash(G.GAME.pseudorandom.seed)
```

### `pseudohash(str)` (`misc_functions.lua:314`)

Deterministic string → float in `[0, 1)`. Iterates characters right-to-left:
`num = ((1.1239285023 / num) * byte * π + π * i) % 1`. Pure function; no
mutable state.

### `pseudoseed(key, predict_seed)` (`misc_functions.lua:333`)

1. If `predict_seed` given: one-shot from `pseudohash(key..predict_seed)`,
   no state mutation (lookahead/preview use).
2. If `G.SETTINGS.paused` (and key != `'to_do'`): return `math.random()` —
   paused interactions use system RNG, don't consume run seed.
3. Otherwise: lazily init `G.GAME.pseudorandom[key]` via `pseudohash`, then
   advance: `state[key] = abs((2.134453429141 + state[key]*1.72431234) % 1)`.
   Return `(state[key] + hashed_seed) / 2`.

Each key has its own independent counter; keys never interfere.

### `pseudorandom(seed, min, max)` (`misc_functions.lua:351`)

Converts key-string to float via `pseudoseed`, calls `math.randomseed(float)`
+ `math.random([min, max])`. Each call re-seeds Lua's global RNG. Call sites
that need reproducibility always pass a named key.

### `pseudoshuffle(list, seed)` (`misc_functions.lua:208`)

Fisher-Yates after `math.randomseed(pseudoseed(seed))`. Pre-sorts by `sort_id`
if present for stable starting order.

### `generate_starting_seed()` (`misc_functions.lua:222`)

Mixes cursor `T.x`, `T.y`, hover time with fixed multipliers → 8-char
alphanumeric. On gold-stake runs with both legendary categories present, loops
until the seed gives a non-golden legendary as first shop legendary.

### Gotchas

- The seed is a **string**; per-key states are floats. Saving/loading restores
  all of `G.GAME.pseudorandom` so the sequence resumes exactly.
- The literal key `'seed'` always returns `math.random()`, bypassing the
  deterministic chain. Used for non-gameplay randomness (visual shuffles).
- Talisman's bignum tables are deterministic score transforms — they don't
  touch the RNG chain.

---

## 14. Content objects: Blinds, Tags, Backs, Challenges

Runtime wrappers around static prototypes in `G.P_BLINDS`, `G.P_TAGS`,
`G.P_CENTERS`, `G.CHALLENGES`.

### Blind (`blind.lua`)

Single live instance at `G.GAME.blind`. Re-initialised each round by
`Blind:set_blind`. Plain `Object:extend()` — not a `CardArea`.

| Method | Line | Role |
|---|---|---|
| `Blind:set_blind(blind, reset, silent)` | 85 | Loads prototype; sets chips/name/dollars/boss; fires SMODS `set_blind` |
| `Blind:defeat(silent)` | 291 | Animates defeat; sets `blind_states[current]='Defeated'` |
| `Blind:debuff_hand(cards, hands, text)` | — | Returns true if hand type is forbidden (drives `evaluate_play` branch) |
| `Blind:modify_hand(cards, hands, text)` | — | Mutates mult/chips for boss effects (called in `evaluate_play_main`) |
| `Blind:press_play()` | 505 | Hook on play-button press; some bosses trigger here |
| `Blind:save()` | — | Minimal table for serialisation |

`G.GAME.blind.in_blind` — "inside a round" flag: true after `set_blind`, false
at start of `end_round`.

### Tag (`tag.lua`)

Reward objects at `G.GAME.tags` (array). Each wraps a `G.P_TAGS[key]` prototype.

- `Tag:init` (line 7): copies config, sets `self.tally` from `G.GAME.tag_tally`
  (monotonically incrementing), calls `set_ability`.
- `Tag:apply_to_run(_context)` (line 135): called from `update_draw_to_hand`
  (`round_start_bonus`), `update_blind_select` (`new_blind_choice`), `select_blind`
  (immediate tags). Removes itself on consumption.
- `Tag:save()` (line 518): `{key, config, tally, ability}`.

### Back (`back.lua`)

Single live instance at `G.GAME.selected_back`. Wraps chosen deck prototype from
`G.P_CENTERS` (`b_red`, `b_blue`, etc.).

- `Back:init` (line 7): copies atlas, name, pos, effect config.
- `Back:apply_to_run()` (line 208): called once in `Game:start_run`; applies
  passive deck modifiers to starting state. Does NOT fire SMODS context.
- `Back:trigger_effect(context)`: scoring-pipeline hook for per-hand deck effects.
- `Back:save()`: `{center_key}`.

### Challenges (`challenges.lua`)

`G.CHALLENGES` is a plain array of static config tables (not class instances):
`{name, id, jokers, consumeables, vouchers, deck, restrictions, rules}`. Read-only
data consumed by `Game:start_run`. Extended by SMODS via `SMODS.Challenge`.

---

## 15. The mods layer

Four mods ship in this build. Load order matters — each can patch functions
defined by earlier mods.

### Load order

1. **SMODS (Steamodded)** — first. Provides `SMODS.calculate_context`,
   `SMODS.Joker`, the `SMODS.Mods` registry, the `Lovely` patch stub.
2. **Cryptid** — second. Extends content (jokers, blinds, decks, Code cards)
   and monkey-patches scoring for Cryptid-specific hooks.
3. **Talisman** — third. Wraps `G.FUNCS.evaluate_play` (and
   `Card:calculate_joker`) into a Lua coroutine; replaces score display with
   bignum-aware rendering; adds `Talisman.dollars`.
4. **Sticky Fingers** — last. Adds drag-to-use gestures (`s_tap`, `l_press`
   handlers in the dump); capability predicates are the 7 `sticky_can_*`
   wrappers in `misc_functions.lua:2714–2756`.

### SMODS

- `SMODS.calculate_context(ctx)` — fan-out to every registered effect handler
  for the given context key (`before`, `after`, `end_of_round`, `joker_main`,
  `debuffed_hand`, etc.).
- `SMODS.calculate_main_scoring(context)` — iterates card areas, scores each
  card.
- `SMODS.Scoring_Parameters` — named scoring scalars with defaults; reset in
  `evaluate_play_after`.
- `SMODS.Mods['<key>'].can_load` — true if mod loaded. Talisman-active guard:
  `if not (SMODS.Mods['Talisman'] or {}).can_load`.

### Talisman

- Default: `break_infinity = "omeganum"` (`talisman.lua:43`). Loaded from
  `talisman_path/big-num/omeganum.lua`. `bignumber` backend deprecated (config
  migration forces omeganum).
- `G.E_SWITCH_POINT` (default 1e11, notation-dependent): below this scores
  render as plain integers; at or above, OmegaNum notation.
- **Scoring coroutine**: `G.FUNCS.evaluate_play` wrapped at `main.lua:2094` to
  spawn `G.SCORING_COROUTINE`. Yields every 30ms (`TIME_BETWEEN_SCORING_FRAMES`,
  `main.lua:2195`) inside `Card:calculate_joker`. `Game:update` resumes each
  frame. Four spin-guards in `state_events.lua` (lines 423, 585, 663, 674)
  block downstream events until coroutine finishes.
- **Abort** (`G.FUNCS.tal_abort`, `main.lua:2105`): sets `tal_aborted = true`;
  `Game:update` skips resume, clears `SCORING_COROUTINE`, and if
  `scoring_state == 'main'` calls `evaluate_play_final_scoring` directly using
  the coroutine's Lua upvalue globals.
- **Dollars sync**: `evaluate_round` sets both `Talisman.dollars` and
  `G.GAME.current_round.dollars` to the same total (`state_events.lua:1221`).
  `cash_out` reads `G.GAME.current_round.dollars` (`button_callbacks.lua:3192`).
- `Talisman.config_file.disable_anims` — suppresses `Card:start_materialize`,
  `Card:start_dissolve`, `Card:set_seal` during scoring. Key mobile perf knob.

### Cryptid

- `Cryptid.ascend(value)` — modifies base mult/chips at top of
  `evaluate_play_main` for Cryptid's extended hand types.
- `calculate_banana`, `calculate_perishable`, `calculate_abstract_break` —
  called from `end_round` after main scoring.
- `Cryptid.apply_ante_tax()` — called at ante-up in `end_round`.
- Code cards call `G.FUNCS.can_reserve_card` / `G.FUNCS.reserve_card` (normally
  Pokermon, absent here). `patches/reserve-shim/` provides stubs so Sticky
  Fingers' drag-to-use works without Pokermon.
- `G.GAME.USING_RUN` — Cryptid 'run' mode flag; full semantics untraced.

### Sticky Fingers capability predicates (`misc_functions.lua:2714`)

| Function | Purpose |
|---|---|
| `sticky_can_use_blind_card` | Use a card on the blind |
| `sticky_can_reserve_card` | Drag to joker slot (Pokermon) |
| `sticky_can_select_card` | Select a hand card |
| `sticky_can_buy` | Buy a shop item |
| `sticky_can_buy_and_use` | Buy + immediately use |
| `sticky_can_select_crazy_card` | Select in crazy-hand mode |
| `sticky_can_take_card` | Take a card from a pack |

Called from `G.FUNCS.can_*` wrappers the UI queries before showing button
states. Our `STICKY_GUARD` patch markers in `misc_functions.lua` protect these
from being called during scoring.

<!-- WORKFLOW SECTIONS END -->

## 16. Device ops & quirks (session-verified)

- **ADB over WiFi**: `10.1.70.155:33463`; package `systems.shorty.lmm`.
- **Screenshots**: `adb shell screencap -p /sdcard/x.png && adb pull` — never
  pipe `exec-out screencap`. The game surface screencaps **black** (SurfaceView);
  use `screenrecord` for real pixels, and beware H.264 partial-frame artifacts
  during fast brightness changes (they look like rendering bugs; one "seam"
  chase ended there).
- **Restart to load changes**: force-stop + single start; relaunching over a
  live instance triggers LÖVE's "Failed to initialize filesystem: already
  initialized" race.
- **Crash detection**: `adb logcat` for `FATAL|AndroidRuntime|Traceback`;
  process liveness via `pidof systems.shorty.lmm`.
- **Performance reality** (profiled, not guessed): 63–76 FPS in-run;
  `e_manager` ~0.2ms; thermal status 0 at idle. The one real frame-hitch was
  Talisman's dim overlay, not load.
- **Tensor G2 throttles** under texture_scaling=2 + CRT + bloom + shadows —
  hence the mobile graphics defaults.

## 17. Gotchas index

Cross-subsystem traps collected across the full architecture audit and debug
sessions. Grouped by the mistake category.

### State machine / event queue

- **Never set `G.STATE` without also setting `G.STATE_COMPLETE = false`.**
  Each `update_*` handler's one-shot block only fires when `STATE_COMPLETE ==
  false`; it immediately sets it to `true`. If you write `G.STATE` and forget
  the reset, the handler never runs — the game silently hangs in the new state.

- **`G.SCORING_COROUTINE` spin-guards must return `false`, not `nil`.**
  Event `func` returning `false` means "not done, retry next frame". Returning
  `nil` (implicit in Lua) is treated as truthy and marks the event complete.
  All four guards in `state_events.lua` (423, 585, 663, 674) correctly return
  `false`.

- **`end_round` is called from three places, not one.**
  `update_new_round` (game.lua:3496), `update_selecting_hand` when both hand
  and deck are empty (game.lua:3266), and `draw_from_deck_to_hand` short-
  circuits to `GAME_OVER` directly when `card_limit <= 0` — bypassing
  `end_round` entirely. Patching "end of round" logic in only one site will
  miss the other two.

- **`update_hand_played` uses `to_big` comparison, not plain `>=`.**
  `to_big(G.GAME.chips) >= to_big(G.GAME.blind.chips)` (game.lua:3440). Any
  patch that rewrites this check using plain Lua comparison will break on large
  chip counts (OmegaNum tables are not comparable with `>=`).

### Lovely / dump architecture

- **Lovely patches never run on Android.** The dump is pre-patched on macOS.
  Editing a mod's `lovely/*.toml` or `lovely/` lua file does nothing on device.
  Always edit `build/game/` dump files directly, then re-run `build.sh build`.

- **All patches re-apply from scratch on every build.** `build.sh` wipes
  `build/game/` and re-extracts from `Balatro.love` before applying patches.
  Any manual edit to `build/game/` is lost on next build. Changes must live in
  `src/dump/` (dump overlays) or in the build script's sed/patch section.

- **Crash tracebacks cite dump line numbers.** These are `build/game/` lines,
  which shift when the dump changes. After updating a mod and regenerating the
  dump, old saved line numbers in Ghost/docs are stale.

### Serialisation / persistence

- **`.jkr` files are deflate-compressed binary — not grep-able.** Don't try to
  `adb pull` and `cat` them. Use `STR_UNPACK(get_compressed(...))` from within
  the game, or decompress with `python3 -c "import zlib,sys; sys.stdout.buffer
  .write(zlib.decompress(sys.stdin.buffer.read(), -15))"`.

- **`STR_PACK` silently drops live `Object` instances** (replaces with
  `"MANUAL_REPLACE"`). If a save round-trip is losing data, check whether
  you're accidentally including a live object in the serialised table.

- **Talisman wraps save files with a bignum guard header.** A save written with
  OmegaNum can't be loaded without Talisman — the guard evaluates to the bare
  fallback table. This is intentional, not a bug.

### Scoring pipeline / coroutine

- **`evaluate_play_after` has two callers and one guard.** Talisman calls it at
  `main.lua:2072`; `evaluate_play_final_scoring` calls it at line 1020 when
  Talisman is NOT loaded. The guard `if not (SMODS.Mods['Talisman'] or {})
  .can_load` prevents double-calling. In this build Talisman IS loaded, so the
  `final_scoring` path never calls `after` directly.

- **`G.SCORING_START` is never reset to nil** — it just gets overwritten on the
  next hand. Do not use it as a "scoring is active" boolean; use
  `G.SCORING_COROUTINE ~= nil` instead.

- **The per-joker `other_joker` loop is O(jokers²).** For every card in every
  joker area it evaluates every other joker. With a large joker count this is
  where the coroutine yield budget matters most on mobile.

### Touch / input

- **`card.states.hover.is` and `hovering.target` are two independent flags**
  that happen to be set together on desktop but not on touch. `hover.is` drives
  the 3D card tilt (must be cleared when finger leaves); `hovering.target`
  drives the description popup (must persist after finger lifts). Conflating
  them caused the card-warp bug fixed in commit `afde8cc`.

- **`G.DRAW_HASH` is rebuilt every frame** — collision is only possible against
  nodes drawn in the *previous* frame. A node that skips a draw pass (e.g. set
  invisible mid-frame) won't be hittable until the next frame.

- **Drag-select only activates on finger-down with zero collision.** If a hand
  card is already under the finger at `touch_pressed`, normal press/drag logic
  takes over and `dragSelectActive` is never set. The feature only engages when
  touching empty table space.

### RNG

- **The literal key `'seed'` bypasses the deterministic chain** and returns
  `math.random()`. If you add a new `pseudorandom('seed', ...)` call, you're
  using system RNG, not the run seed. Use any other key string.

- **`pseudorandom` re-seeds Lua's global RNG.** Any `math.random()` call
  between two `pseudorandom` calls uses the state left by the last
  `pseudorandom`. This is the intended design but can produce surprising results
  if you insert a `math.random()` call without realising it consumes the seeded
  state.

### Performance (Android / Mali-G710)

- **The Talisman dim+overlay UIBox is rebuilt every frame it's shown** — not
  just once. The profiled frame-hitch was this construction cost, not scoring
  computation. If the overlay appears, it makes itself slower.

- **`collectgarbage('collect')` fires mid-frame** when `SCORING_COROUTINE` is
  active and heap > 1GB (`main.lua:2114`). This is intentional to prevent OOM
  on Android. Do not remove it; do not add a second GC call nearby.

- **SurfaceView screencaps black** on Android. `adb shell screencap -p
  /sdcard/x.png` captures the compositor, not the SurfaceView. Use
  `adb shell screenrecord` for real pixels.
