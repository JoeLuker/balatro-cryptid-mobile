# nix/ — pinned inputs + the build derivation (pattern A)

The LÖVE Cryptid build follows the **distro source-package pattern**: pristine
pinned upstream + an applied patch series + a content-addressed lockfile —
realized as a Nix derivation (the hermetic form of that pattern).

| File | Role | Literature analogue |
|------|------|---------------------|
| `sources.json` | the lockfile — every input pinned by sha256 | `flake.lock`, `go.sum`, niv `sources.json` |
| `update-sources.sh` | regenerate the lockfile (resolve refs → hashes) | `niv update`, `npins` |
| `sources.nix` | read the lockfile → Nix fetchers | niv `sources.nix` |
| `balatro-cryptid.nix` | the build: fetch → patch → assemble → sign (Phase 2) | RPM `.spec`, Debian `rules` |

## Update a pin
```bash
# edit the source table in update-sources.sh (bump a tag/ref), then:
nix/update-sources.sh        # rewrites sources.json with fresh hashes
git diff nix/sources.json    # review the bump
```

## Design decisions
- **Game is pinned, not vendored.** `Balatro.love` (© LocalThunk) is a
  `requireFile` (bytes never enter git); `base.apk` is a pinned `fetchurl`.
- **Mods that ship release-asset zips** (Amulet, CardSleeves) are
  pinned by asset URL + flat hash; the build unzips them.
- **Mods consumed from git** (Cryptid @ tag, Steamodded/sticky-fingers @ commit)
  are `fetchFromGitHub` by rev. Steamodded was rolling `main` (unpinned) — now
  pinned to a commit, which **de-drifts** it and needs a build re-verify.
- **lovely-injector** is host-only (desktop dump regen); it is *not* embedded in
  the APK, so it is intentionally absent from the lockfile.
