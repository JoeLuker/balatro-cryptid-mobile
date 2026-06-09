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
| `Card:calculate_joker` (Talisman yield point) | `main.lua:2204` | Wraps vanilla `calculate_joker`: if more than `TIME_BETWEEN_SCORING_FRAMES` (0.03 s) has elapsed since `G.LAST_SCORING_YIELD` and inside a coroutine, yields back to the frame loop. This is the only `coroutine.yield()` site. |
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

*(pending workflow merge)*

## 6. Frame anatomy

*(pending workflow merge)*

## 7. Object model: Object → Node → Moveable → Card/CardArea

*(pending workflow merge)*

## 8. Round flow & the scoring pipeline

*(pending workflow merge)*

## 9. Rendering & shaders

*(pending workflow merge — Mali constraints below are session-verified)*

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

*(pending workflow merge)*

## 11. Input: controller & the touch layer

*(pending workflow merge — session model: frame order is collision →
`set_cursor_hover` → queued press → cursor_down/up handling → hover block →
dispatch to click/drag/hover targets. Popup lifetime hangs off
`hovering.target`; mesh tilt hangs off `states.hover.is`; the warp bug lived in
that distinction.)*

## 12. Persistence: saves, settings, profiles

*(pending workflow merge — known: `settings.jkr` is compressed (not grep-able);
deleting it regenerates defaults from `globals.lua` and does NOT touch run/
profile saves.)*

## 13. RNG & determinism

*(pending workflow merge)*

## 14. Content objects: Blinds, Tags, Backs, Challenges

*(pending workflow merge)*

## 15. The mods layer

*(pending workflow merge — session-verified: Talisman switches scores to
OmegaNum heap tables above ~1e300 (`E_SWITCH_POINT`) and runs scoring in
`G.SCORING_COROUTINE` with a dim+Abort overlay; Cryptid's Code cards call
`G.FUNCS.can_reserve_card`/`reserve_card` which only Pokermon defines — our
reserve-shim provides them; sticky-fingers' touch layer lives in the dump
(`s_tap`/`l_press`), its 7 `sticky_can_*` wrappers in `misc_functions.lua`.)*

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

*(pending workflow merge — cross-subsystem gotchas collected from the audit)*
