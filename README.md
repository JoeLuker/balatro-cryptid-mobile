# Balatro Cryptid Mobile

Build and deploy modded Balatro (with Cryptid) to Android.

## Quick Start

```bash
# Check tools are available
just check

# Full build and deploy
just all

# Or step by step:
just fetch    # Download sources and mods
just build    # Build APK and prepare files
just deploy   # Install to connected phone
```

## Project Structure

```
balatro-cryptid-mobile/
├── config.yaml              # Build configuration (declarative)
├── justfile                 # Build commands
├── README.md
├── src/
│   ├── Balatro.love        # Original game from Steam
│   ├── base.apk            # Android LÖVE runtime
│   └── dump/               # Lovely-generated Lua patches
├── mods/
│   ├── Steamodded/         # Mod framework
│   ├── Cryptid/            # Main mod
│   ├── Talisman/           # Big number support
│   └── lovely/             # Injector config
├── patches/
│   ├── android-nativefs.lua    # Android filesystem wrapper
│   └── crt-shader-fix.patch    # Pixel GPU fix
├── scripts/
│   └── build.sh            # Main build script
└── build/                  # Build artifacts (gitignored)
    ├── apk/
    └── phone-transfer/
```

## Requirements

- `apktool` - APK decompilation/recompilation
- `zipalign` - APK alignment
- `apksigner` - APK signing
- `adb` - Android Debug Bridge
- `just` - Command runner (optional, can use build.sh directly)

On NixOS:
```bash
nix-shell -p apktool android-tools just
```

## How It Works

1. **Sources**: Original `Balatro.love` from Steam + Android LÖVE base APK
2. **Mods**: Downloaded from GitHub releases (Steamodded, Cryptid, Talisman)
3. **Patches**: Android-specific fixes applied during build
4. **Build**: APK repackaged with game assets + patched shaders
5. **Deploy**: APK installed, mod files pushed to app's internal storage

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
| `just logs` | Watch app logs |
| `just restart` | Restart the app |
| `just clean` | Remove build artifacts |

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
