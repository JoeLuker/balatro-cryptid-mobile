# Balatro Cryptid Mobile

Build and deploy modded Balatro (with Cryptid) to Android.

## Quick Start

```bash
# Build the modded, signed APK — pinned + reproducible, via Nix
just build            # nix build .#gameLove → build/game + nix/sign.sh → build/apk

# Deploy to a connected phone (installs build/apk, pushes build/phone-transfer)
just deploy           # or: just all   (build + deploy)
```

All inputs are pinned in `nix/sources.json` (no network `latest`). The build is a
Nix derivation: pinned pristine sources → patch series → `game.love` → APK.
See `MIGRATION.md` for the full architecture.

## Project Structure

```
balatro-cryptid-mobile/
├── flake.nix / justfile      # `nix build` / `just build|deploy`
├── nix/
│   ├── sources.json          # pinned inputs (lockfile); update-sources.sh re-pins
│   ├── sources.nix           # lockfile → fetchers
│   ├── balatro-cryptid.nix   # the build: pristine → patches → game.love → APK
│   ├── gen-patches.sh        # (re)generate the patch series from build.sh apply_*
│   ├── regen-dump.sh         # regenerate vendor/dump from the pins (lovely, Xvfb)
│   └── sign.sh               # sign the reproducible APK with keys/
├── overlay/
│   ├── patches/  *.patch + series   # our diffs vs pristine (applied hard-fail)
│   ├── game/                 # files we own outright (conf.lua, …)
│   └── config/               # baked-in mod config (Cryptid, Steamodded)
├── vendor/dump/              # lovely-merged game Lua, generated from the pins
├── patches/                  # runtime modules + Android/perf patches + reserve-shim
├── scripts/build.sh          # deploy/logs/clean + the apply_* patch source
├── rebuild/                  # the Kotlin/Compose remake (separate project)
└── build/  (gitignored)      # build/game, build/apk/, build/phone-transfer/
```

## Requirements

- `apktool` - APK decompilation/recompilation
- `zipalign` - APK alignment
- `apksigner` - APK signing
- `adb` - Android Debug Bridge
- `just` - Command runner (optional, can use build.sh directly)

On NixOS, the repo ships a `shell.nix` that pins the whole toolchain
(including the Android build-tools that provide `zipalign`/`apksigner`):
```bash
nix-shell                       # drops you into a shell with everything on PATH
nix-shell --run 'just build'    # or run a single command in the env
```

## How It Works

1. **Sources**: Original `Balatro.love` from Steam + Android LÖVE base APK
2. **Mods**: Downloaded from GitHub releases (Steamodded, Cryptid, Talisman)
3. **Patches**: Android-specific fixes applied during build
4. **Build**: APK repackaged with game assets + patched shaders
5. **Deploy**: APK installed, mod files pushed to app's internal storage

## Local testing (no phone required)

Three layers, fastest first — run them in order before any device deploy:

1. **Gesture suite** (`just test-controller`, <1s): drives the real built
   `engine/controller.lua` (all touch patches included) with scripted
   tap/hold/drag/slide timelines under pure LuaJIT.
2. **Desktop smoke** (`just smoke`, ~1 min): boots `build/game` on desktop
   LÖVE under Xvfb with `love.system.getOS()` spoofed to `'Android'` so the
   shipped code paths run verbatim; asserts boot-to-menu and saves a
   screenshot to `build/smoke/smoke.png`.
3. **Emulator** (`just emu-test`, ~5–10 min): installs the actual signed APK
   in a headless Android emulator (KVM, ARM→x86 translation), mirrors the
   deploy, and polls screenshots until a menu-like frame (PASS) or the LÖVE
   crash-screen signature (FAIL). Artifacts in `build/emulator/`.

What still needs the phone: Mali-GPU shader behaviour (fp16/mediump quirks),
real touch feel, and performance numbers.

See `docs/GAME-ARCHITECTURE.md` for a full map of the game code and patches.

## Commands

