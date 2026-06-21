# LÖVE build restructure → Nix-native pristine-vendor + patch-series

Goal: kill the live-patch / fetch-`latest` / 5,127-line-`sed`-script model and make
the modded APK **reproducible and fully baked in** — pinned inputs, declarative
patches that fail loud, a thin build. Pattern: distro source-package
(pristine + quilt series + lockfile), realized as a Nix derivation.

## Status

| Phase | Deliverable | State |
|------:|-------------|-------|
| 1 | **Pinning** — `nix/sources.json` lockfile, `update-sources.sh`, `sources.nix` | ✅ done & verified |
| 2 | **Build derivation** — `nix/balatro-cryptid.nix` (gameLoveBase/gameLove/apk) | ✅ done & verified |
| 3a | **Dump from pins** — `stage-mods.sh` + `regen-dump.sh` → `vendor/dump` (stamped) | ✅ done & verified |
| 3b | **Patch conversion** — `gen-patches.sh` → 57 mechanical + 6 `manual/` ports, `git apply` hard-fail | ✅ done & verified (idempotent; 259 lua/0 errors) |
| 4 | **Config** — `config-overrides/` → `overlay/config/` baked overlays (incl. per-item toggle) | ✅ done & verified |
| — | **Flake + signing** — `packages.default` pure build; `nix/sign.sh` → signed APK | ✅ done & verified (89M, v1/v2/v3 verify) |
| 5 | **Cleanup** — retire `scripts/build.sh`, quarantine `src/` dumps, drop `tools/lovely*` from tree | ⬜ next |
| 6 | **Project split** — make `rebuild/` (Kotlin) vs the LÖVE build explicit roots | ⬜ |

**Build pipeline is complete & build-verified:** `pins (sources.json + flake.lock)
→ gameLoveBase → gameLove (63 patches) → apk` builds purely via `nix build`,
`nix/sign.sh` → signed 89M APK (v1/v2/v3 verify), 259 lua / 0 syntax errors.

## ⚠ Open runtime issue — Steamodded de-drift breaks SMODS load (BLOCKS shipping)

The smoke gate (boot the built game headless, spoof Android) revealed: the
from-pins build **crashes at boot — `main.lua: attempt to index global 'SMODS'
(a nil value)`** — while the legacy build/game does NOT hit that crash.

Root cause lead: the **Steamodded pin was de-drifted to current `main` (fdb7442)**,
but the validated phone/legacy build used an **older Steamodded** (`1.0.0~BETA-1224a`).
fdb7442 injects `main.lua` differently (1426 vs legacy 2578 lines) and its SMODS
bootstrap doesn't load via the from-pins dump under the desktop spoof.

Next step (pick one):
1. **Re-pin Steamodded to the validated version** (the `1224a`-era commit the phone
   shipped) → re-run `regen-dump.sh` + `gen-patches.sh` → re-smoke. Most likely fix.
2. **Emulator-validate** (`just emu-test` on the Nix APK) to check whether it's a
   desktop-spoof artifact vs a real boot failure on Android.

**`scripts/build.sh` is retained** (Phase 5 deferred) — it is the runtime-validated
fallback until the Nix APK boots clean. Do NOT retire it before this is resolved.

## Target layout

```
nix/
  sources.json          # lockfile (pinned sha256 for every input)
  sources.nix           # lockfile → fetchers
  update-sources.sh     # re-pin
  balatro-cryptid.nix   # the build derivation
overlay/
  patches/  series + *.patch   # our diffs vs pristine, hard-fail on reject
  game/     conf.lua, *.lua     # files we own outright (base/overlay)
  config/   Cryptid/, Steamodded/   # baked-in mod config
flake.nix                 # exposes packages.balatro-cryptid + the dev shell
# gone: scripts/build.sh, config-overrides/, src/dump*, tools/lovely*
```

## Phase 2 findings

