# Session report — 2026-06-09

The complete record of one very long day: ~60 commits across two parallel
Claude sessions, four shipped feature/fix arcs, one production incident with
root-cause and prevention, a three-layer local test harness, a full
adversarially-verified architecture map, and a verified performance program.

Companion documents:
- `docs/GAME-ARCHITECTURE.md` — the 2,300-line verified map of the game code
- `docs/PERF-FINDINGS.md` — the ranked, status-aware optimization list
- `docs/perf-findings-raw-2026-06-09.txt` — all 43 raw findings with full
  measure/fix/risk detail, as harvested from the hunt agents

---

## 1. Touch UX arc (morning)

Goal: phone-native card interactions. All shipped as build.sh appliers
against `engine/controller.lua` (markers grep-able in `build/game/`).

| Fix | Marker | Root cause |
|---|---|---|
| Tap a card → description persists after lift | `TAP_DESC_PERSIST` | vanilla cleared hover on touch release |
| Tap same card / elsewhere dismisses | `TAP_DESC_TOGGLE` | — |
| Hand cards: quick tap selects, ≥0.2s hold shows description | `TAP_DESC_HOLDGATE` | description flashed during sub-tap window |
| Held description survives release | `TAP_DESC_HOLD_NODRAG` | stationary hold registered as degenerate reorder-drag; drag-release path destroyed the popup |
| **Card warp fix** | `TAP_DESC_RELAX` | the popup hangs off `hovering.target` but the 3D mesh tilt hangs off `states.hover.is` — persisted descriptions left `hover.is` true, so the card's tilt anchor (`tilt_var.mx/my` → `mouse_screen_pos` uniform) kept tracking the live finger. First fix attempt guarded `set_offset` — wrong knob (`amt`, not the anchor). |
| Slide-to-select | `DRAG_SELECT_*` | adapted from BalatroMobileLikeDragging's `dragSelectActive`; arms only on empty-space touch; first card sets select/deselect mode; toggle in Settings → Game |
| `cursor_down.uptime` stamped inside the lock guard | `CURSOR_DOWN_UPTIME_FIX` | sticky-fingers stamped it outside `L_cursor_press`, so locked presses left stale uptime → falsely short durations → broke the hold gate |

Verified model (deep audit, "reliable"): `l_press.target` is never written
anywhere — long-press-suppresses-tap is a dead stub. RELAX placement confirmed
safe (clears the flag, not the target; hover dispatch still fires correctly).

## 2. Build environment & dim-gate arc

- **shell.nix** (`bee7267`): the imperative Android SDK had been removed;
  the whole toolchain (build-tools 34.0.0 → zipalign/apksigner, apktool,
  openjdk17, adb, love, xvfb-run, luajit) is now pinned via nixpkgs.
  `nix-shell --run './scripts/build.sh build'` works from a clean machine.
- **Talisman dim-gate re-homing** (`7b87d70`): the patcher began removing
  main.lua's baked duplicate of Talisman's coroutine harness (a real bug — it
  double-resumed scoring every frame), which silently orphaned the 0.3s
  dim-overlay gate that fixed the 1-frame dark flash on every hand. The gate
  (`TALISMAN_DIM_GATE`) now patches the single live harness in
  `Mods/Talisman/talisman.lua` — in **both** shipped copies, because:
- **Mod-copy duality** (empirically established): every mod ships twice —
  embedded in game.love (patched by build_apk) and pushed to `files/save/Mods`
  (from prepare_transfer). The loader enumerates from the **embedded archive**
  (proof: stale save-dir Pokermon never loaded; embedded-only shader fixes
  work on device) but save-dir reads can shadow same-path files, so mod-file
  patches must hit both. Deploy pushes but never deletes — stale Pokermon was
  removed from the device by hand.

## 3. The local test harness ("stop using my phone as CI")

Three layers, `README.md` documents them; run in order before any deploy:

1. **`just test-controller`** (<1s): pure-LuaJIT rig driving the REAL built
   `engine/controller.lua` with scripted touch timelines. 10 tests covering
   the full gesture matrix. First run immediately taught the harness that
   real cards have `click_timeout = 0.3` (card.lua:60) — why a hold doesn't
   select on release.
