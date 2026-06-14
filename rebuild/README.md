# balatro-native — clean-slate rebuild

Burn-it-down rebuild. **Not** patched onto the LÖVE base. Native Kotlin + Jetpack
Compose, composition-not-inheritance core, content ported and verified byte-for-byte
against the original via score-oracle.

## Why a clean slate (settled)
The LÖVE build is inheritance-by-monkeypatch (5-deep `Game:update` wrappers, `eval_card`
override chains, sed-patches on a baked dump). Every framerate, crash, and UX problem
traced back to that. This rebuild removes the *category* of those problems instead of
patching instances.

## Architecture
- **Engine** (`engine/Ecs.kt`) — Entity = Int, Component = data, System = pure function
  in an ordered Pipeline. No inheritance, no overrides, no monkeypatch. Component stores
  are dense-packed (cache-coherent iteration). Order is explicit data; removal is one
  delete; no system owns the next call → the rewind-crash class is structurally gone.
- **Scoring** (`game/Scoring.kt`) — jokers **register** Effects (which contexts + a pure
  handler) into a subscription index; scoring a hand dispatches each context to only its
  subscribers, in board order, over **one reused `Context`** → no per-hand table churn
  (the old ~340-alloc/hand GC problem). `BigValue` (`game/BigValue.kt`) is the Talisman/
  OmegaNum seam.
- **Content** (`content/Jokers.kt`) — porting a joker = data + a `register`. State lives
  in a `JokerState` component (so save/load/rewind are data reads, never object-graph walks).
- **UI** (`ui/`, `bridge/`) — the live **board** renders in-engine (LOD: all jokers
  visible during play, cheap when small, full detail on the active/hovered one). All
  **chrome** — managing huge stacks, content library, settings, info — is native **Compose**
  (`ModalBottomSheet`, `LazyVerticalGrid`, native scroll/gesture) composited over the board.
  Game state ↔ Compose over a thin **bridge**.

## Correctness: the oracle is the spec
A joker is "ported" only when the new scoring equals the original LÖVE build's score on
the score-oracle baseline seeds. `game/Demo.kt` is the smallest instance of that check
(order-dependent cascade → exact number). The rebuild can never silently drift from the
game people know.

## Build (toolchain to stand up)
Android Kotlin + Compose via gradle (AGP), Android SDK already present in nix. The board
renderer (GL/Skia surface) and the Lua-free engine run native; the existing build's
`game.love` asset pipeline is retired, not reused — content comes through the oracle-verified
port, not the `.love`.

## Roadmap
1. **[done]** Composition core + scoring + first joker archetypes + the passing demo.
2. Toolchain: `nix` gradle/AGP/Kotlin/Compose; `gradlew assembleDebug` green; Compose
   `MainActivity` on device.
3. The board renderer (entities → draw commands → GL surface), LOD.
4. The bridge + first native modal: `LazyVerticalGrid` of the real joker list over the live board.
5. Content port in oracle-verified waves: the 10 representative archetypes first, then breadth.
6. Big-number: drop OmegaNum behind `BigValue`. Optional Rust core via JNI if profiling demands no-GC.