- **`gameLove` builds from the pins** — `nix-build nix/balatro-cryptid.nix -A gameLove
  --arg dump <tree>` produces a 785-file `game.love`: vanilla + dump, Amulet
  `talisman/`+`big-num/` root-mounted, `Mods/{Steamodded,Cryptid,Amulet,
  sticky-fingers,CardSleeves,DebugPlus,reserve-shim}`, owned modules + `conf.lua`.
  It is **pre-patch** (series empty) and uses the working-tree dump — assembly-
  correct, not yet shippable.
- **The dump is a third pinned-input class.** `regen-dump.sh` already regenerates
  it *on Linux* (builds lovely from source, boots love+Xvfb headless) — it is not
  Mac-only. So it can be a pinned, generated input rather than a gitignored blob.
- **Two gates this surfaces:**
  1. *Flake purity* — a pure flake can't read the gitignored dump, so
     `packages.default` waits until the dump is **vendored** (committed) or built
     as a derivation. Until then the build runs via `nix-build … --arg dump`.
  2. *Dump↔pin consistency* — the dump MUST be regenerated from the **pinned**
     sources (esp. the de-drifted Steamodded `fdb7442`), else mod source and dump
     drift — the exact bug class this whole effort kills. Phase 3 regenerates from
     pins and vendors the result.
- **Deferred (size/secrets, not correctness):** `strip_en_us_assets` (~60 MB) and
  signing-key wiring (`ensure_keystore`) — the apk stage currently emits an
  aligned-unsigned APK so the pipeline is verifiable.

## Phase 3a findings (dump from pins)

- **The dump is now reproducible from the lockfile.** `nix/regen-dump.sh` stages
  pinned Balatro.love + pinned mods (`stage-mods.sh`) and boots love+lovely under
  Xvfb → `vendor/dump/` (34 lua), stamped with the source revs in `.source-revs`.
  gameLove builds from it with no `--arg` → flake-pure-able.
- **Fixed a latent bug doing it:** the old `regen-dump.sh` did `cp -r mods/Amulet`,
  but Amulet unpacks **flat** at `mods/` root (no `mods/Amulet/`) — so the old
  rig was dead against the current layout. `stage-mods.sh` puts every mod
  (Amulet included) in a correct desktop `Mods/<Name>/` layout.
- **Restart survived:** SMODS restarts LÖVE once after first load; seeding the
  game at love's save-identity path + pinning `XDG_*` lets the re-exec reach
  running state (without it only 5/34 files dumped).
- **Resolved gap:** `SMODS/_/smods-https-thread.lua` absent from the from-pins
  dump is **benign** — that path is a lovely runtime-registration hack name
  (`newFileData(..., '=[SMODS _ "smods-https-thread.lua"]')`); the real HTTPS
  code is `Mods/Steamodded/libs/https/smods-https.lua` (embedded) and loads from
  there on Android (no lovely). Nothing `require`s the dump path.

## Phase 3b findings (patch conversion)

- **63 patches generated** from build.sh's call order; **gameLove builds green**
  with all 63 `git apply`'d (hard-fail series — the 93 log_warn silent-skips are
  gone). Patched `game.love`: **259 lua files, 0 syntax errors** (luajit -bl),
  sentinels present (CRY_EVENTS_GUARDED, DISABLED_CENTER_SKIP, MALI_RANGE_FIX, …).
- **1 silent-skip remains** — `cryptid_oil_lamp_local_fix` is **REDUNDANT** (dropped):
  the pinned Cryptid v0.5.16a Oil Lamp `calculate` already uses `local other_card`
  (properly scoped); the bare `other_joker` leak the fix targeted is gone upstream.
- **`sticky_fingers_guard` resolved (patch 60):** the original function targeted
  `functions/misc_functions.lua` expecting sticky-fingers' lovely/misc_functions.toml
  append to be present in the dump. Root cause: lovely's `append` + `dump_lua = true`
  combination did not produce output in the vendored dump boot — both of
  sticky-fingers' lovely patches (misc_functions, button_callbacks) are absent from
  `vendor/dump/`. The `sticky_can_*` wrapper functions are therefore not present
  anywhere in the assembled tree. Fix: patch 60 appends all 7 guarded wrappers
  directly to `Mods/sticky-fingers/main.lua` (the only runtime-loaded lua in the
  embedded mod), with nil-guards baked in (STICKY_GUARD sentinel).
