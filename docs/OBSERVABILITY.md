# Observability (OBS)

The one place to look when the Android build misbehaves. OBS is a single suite
built on `patches/android-telemetry.lua` (an *owned module* — copied verbatim
into the build). It consolidates the debug paths that used to compete: a
standalone `print`→logcat shim, ad-hoc Cryptid diagnostic files, and the
now-removed DebugPlus console/logger/profiler.

Everything reports through one API, into one set of sinks, behind one gate, and
the suite **announces itself** in its own log so you never have to guess whether
logging is on or where the file is.

## Sinks — where output goes

| Sink | Path / channel | Gate | Notes |
|---|---|---|---|
| **telemetry.log** | `files/save/game/telemetry.log` (rotates at 1 MB → `.1`) | Debug Logging | Canonical sink. Events + `PERF_SNAPSHOT`, flushed every 5 s and on crash/background. |
| **crash.log** | `files/save/game/crash.log` | **always on** | Every crash, even with logging off. A crash is never lost. |
| **logcat** | tag `SDL/APP`, lines prefixed `[TEL]` | Debug Logging | Live mirror via `print`. |
| **phone-home** | POST to the tailnet receiver | Phone Home Telemetry | `scripts/telemetry-home.py`. |

The **file sinks are canonical and reliable**; logcat is a convenience mirror.

## The gate — Settings → Game

- **Debug Logging** (`telemetry_log`) → telemetry.log + logcat.
- **Phone Home Telemetry** (`telemetry_home`) → network upload.
- **Debug HUD (FPS + perf)** (`perf_mode`) → on-screen FPS/perf overlay.

Default **OFF** (the APK is shareable). **Crashes are captured regardless of the gate.**

## Self-announcing

When logging turns on — at boot *or* toggled live (the gate is re-read every
frame; no restart) — the log opens with:

```
OBS_INIT     crashes=crash.log file=telemetry.log home=off level=on
OBS_REGISTRY n=2 components=cryptid-init:ok,cryptid-lib:ok
```

- **`OBS_INIT`** — confirms logging is on and names every sink path.
- **`OBS_REGISTRY`** — the component-health inventory. Each subsystem that called
  `OBS.register(name, status)` appears here; a degraded/inactive component
  announces itself instead of failing silently.

## API (in-build)

`patches/android-telemetry.lua` exposes the global `OBS`:

- `OBS.event(kind, data)` — emit an event. (`ATLOG` is the legacy alias used by
  the instrumentation injected into game files.)
- `OBS.register(name, status[, detail])` — a subsystem self-reports into the registry.
- `OBS.registry` — the live table (persists regardless of the gate, so the
  inventory is complete even for components that registered while logging was off).

Components report through this so the parts know about each other. Example: the
Cryptid lib-loader loads each file independently and reports
`OBS.register("cryptid-lib", "ok"|"degraded", "failed:<files>")` plus
`LIB_LOAD_FAIL` events — so one bad lib file on Android surfaces in the registry
instead of silently aborting the whole load.

## Event taxonomy (the ones you'll grep for)

- Lifecycle: `SESSION_START`, `OBS_INIT`, `OBS_REGISTRY`, `REGISTER`
- Crashes: `CRASH` (full trace + state; also → crash.log, always)
- Perf: `PERF_SNAPSHOT` (windowed fps / frame-time per state), `JIT_ABORT`, `JIT_TRACE`
- Loaders: `LIB_LOAD_FAIL`
- Gameplay / input: `STATE`, `RUN_START`, `PLAY_HAND`, `G_MPRESS`, `G_REL`, …

## Reading it from the host

```
just obs            # dashboard: registry + per-state fps + crash count (pulls first)
just obs pull       # pull telemetry.log (+.1) + crash.log → build/telemetry/
just obs crashes    # print every crash (crash.log is always-on)
just obs perf       # per-state fps / worst-frame summary
just obs watch      # live telemetry over logcat
just obs home       # run the phone-home receiver
```

## What was removed, and why

- **DebugPlus** — a keyboard-only console + logger + profiler, inert on the
  no-lovely Android build and overlapping this suite (its half-loaded state once
  caused a crash). Cut at the root: dropped from the pins and the dump
  **regenerated without it** (`nix/regen-dump.sh` + `nix/stage-mods.sh`), so
  `vendor/dump` is genuinely DebugPlus-free — not patched-over.
- **LOGCAT_PRINT** — a redundant standalone `print`→logcat shim (`LOVE` tag); its
  job is the telemetry logcat sink.
- **cry_libload.txt / ad-hoc `CRY_*` diagnostics** — folded into OBS events + the registry.

## Where it lives

- Core: `patches/android-telemetry.lua` (owned module → copied into the build).
- Gate UI: `apply_settings_debug_tab` in `scripts/build.sh`
  → `overlay/patches/06-settings_debug_tab.patch`.
- Host CLI: `obs` recipe in `justfile`.
- Local verification (headless, logging forced on): `test/telemetry-gate.sh`.
