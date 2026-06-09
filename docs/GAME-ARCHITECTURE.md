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