- **`drag_reject_feedback` resolved (patch 61):** same root cause — the anchor
  `e.config.release_func = nil` lives inside `G.FUNCS.check_drag_target_active`,
  defined in sticky-fingers' `lovely/button_callbacks.lua` (the other append that
  also did not land in the dump). Fix: patch 61 appends `check_drag_target_active`
  to `Mods/sticky-fingers/main.lua` with the DRAG_REJECT_FEEDBACK logic built in
  (rejected drop → cancel sound + card shake + red cost flash).
- **`structural_mods_lock` resolved (patch 62):** target moved from
  `Mods/Steamodded/src/loader.lua` to `Mods/Steamodded/src/preflight/loader.lua`
  in Steamodded de-drift to commit `fdb7442`. Patch re-anchored to three
  `NFS.getInfo(.../.lovelyignore)` checks in the new location, each guarded with
  `and not STRUCTURAL_BAKED[mod.id]` (STRUCTURAL_MODS_LOCK sentinel).
- **`mod_toggle_removed` resolved (patch 63):** the `create_toggle` callback in
  `Mods/Steamodded/src/ui.lua` was rewritten in `fdb7442` to use
  `require"SMODS.preflight.loader".addToBlacklist` instead of
  `NFS.write(.../.lovelyignore)`. Re-anchored to the new 31-line block;
  replacement is the same inert "baked" label (MOD_TOGGLE_REMOVED sentinel).
- **`cryptid_oil_lamp_local_fix` dropped (REDUNDANT):** the pinned Cryptid
  v0.5.16a Oil Lamp already uses `local other_card` in its `calculate` function;
  the bare `other_joker` global-leak the fix targeted does not exist. No patch needed.
- **`drag_self_drop_exclude` ported** (patch 59): the original anchor lived in the
  Lovely-modded TAP_DESC_HOLD_NODRAG block, absent from the pristine pinned engine.
  Fix re-anchored to `set_cursor_hover`'s collision-walk (`DRAG_SELF_DROP_EXCLUDE`
  sentinel, depth-8 parent walk, `_drag_anc` guard on the hover-candidate loop).
- **Shader EOL** normalised to LF in gameLoveBase so the git-diff shader patches
  are byte-stable (Cryptid ships CRLF; GLSL is EOL-agnostic).

## Phase 3b mechanism — convert patches *mechanically*, and audit them

Do **not** hand-rewrite 72 functions. Generate the diffs from the source of truth:

```
for each game/mod file the build touches:
  cp pristine(from sources.nix) → /tmp/work
  run the legacy apply_*  on /tmp/work        # the existing sed/python
  diff -u pristine /tmp/work > overlay/patches/NN-<name>.patch
  append NN-<name>.patch to series
```

This is faithful (the diff *is* what the build already produces) and reviewable.
Free bonus: **any `apply_*` whose anchor no longer matches produces an empty diff**
— that is the silent-skip audit. Each empty diff is a fix the current build is
*already shipping without* (93 `log_warn` skips today). Those get logged and
triaged, not buried.

## Decisions

- **Game pinned, never vendored** — `Balatro.love` via `requireFile`, `base.apk`
  via pinned `fetchurl`. Reproducible without redistributing © LocalThunk bytes.
- **Steamodded de-drifted** — was fetched from rolling `main` (phone shipped
  `1224a`, upstream `latest` is now `1814a`). Pinned to commit `fdb7442`. ⚠️ This
  changes the Steamodded version vs the last phone build → Phase 2 must re-verify
  the build (oracle + smoke) before any deploy.
- **lovely-injector** is host-only (desktop dump regen), not in the APK → not
  pinned here.
- **`trigger-collapse.lua`** (and the asize crash under investigation) is owned
  code → moves to `overlay/game/`, unaffected by this restructure.

## Verification gates (per phase, before moving on)
- P2: `nix-build` produces a signed APK; `just smoke` boots it to menu.
- P3: built `game.love` is byte-equivalent (modulo timestamps) to the legacy
  build's, OR every difference is an explained silent-skip we chose to fix.
- P4–6: `just test` green; emulator PASS.
