# overlay/game/ — files we own outright (base/overlay pattern)

Files here are **full source files we author/replace**, copied over the pinned
pristine game tree *after* patches apply and *before* `game.love` is zipped.

Use this (not a patch) when we replace most/all of a file or add a new one — it's
clearer to own the whole file than to carry a giant diff. Mirror the game-root
layout, e.g. `overlay/game/conf.lua`, `overlay/game/nativefs.lua`.

Migrated here so far: **`conf.lua`** (the authoritative Android love.conf).

Still pending migration — the standalone runtime modules
(`android-telemetry.lua`, `trigger-collapse.lua`, `lazy-shader.lua`,
`idle-joker-perf.lua`, `emulator-smoke-check.lua`), the Android `nativefs`
wrapper, and the `reserve-shim/` mini-mod all still live in `patches/` and are
copied into the tree by `nix/balatro-cryptid.nix`. Moving them here is a
mechanical relocation (update the nix copy paths) that just hasn't been done.
