# overlay/game/ — files we own outright (base/overlay pattern)

Files here are **full source files we author/replace**, copied over the pinned
pristine game tree *after* patches apply and *before* `game.love` is zipped.

Use this (not a patch) when we replace most/all of a file or add a new one — it's
clearer to own the whole file than to carry a giant diff. Mirror the game-root
layout, e.g. `overlay/game/conf.lua`, `overlay/game/nativefs.lua`.

Migrated here from `scripts/build.sh` (Phase 2): the heredoc `conf.lua`, the
standalone runtime modules (`android-telemetry.lua`, `trigger-collapse.lua`,
`lazy-shader.lua`, `idle-joker-perf.lua`), the Android `nativefs.lua` wrapper,
and the `reserve-shim/` mini-mod (today in `patches/`).
