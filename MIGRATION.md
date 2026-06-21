# LÖVE build restructure → Nix-native pristine-vendor + patch-series

Goal: kill the live-patch / fetch-`latest` / 5,127-line-`sed`-script model and make
the modded APK **reproducible and fully baked in** — pinned inputs, declarative
patches that fail loud, a thin build. Pattern: distro source-package
(pristine + quilt series + lockfile), realized as a Nix derivation.

## Status

| Phase | Deliverable | State |
|------:|-------------|-------|
| 1 | **Pinning** — `nix/sources.json` lockfile, `update-sources.sh`, `sources.nix` | ✅ done & verified (pins realise, hashes valid) |
| 2 | **Build derivation** — `nix/balatro-cryptid.nix`; `overlay/game/conf.lua` owned | 🟡 gameLove **build-verified** (785 files from pins); apk eval-verified |
| 3 | **Patch conversion** — 72 `apply_*` → `overlay/patches/*.patch` + `series`, applied `--check` (hard-fail) | ⬜ |
| 4 | **Config** — fold `config-overrides/` → `overlay/config/`; delete the clobber-reapply path | ⬜ |
| 5 | **Cleanup** — retire `scripts/build.sh`, quarantine `src/` dumps, drop `tools/lovely*` from tree | ⬜ |
| 6 | **Project split** — make `rebuild/` (Kotlin) vs the LÖVE build explicit roots | ⬜ |

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

## Phase 3 mechanism — convert patches *mechanically*, and audit them

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