2. **`just smoke`** (~1 min): boots `build/game` on desktop LÖVE under Xvfb
   with `love.system.getOS()` spoofed to `'Android'` so shipped code paths run
   verbatim. Asserts boot-to-menu, screenshots to `build/smoke/smoke.png`.
   Boots to the Cryptid menu in ~14s at ~150fps on llvmpipe.
3. **`just emu-test`** (~5–10+ min): headless Android emulator (KVM, x86_64
   API-34 image with ARM→x86 translation for our ARM-only APK). Installs the
   actual signed APK, mirrors the run-as deploy, polls screenshots for a
   verdict. Every adb call serial-pinned to `emulator-5560`.

Hard-won harness lessons:
- LÖVE's crash screen keeps the process alive and writes **nothing** to
  emulator logcat → crash detection must be visual (static + flat-colored
  frame = crash; animated + color-diverse = alive).
- LuaJIT cannot JIT under ARM translation → emulator boots take minutes; the
  poll budget is `EMU_BOOT_BUDGET` (420s default) and still wasn't enough on
  the last run — frame was animating (loading) at budget. Known limitation:
  portrait letterboxing keeps black ~47% dominant, so the menu detector needs
  a viewport crop before it can pass. Queued refinement.
- `sort | head` inside `$( )` under `set -o pipefail` SIGPIPE-kills the
  script — single-pass awk instead.
- **What still needs the phone**: Mali fp16/mediump shader behavior, real
  touch feel, performance numbers. Nothing else.

The emulator paid for itself on day one by catching two latent shader defects:

- **28 shipped .fs files lacked trailing newlines**; blur.fs ends in `#endif`
  and a directive at EOF without newline is invalid GLSL — Mali/Mesa tolerate
  it, the emulator's translator crash-looped. Fixed: `apply_shader_eof_newlines`.
- **blur.fs is the only shipped shader structured prototypes-first**
  (`effect()` calling forward-declared helpers); the translator miscompiles
  that in the vertex stage. Fixed by content-preserving reorder to the
  convention all 12 other hue-shaders use: `apply_blur_shader_reorder`
  (marker `BLUR_PROTO_REORDER`). Pinned via line-number fingerprint: only
  blur.fs has the triple-`hue` return at line 102.

## 4. The incident: rendering corruption on the phone

**What happened:** the parallel session applied a perf finding — "skip
setShader between consecutive same-shader draws" (`e33ee25`) — and deployed.
All card rendering corrupted ("laughably fucked").

**Root cause:** LÖVE's `Shader:send` is not batch-aware. The per-draw
`setShader(nil)` + rebind that the optimization removed is precisely what
flushes each sprite's geometry batch, scoping per-card uniforms
(`texture_details`, `mouse_screen_pos`, dissolve, burn colors) to their card.
Without the flush, whole batches render with the LAST card's uniforms — wrong
atlas regions and tilt on every card. Not Mali-specific.

**Why the safety nets missed it:** the desktop smoke asserts boot-to-menu,
not rendering correctness; the gesture suite doesn't render at all. The
hunter's own finding had flagged this medium-risk / needs-audit.

**Why git couldn't revert it:** the commit was message-only — the code edits
went directly into **untracked `src/dump/`** (and gitignored `build/game/`).
Reversal was done by hand in the dump (`1342a45` records it), rebuilt, gated
through the ladder, redeployed.

**Prevention now in place:**
- `docs/PERF-FINDINGS.md` rules of engagement: measure before shipping;
  changes via build.sh appliers or tracked files, never raw dump edits;
  ladder before deploy.
- The correct alternative if shader-bind overhead ever matters:
  uniform-value caching, not bind elision.

## 5. The deploy boundary (the bigger incident)

Multiple sessions deployed to Joe's phone without being asked — each deploy
force-restarts the app while he's using the device, and one shipped the
rendering corruption. His phone is not a CI target.