| Command | Description |
|---------|-------------|
| `just check` | Verify all tools are available |
| `just fetch` | Download sources and mods |
| `just build` | Build APK and transfer files |
| `just deploy` | Install to connected phone |
| `just all` | Full pipeline (fetch + build + deploy) |
| `just quick` | Rebuild and deploy (skip fetch) |
| `just push-mods` | Push mod files only (no APK install) |
| `just test-controller` | Local touch-gesture regression suite (<1s, no phone) |
| `just smoke` | Boot the built game locally under Xvfb, assert menu + screenshot (~1 min) |
| `just emu-test` | Boot the built APK in a headless Android emulator (~5-10 min) |
| `just test` | Gesture suite + smoke (run before any deploy) |
| `just logs` | Watch app logs |
| `just restart` | Restart the app |
| `just clean` | Remove build artifacts |
| `just list-configs` | Show config override files |
| `just edit-cryptid` | Edit Cryptid config |
| `just edit-steamodded` | Edit Steamodded config |

## Adding and Managing Mods

### Two Types of Mods

**1. Pure Lua Mods** (e.g., Cryptid, Talisman)
- Just Lua code that runs on top of the game
- Can be added by placing them in `mods/` and updating `build.sh`
- Work immediately after rebuild

**2. Lovely-Patching Mods** (e.g., sticky-fingers)
- Use `.toml` files to patch game engine code
- Require regenerating dump files on desktop
- More complex to add

### Adding a Pure Lua Mod

1. Download/clone the mod to `mods/YourMod/`
2. Edit `scripts/build.sh` - add the mod name to the `for mod in ...` loops (search for "Steamodded Cryptid")
3. Run `just build && just deploy`

### Adding a Lovely-Patching Mod

These mods modify the game's core Lua files via lovely's patch system. The patches are applied when generating dump files, not at runtime.

1. **On your desktop Mac/PC:**
   - Install the mod in Balatro's Mods folder (`~/Library/Application Support/Balatro/Mods/` on Mac)
   - Run Balatro with lovely injector installed
   - The patched code is written to `~/Library/Application Support/Balatro/Mods/lovely/dump/`

2. **Copy dump files to this project:**
   ```bash
   # From Mac (replace with your path)
   cp -r ~/Library/Application\ Support/Balatro/Mods/lovely/dump/* src/dump/

   # Or via SSH from othaos
   scp -r othaos:"~/Library/Application Support/Balatro/Mods/lovely/dump/*" src/dump/
   ```

3. **Add the mod to the build:**
   - Place the mod in `mods/YourMod/`
   - Update `build.sh` to include it
   - Run `just build && just deploy`

**Important:** Any time you add/remove/update a lovely-patching mod, you must regenerate the dump files on desktop.

### Config Overrides

Mod configs in `mods/` are overwritten when you run `just fetch`. To preserve your settings:

1. Edit files in `config-overrides/` instead:
   - `config-overrides/Cryptid/config.lua`
   - `config-overrides/Steamodded/config.lua`

2. These are automatically copied over the downloaded configs after fetch

### Which Type is My Mod?

Check if the mod has a `lovely/` folder with `.toml` files:
- **Has `.toml` patches** → Lovely-patching mod (needs dump regeneration)
- **No `.toml` patches** → Pure Lua mod (just add and rebuild)

## Patches

### android-nativefs.lua

Balatro mods use `nativefs` for file operations, which relies on FFI that doesn't work on Android. This patch wraps nativefs to use LÖVE's built-in `love.filesystem` on Android.

### crt-shader-fix.patch

The CRT shader's noise calculation uses `time * 1000.0` which causes floating point overflow on Pixel phones (Tensor GPU), creating a black oval. Fixed by using a hash-based approach with bounded time values.

## Troubleshooting

**App crashes immediately**: Check logs with `just logs`. Common issues:
- Missing mod files: run `just push-mods`
- nativefs errors: ensure android-nativefs.lua is in place

**Black oval on screen**: CRT shader not patched. Rebuild with `just build`

**Can't push files**: Ensure APK is debuggable and installed

## License

This is a modding tool. Balatro is © LocalThunk. Mods are by their respective authors.
