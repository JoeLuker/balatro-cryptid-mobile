# overlay/config/ — mod configuration, baked in

Mod config files that override an upstream mod's shipped defaults, applied after
the mod's pristine source is unpacked.

Replaces the top-level `config-overrides/` tree, which exists only because the
old `just fetch` clobbered configs and then re-copied them back. With pinned
pristine sources there is no clobber: the config is simply layered on once.

Layout mirrors the mod, e.g. `overlay/config/Cryptid/config.lua`,
`overlay/config/Steamodded/config.lua`. Migrated in Phase 4.