**Hard rule, enforced in code** (`2a8c40d`): `deploy()` refuses physical
devices unless `BALATRO_DEPLOY_PHONE=1` is set. Setting that variable IS the
claim that Joe explicitly asked for a deploy in the current conversation —
one approval is never standing approval. Emulator serials exempt. The rule is
also in session memory and Ghost so every future session inherits it.
(Related: avoid screencap/screenrecord of the phone unasked — it captures
whatever he's doing.)

## 6. The architecture map

`docs/GAME-ARCHITECTURE.md` (~2,300 lines): the build pipeline, the
lovely-dump reality, all 18 core subsystems plus gap-fill passes — every map
adversarially spot-checked against code, with corrections applied. Produced by
multi-agent audit (18 mappers + verifiers + critic; then 8 gap-fillers +
verifiers; ~5M tokens total) merged with session knowledge. Highlights a
future patch author must know:

- G.STATES enum with TRUE values (SELECTING_HAND=1 … NEW_ROUND=19, DEMO_CTA
  not "DEMO_CUT"); dispatch is independent `if`s — a mid-update state change
  runs the new handler the same frame.
- The SMODS loader wrapper-nesting rule: ascending priority, last-assign wraps
  outermost → sticky-fingers (100000) is always the outermost wrapper.
- RNG: `%.13f` rounding is part of the PRNG (never "optimize" it);
  pseudoseed during run-load desyncs save replay.
- Scoring lives in talisman.lua:672-820 (single harness post-dedup); the only
  yield is the Card:calculate_joker wrapper (0.03s cadence).
- Card:hover rebuilds the full description UI tree every time (no cache).
- Canvas pipeline: G.AA_CANVAS is permanently nil everywhere; CRT settings
  value is mutated in-place mid-frame (read between :3155-3170 sees 30%).

## 7. The performance program

Profiled baseline (not guessed): 63–76 FPS in-run, e_manager ~0.2ms, thermal
0 at idle. Targets: headroom, battery, scoring throughput, GC-spike avoidance
(GC is manual + budgeted — allocation churn becomes deferred frame spikes).

Six-path hunt (draw/update/scoring/memory/platform/UI), 43 findings, each
verified. Status (full detail in PERF-FINDINGS.md + the raw file):

- **16 already applied** by the parallel session (scoring-path cleanups, CRT
  uniform gate, FPS_CAP=60 Android, HUD text dedups, OmegaNum paired-compare
  eliminations, enh_cache fixes…).
- **1 reverted disaster** (setShader bind elision — §4).
- **3 rejected** (2 hunter hallucinations; the pseudoseed format change).
- **7 Tier-1 remaining**: safe GC-pressure wins, measurable locally via
  heap deltas in the smoke (align_cards closure hoist is the headline:
  7 sort closures/area/frame).
- **4 Tier-2**: audits first — headlined by the `to_big` fast-path below 1e15
  (550+ OmegaNum allocs per hand at normal chip scales).
- **Tier-3**: hover-popup cache and DynaText glyph cache — measure first.

## 8. Process notes (multi-session + multi-agent)

- Two sessions worked the same repo concurrently. Costs observed: an edit
  race on build.sh, one session's commit swallowing the other's working-tree
  doc edits (`0c01843`'s only tracked content is the other session's 217 doc
  lines), and the unreviewed perf deploy. If running parallel sessions again:
  one writer per file class, and everything through appliers/tracked files.
- The workflow runner died mid-run three times (agents frozen on delivered
  results at identical timestamps). Resume-from-journal recovers cheaply;
  bounded verifier budgets (~30 tool calls) prevent runaway verification; a
  transcript-mtime watchdog catches silent stalls. Final harvest was completed
  by hand from agent transcripts.
- Adversarial verification earned its keep everywhere: it caught a fabricated
  CardArea section, a wrong G.STATES enum, hunter hallucinations, and dozens
  of off-by-N line citations.

## 9. Current state & pending

**On the phone right now:** the reverted-rendering build (deployed at Joe's
implicit demand to fix the corruption; before the deploy gate landed).
**Awaiting Joe's eyeball** that cards render correctly again.

**Built locally, not deployed** (gate enforced): everything since — shader
EOF/reorder fixes, the parallel session's later perf patches (gated green
through gestures+smoke), telemetry PERF_SNAPSHOT emitter.

Pending, in rough order:
1. Joe confirms phone rendering is sane.
2. Tier-1 perf passes (patch → local heap-delta measurement → ladder →
   accumulate; deploy only on explicit ask).
3. Tier-2 `to_big` fast-path audit + boundary tests.
4. Emulator menu-detector viewport crop + bigger boot budget.
5. On-device verification round for slide-to-select + tap-description feel,
   whenever Joe is at the phone with a deploy he asked for.
