# rebuild/ — Balatro native (Kotlin + Jetpack Compose)

The LÖVE build's **twin project**: a from-scratch reimplementation of Balatro
(plus Cryptid content) as a native Android app. The rewrite itself is the goal —
no Lua, no LÖVE, no `.love` asset pipeline. The original game is the acceptance
test: behavior is ported from the vanilla source (`../src/vanilla/`) and checked
against oracles, never approximated from screenshots.

**Honest status: aimed-at faithful, not achieved.** `docs/UI_AUDIT.md` is the
authoritative gap/crutch registry (vanilla taxonomy vs coverage); read it before
claiming or assuming fidelity anywhere.

Own Gradle project: `cd rebuild && ./gradlew test` / `assembleDebug`.

## Layout (`app/src/main/kotlin/systems/balatro/`)

- **`engine/`** — direct ports of vanilla engine primitives: `Moveable` spring
  dynamics, `Node`/Room scene graph, `CardArea` (`align_cards`), `EventManager`,
  `GameClock`. Cited line-by-line against `src/vanilla/engine/*.lua`.
- **`game/`** — the rules: `Score.kt` (the scoring engine, oracle-verified),
  `Hands`/`Levels`/`Deck`, `Blinds.kt` (boss selection = the `get_new_boss()`
  port), jokers as data + reducers (`JOKER_MANIFEST`).
- **`ui/`** — `UILayout.kt` is the `calculate_xywh` layout-engine port; screens
  render **extracted** vanilla `create_UIBox_*` trees (JSON in `app/src/main/assets/ui/`,
  produced by `../tools/uiref/`) rather than hand-built lookalikes. `RunScreen.kt`
  holds the live run state and the screen composables.
- **`save/`** — `RunSnapshot` (kotlinx-serialization JSON of the whole run),
  atomic `SaveIo` (stage + fsync + rename), `StatsStore`.
- **`audio/`** — SoundPool SFX + MediaPlayer music. **`bridge/`, `content/`** — glue.

## Verification (three independent oracles)

1. **Score oracle** — `../test/kt-oracle.sh` scores ~99 baseline hands with the
   real LÖVE build and requires the Kotlin `Score` to match exactly. Runs outside
   Gradle (`nix-shell -p kotlin`); note it is currently **disjoint** from the JUnit
   suite — a green `./gradlew test` says nothing about oracle parity.
2. **Layout oracle** — `../tools/uiref/` stands up Balatro's real `engine/ui.lua`
   and dumps every node's computed geometry; `verify_layout.py` compares the
   Kotlin layout node-for-node (HUD verified 80/80).
3. **JUnit** — `./gradlew test`: 50+ test classes with hand-derived expected
   values (worked arithmetic in comments), deliberately separated reducer-accrual
   vs oracle read-path coverage. See `../docs/TESTING.md`.

On-device checks run on the **emulator** (`emulator-5560`); the physical phone is
personal hardware — never deploy there unprompted.

## Docs

- `ENGINE_PORT_PLAN.md` / `ENGINE_PORT_P0.md` — the engine-port plan and its P0 slice.
- `docs/UI_AUDIT.md` — vanilla UI taxonomy vs rebuild coverage; the crutch teardown list.
- `../docs/REVIEW-2026-07-01.md` — full-project review findings and fix status.

## History

An earlier README here described an ECS/composition architecture; that design was
retired in favor of the direct 1:1 engine port above (it lives in git history).
