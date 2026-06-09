#!/usr/bin/env bash
set -euo pipefail

# Balatro Cryptid Mobile - Build Script
# Usage: ./scripts/build.sh [command]
# Commands: fetch, build, deploy, all (default)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="$PROJECT_DIR/build"
SRC_DIR="$PROJECT_DIR/src"
MODS_DIR="$PROJECT_DIR/mods"
PATCHES_DIR="$PROJECT_DIR/patches"

# Config (could be read from config.yaml with yq)
PACKAGE_ID="com.unofficial.balatro.cryptid"
# The base APK's original package - used for adb/run-as since renameManifestPackage
# doesn't work reliably with apktool 2.12.x
INSTALLED_PACKAGE_ID="systems.shorty.lmm"
APP_NAME="Balatro Cryptid"
DEBUGGABLE="true"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[OK]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Check required tools
check_tools() {
    log_info "Checking required tools..."
    local missing=()

    # Find tools in nix store with deeper search
    find_nix_tool() {
        local tool="$1"
        find /nix/store -maxdepth 6 -name "$tool" -type f 2>/dev/null | grep -v "\.drv" | head -1
    }

    for tool in apktool zipalign apksigner adb keytool unzip zip curl; do
        if ! command -v "$tool" &>/dev/null; then
            local nix_path=$(find_nix_tool "$tool")
            if [[ -n "$nix_path" ]]; then
                log_info "Found $tool at $nix_path"
                export PATH="$(dirname "$nix_path"):$PATH"
            else
                missing+=("$tool")
            fi
        fi
    done

    if [[ ${#missing[@]} -gt 0 ]]; then
        log_error "Missing tools: ${missing[*]}"
        log_info "Install with: nix-shell -p apktool android-tools openjdk"
        exit 1
    fi
    log_success "All tools available"
}

# Fetch sources
fetch_sources() {
    log_info "Fetching sources..."
    mkdir -p "$SRC_DIR" "$MODS_DIR"

    # Fetch base.apk if not present
    if [[ ! -f "$SRC_DIR/base.apk" ]]; then
        log_info "Downloading base.apk..."
        curl -L -o "$SRC_DIR/base.apk" "https://lmm.shorty.systems/base.apk"
    fi

    # Fetch Balatro.love from othaos if not present
    if [[ ! -f "$SRC_DIR/Balatro.love" ]]; then
        log_info "Fetching Balatro.love from othaos..."
        scp "othaos:~/Library/Application Support/Steam/steamapps/common/Balatro/Balatro.app/Contents/Resources/Balatro.love" "$SRC_DIR/"
    fi

    # Fetch mods
    fetch_mod "Steamodded/smods" "smods" "Steamodded"
    fetch_mod "MathIsFun0/Cryptid" "cryptid" "Cryptid"
    fetch_mod "MathIsFun0/Talisman" "talisman" "Talisman"
    fetch_mod "ethangreen-dev/lovely-injector" "lovely" "lovely"
    fetch_mod_source "eramdam/sticky-fingers" "sticky-fingers"

    # Apply config overrides
    apply_config_overrides

    log_success "Sources fetched"
}

apply_config_overrides() {
    local overrides_dir="$PROJECT_DIR/config-overrides"
    if [[ -d "$overrides_dir" ]]; then
        log_info "Applying config overrides..."
        for mod_dir in "$overrides_dir"/*/; do
            local mod_name=$(basename "$mod_dir")
            if [[ -d "$MODS_DIR/$mod_name" ]]; then
                cp -r "$mod_dir"* "$MODS_DIR/$mod_name/"
                log_info "  Applied overrides for $mod_name"
            fi
        done
    fi
}

fetch_mod() {
    local repo="$1"
    local name="$2"
    local extract_name="$3"

    if [[ ! -d "$MODS_DIR/$extract_name" ]]; then
        log_info "Fetching $name from GitHub..."
        local zip_url="https://github.com/$repo/releases/latest/download/$name.zip"
        local zip_file="$MODS_DIR/$name.zip"

        if curl -fL -o "$zip_file" "$zip_url" 2>/dev/null; then
            unzip -q -o "$zip_file" -d "$MODS_DIR/"
            rm "$zip_file"
        else
            log_warn "Could not fetch $name, trying archive..."
            curl -L -o "$zip_file" "https://github.com/$repo/archive/refs/heads/main.zip"
            unzip -q -o "$zip_file" -d "$MODS_DIR/"
            mv "$MODS_DIR/${repo##*/}-main" "$MODS_DIR/$extract_name" 2>/dev/null || true
            rm "$zip_file"
        fi
    fi
}

# Fetch mod from source (no release zip, just clone the repo)
fetch_mod_source() {
    local repo="$1"
    local extract_name="$2"

    if [[ ! -d "$MODS_DIR/$extract_name" ]]; then
        log_info "Fetching $extract_name from GitHub source..."
        local zip_file="$MODS_DIR/$extract_name.zip"
        curl -L -o "$zip_file" "https://github.com/$repo/archive/refs/heads/main.zip"
        unzip -q -o "$zip_file" -d "$MODS_DIR/"
        mv "$MODS_DIR/${repo##*/}-main" "$MODS_DIR/$extract_name"
        rm "$zip_file"
    fi
}

# Generate dump files by running Balatro with lovely on host
generate_dumps() {
    log_info "Checking for dump files..."

    if [[ ! -d "$SRC_DIR/dump" ]] || [[ ! -f "$SRC_DIR/dump/main.lua" ]]; then
        log_warn "Dump files not found. You need to:"
        log_info "1. Install lovely-injector on your PC"
        log_info "2. Copy mods to Balatro's Mods folder"
        log_info "3. Run Balatro once to generate dumps"
        log_info "4. Copy dumps from %APPDATA%/Balatro/Mods/lovely/dump/ to $SRC_DIR/dump/"

        # Try to fetch from othaos if available
        if ssh othaos "test -d ~/Library/Application\ Support/Balatro/Mods/lovely/dump" 2>/dev/null; then
            log_info "Found dumps on othaos, fetching..."
            mkdir -p "$SRC_DIR/dump"
            scp -r "othaos:~/Library/Application Support/Balatro/Mods/lovely/dump/*" "$SRC_DIR/dump/"
        else
            return 1
        fi
    fi

    log_success "Dump files available"
}

# Build the APK
build_apk() {
    log_info "Building APK..."

    mkdir -p "$BUILD_DIR"/{apk,apktool,phone-transfer,game}

    # Decompile base APK
    if [[ ! -d "$BUILD_DIR/apktool/AndroidManifest.xml" ]]; then
        log_info "Decompiling base.apk..."
        apktool d -f -o "$BUILD_DIR/apktool" "$SRC_DIR/base.apk"
    fi

    # Modify AndroidManifest.xml
    log_info "Patching AndroidManifest.xml..."
    # Use apktool's renameManifestPackage so resources and activity resolution work correctly
    # (directly editing package= in the manifest breaks activity class lookup)
    if ! grep -q "renameManifestPackage" "$BUILD_DIR/apktool/apktool.yml"; then
        echo "renameManifestPackage: $PACKAGE_ID" >> "$BUILD_DIR/apktool/apktool.yml"
    else
        sed -i "s/renameManifestPackage:.*/renameManifestPackage: $PACKAGE_ID/" "$BUILD_DIR/apktool/apktool.yml"
    fi
    # Ensure game.love is stored uncompressed so LÖVE can read it as a ZIP-in-ZIP
    if ! grep -q "game.love" "$BUILD_DIR/apktool/apktool.yml"; then
        sed -i '/^doNotCompress:/a - assets/game.love' "$BUILD_DIR/apktool/apktool.yml"
    fi
    sed -i "s/android:label=\"[^\"]*\"/android:label=\"$APP_NAME\"/" "$BUILD_DIR/apktool/AndroidManifest.xml"

    if [[ "$DEBUGGABLE" == "true" ]]; then
        sed -i 's/android:allowBackup="true"/android:allowBackup="true" android:debuggable="true"/' "$BUILD_DIR/apktool/AndroidManifest.xml"
    fi

    # Build game.love archive (LÖVE Android expects assets/game.love as a ZIP)
    log_info "Building game.love archive..."
    local game_dir="$BUILD_DIR/game"
    rm -rf "$game_dir"
    mkdir -p "$game_dir"

    # Extract original Balatro.love
    log_info "  Extracting Balatro.love..."
    unzip -q -o "$SRC_DIR/Balatro.love" -d "$game_dir/"

    # Copy lovely dump files (patched Lua files)
    if [[ -d "$SRC_DIR/dump" ]]; then
        log_info "  Embedding lovely dump files..."
        cp -r "$SRC_DIR/dump/"*.lua "$game_dir/" 2>/dev/null || true
        cp -r "$SRC_DIR/dump/"*.txt "$game_dir/" 2>/dev/null || true
        cp -r "$SRC_DIR/dump/engine" "$game_dir/" 2>/dev/null || true
        cp -r "$SRC_DIR/dump/functions" "$game_dir/" 2>/dev/null || true
        cp -r "$SRC_DIR/dump/SMODS" "$game_dir/" 2>/dev/null || true

        # Copy nativefs folder (original FFI version for non-Android)
        cp -r "$SRC_DIR/dump/nativefs" "$game_dir/" 2>/dev/null || true

        # Replace top-level nativefs.lua with Android-compatible wrapper
        cp "$PATCHES_DIR/android-nativefs.lua" "$game_dir/nativefs.lua"
        log_success "  Dump files added"

        # Guard Sticky Fingers' cross-mod helpers against missing optional integrations.
        # The runtime code is the lovely dump (functions/), not the mod's lovely/ payload.
        apply_sticky_fingers_guard "$game_dir/functions/misc_functions.lua"
    else
        log_warn "No dump files found in $SRC_DIR/dump - mods won't work!"
    fi

    # Embed Mods folder.
    # NOTE: lovely-injector does not run on Android (no native lib) — the lovely
    # dump in functions/ and engine/ is what actually patches the game. So the
    # mods' own lovely/ payload folders are dead weight, and the standalone
    # "lovely" mod folder is only a stale dump + log. Neither is embedded.
    log_info "  Embedding Mods folder..."
    mkdir -p "$game_dir/Mods"
    for mod in Steamodded Cryptid Talisman sticky-fingers; do
        if [[ -d "$MODS_DIR/$mod" ]]; then
            cp -r "$MODS_DIR/$mod" "$game_dir/Mods/"
            rm -rf "$game_dir/Mods/$mod/lovely"
            log_info "    Embedded $mod"
        fi
    done

    # Reserve Shim: local mini-mod providing G.FUNCS.can_reserve_card/reserve_card
    # for Sticky Fingers' Pull target (extracted from Pokermon — see patches/reserve-shim)
    cp -r "$PATCHES_DIR/reserve-shim" "$game_dir/Mods/"
    log_info "    Embedded reserve-shim"

    # Create lovely.lua config
    cat > "$game_dir/lovely.lua" << EOF
return {
  repo = "https://github.com/ethangreen-dev/lovely-injector",
  version = "0.9.0",
  mod_dir = "Mods",
}
EOF

    # Apply patches to game files
    log_info "Applying patches..."
    apply_crt_fix "$game_dir/resources/shaders/CRT.fs"
    apply_android_settings_fix "$game_dir/globals.lua"
    apply_android_video_settings_fix "$game_dir/functions/UI_definitions.lua"
    # Use Python patcher for main.lua (more reliable than sed for complex patches)
    python3 "$SCRIPT_DIR/patch_main_lua.py" "$game_dir/main.lua"

    # Copy telemetry module into game root
    cp "$PATCHES_DIR/android-telemetry.lua" "$game_dir/android-telemetry.lua"
    log_success "Telemetry module embedded"

    # Patch conf.lua
    cat > "$game_dir/conf.lua" << 'EOF'
_RELEASE_MODE = true
_DEMO = false

function love.conf(t)
    t.console = not _RELEASE_MODE
    t.title = 'Balatro'
    t.window.width = 0
    t.window.height = 0
    t.window.minwidth = 100
    t.window.minheight = 100
end
EOF

    # Create game.love ZIP archive
    log_info "Creating game.love archive..."
    mkdir -p "$BUILD_DIR/apktool/assets"
    local game_love
    game_love="$(cd "$BUILD_DIR/apktool/assets" && pwd)/game.love"
    rm -f "$game_love"
    # Use subshell to avoid changing directory in main shell
    (cd "$game_dir" && zip -q -r "$game_love" .)
    log_success "game.love created ($(du -h "$game_love" | cut -f1))"

    # Rebuild APK
    log_info "Rebuilding APK..."
    # Use single-threaded mode to avoid race conditions with smali files
    apktool b -j 1 -f "$BUILD_DIR/apktool" -o "$BUILD_DIR/apk/unsigned.apk"

    # Align and sign
    log_info "Aligning APK..."
    zipalign -f 4 "$BUILD_DIR/apk/unsigned.apk" "$BUILD_DIR/apk/aligned.apk"

    log_info "Signing APK..."
    ensure_keystore
    apksigner sign --ks "$KEYSTORE_FILE" --ks-pass pass:android \
        --out "$BUILD_DIR/apk/$PACKAGE_ID.apk" "$BUILD_DIR/apk/aligned.apk"

    log_success "APK built: $BUILD_DIR/apk/$PACKAGE_ID.apk"
}

# Sticky Fingers wires touch drag-targets to optional integrations (Pokermon's
# reserve_card, Reverie's crazy cards, etc.) by calling G.FUNCS.can_<x> through
# sticky_can_<x> wrappers. Those wrappers don't check the function exists, so
# with Cryptid-but-not-Pokermon a Code card in a pack calls a nil
# G.FUNCS.can_reserve_card and crashes (only on mobile, where drag-targets
# render). Guard every wrapper's call so a missing integration returns false
# instead of crashing. Upstream bug: eramdam/sticky-fingers misc_functions.lua.
apply_sticky_fingers_guard() {
    local f="$1"

    if [[ ! -f "$f" ]]; then
        log_warn "Sticky Fingers misc_functions.lua not found, skipping guard"
        return 0
    fi
    if grep -q "STICKY_GUARD" "$f"; then
        log_info "Sticky Fingers guard already applied"
        return 0
    fi

    # Before each `G.FUNCS.can_x(temp_config)`, insert a nil-guard returning false.
    sed -i -E 's|^([[:space:]]*)(G\.FUNCS\.can_[a-zA-Z_]+)\(temp_config\)$|\1if not \2 then return false end -- STICKY_GUARD\n\1\2(temp_config)|' "$f"

    local n
    n=$(grep -c "STICKY_GUARD" "$f")
    log_success "Sticky Fingers guard applied ($n wrappers protected)"
}

apply_crt_fix() {
    local shader_file="$1"

    if grep -q "time \* 1000.0" "$shader_file"; then
        log_info "Applying CRT shader fix for Pixel/Tensor GPU..."
        # Replace the problematic noise calculation with hash-based approach
        sed -i '
        /\/\/Add in some noise/,/dx \* clamp/ {
            s/MY_HIGHP_OR_MEDIUMP number x = (tc.x - mod(tc.x, 0.002)) \* (tc.y - mod(tc.y, 0.0013)) \* time \* 1000.0;/\/\/ Fixed for Pixel\/Tensor GPU - hash-based noise\n    MY_HIGHP_OR_MEDIUMP number wrapped_time = mod(time, 100.0);\n    MY_HIGHP_OR_MEDIUMP number hash_input = tc.x * 12.9898 + tc.y * 78.233 + wrapped_time * 43.758;\n    MY_HIGHP_OR_MEDIUMP number x = fract(sin(hash_input) * 43758.5453);/
            s/x = mod( x, 13.0 ) \* mod( x, 123.0 );/\/\/ Original: x = mod( x, 13.0 ) * mod( x, 123.0 );/
            s/MY_HIGHP_OR_MEDIUMP number dx = mod( x, 0.11 )\/0.11;/MY_HIGHP_OR_MEDIUMP number dx = x; \/\/ was: mod( x, 0.11 )\/0.11/
        }
        ' "$shader_file"
        log_success "CRT shader fix applied"
    else
        log_info "CRT shader already fixed or pattern not found"
    fi
}

apply_android_settings_fix() {
    local globals_file="$1"

    if [[ ! -f "$globals_file" ]]; then
        log_warn "globals.lua not found, skipping Android settings fix"
        return
    fi

    # Check if already patched
    if grep -q "Android mobile settings" "$globals_file"; then
        log_info "Android settings already patched"
        return
    fi

    log_info "Patching globals.lua for Android mobile UI..."

    # Add Android-specific settings after the Windows block
    sed -i '/if love.system.getOS() == .Windows. then/,/end/ {
        /end/ a\
    -- Android mobile settings (enables mobile UI, disables desktop-only features)\
    if love.system.getOS() == '"'"'Android'"'"' then\
        self.F_MOBILE_UI = true\
        self.F_SAVE_TIMER = 30\
        self.F_DISCORD = false\
        self.F_CRASH_REPORTS = false\
        self.F_RUMBLE = false\
    end
    }' "$globals_file"

    log_success "Android settings fix applied"
}

# Hide desktop-only video settings on Android
apply_android_video_settings_fix() {
    local ui_file="$1"

    if [[ ! -f "$ui_file" ]]; then
        log_warn "UI_definitions.lua not found, skipping video settings fix"
        return
    fi

    # Check if already patched
    if grep -q "Android video settings hidden" "$ui_file"; then
        log_info "Video settings already patched for Android"
        return
    fi

    log_info "Patching UI_definitions.lua to hide video settings on Android..."

    # Replace the Video tab content to check for Android
    sed -i "/elseif tab == 'Video' then/,/elseif tab == 'Audio' then/ {
        /elseif tab == 'Video' then/ {
            a\\
    -- Android video settings hidden (monitor/resolution don't apply)\\
    if love.system.getOS() == 'Android' then\\
      return {n=G.UIT.ROOT, config={align = \"cm\", padding = 0.1, colour = G.C.CLEAR}, nodes={\\
        {n=G.UIT.R, config={align = \"cm\"}, nodes={\\
          {n=G.UIT.T, config={text = \"Video settings not available\", scale = 0.5, colour = G.C.UI.TEXT_LIGHT}}\\
        }},\\
        {n=G.UIT.R, config={align = \"cm\"}, nodes={\\
          {n=G.UIT.T, config={text = \"on mobile devices\", scale = 0.4, colour = G.C.UI.TEXT_INACTIVE}}\\
        }}\\
      }}\\
    end
        }
    }" "$ui_file"

    log_success "Video settings hidden on Android"
}

# Fix SMODS path discovery for Android
# On Android, NFS.getDirectoryItems doesn't work properly with APK assets
# We hardcode the path since we know where Steamodded is embedded
apply_android_smods_path_fix() {
    local main_file="$1"

    if [[ ! -f "$main_file" ]]; then
        log_warn "main.lua not found, skipping SMODS path fix"
        return
    fi

    # Check if already patched
    if grep -q "Android SMODS path fix" "$main_file"; then
        log_info "SMODS path already patched for Android"
        return
    fi

    log_info "Patching main.lua for Android SMODS path..."

    # Add Android package.preload and path fix BEFORE the SMODS = {} line
    # Use package.preload to map SMODS.version and SMODS.release to Steamodded root
    # The requires use 'SMODS.version' but files are at Mods/Steamodded/version.lua
    sed -i "/^SMODS = {}/i\\
-- Android SMODS path fix: preload SMODS modules and add Mods directories to require path\\
if love.system.getOS() == 'Android' then\\
    -- Preload SMODS.version and SMODS.release to map to Steamodded root files\\
    package.preload['SMODS.version'] = function() return love.filesystem.load('Mods/Steamodded/version.lua')() end\\
    package.preload['SMODS.release'] = function() return love.filesystem.load('Mods/Steamodded/release.lua')() end\\
    -- Add paths for mod requires including Steamodded libs (json, nativefs, https)\\
    -- LÖVE uses love.filesystem require path, not Lua package.path\\
    local love_paths = 'Mods/Steamodded/libs/?.lua;Mods/Steamodded/libs/?/init.lua;Mods/Steamodded/?.lua;Mods/Steamodded/?/init.lua;Mods/?.lua;Mods/?/init.lua'\\
    love.filesystem.setRequirePath(love.filesystem.getRequirePath() .. ';' .. love_paths)\\
end\\
" "$main_file"

    # Replace the find_self call with Android-aware version
    sed -i "s|SMODS.path = find_self(SMODS.MODS_DIR, 'core.lua', '--- STEAMODDED CORE')|-- Android SMODS.path hardcode since NFS doesn't work with APK assets\\
if love.system.getOS() == 'Android' then\\
    SMODS.path = 'Mods/Steamodded/'\\
else\\
    SMODS.path = find_self(SMODS.MODS_DIR, 'core.lua', '--- STEAMODDED CORE')\\
end|" "$main_file"

    # Also fix Talisman path discovery - on Android, hardcode everything
    sed -i "s|local info = nativefs.getDirectoryItemsInfo(lovely.mod_dir)|-- Android Talisman path fix\\
local info\\
if love.system.getOS() == 'Android' then\\
    info = {}\\
    for _, name in ipairs({'Cryptid', 'Steamodded', 'Talisman', 'lovely', 'sticky-fingers'}) do\\
        table.insert(info, {name = name, type = 'directory'})\\
    end\\
else\\
    info = nativefs.getDirectoryItemsInfo(lovely.mod_dir)\\
end|" "$main_file"

    # Fix Talisman's nativefs.getInfo for directory check - on Android, always return true for Talisman
    sed -i 's|nativefs.getInfo(lovely.mod_dir .. "/" .. v.name .. "/talisman.lua")|( love.system.getOS() == "Android" and v.name == "Talisman" or nativefs.getInfo(lovely.mod_dir .. "/" .. v.name .. "/talisman.lua") )|g' "$main_file"

    # Fix "if not nativefs.getInfo(talisman_path)" - skip check on Android
    sed -i 's|if not nativefs.getInfo(talisman_path) then|if love.system.getOS() ~= "Android" and not nativefs.getInfo(talisman_path) then|' "$main_file"

    # Fix specific nativefs.read call for config
    sed -i 's|local config_read_result = nativefs.read(talisman_path.."/config.lua")|local config_read_result = (love.system.getOS() == "Android") and love.filesystem.read(talisman_path.."/config.lua") or nativefs.read(talisman_path.."/config.lua")|' "$main_file"

    # Skip nativefs.write on Android - config saving won't work but loading does
    sed -i 's/nativefs\.write(talisman_path \.\. "\/config\.lua", STR_PACK(Talisman\.config_file))/pcall(function() if love.system.getOS() ~= "Android" then nativefs.write(talisman_path .. "\/config.lua", STR_PACK(Talisman.config_file)) end end)/g' "$main_file"

    # Fix nativefs.load calls for Big number library
    sed -i 's|Big, err = nativefs.load(talisman_path.."/big-num/"..Talisman.config_file.break_infinity..".lua")|Big, err = (love.system.getOS() == "Android") and love.filesystem.load(talisman_path.."/big-num/"..Talisman.config_file.break_infinity..".lua") or nativefs.load(talisman_path.."/big-num/"..Talisman.config_file.break_infinity..".lua")|' "$main_file"

    sed -i 's|Notations = nativefs.load(talisman_path.."/big-num/notations.lua")()|Notations = ((love.system.getOS() == "Android") and love.filesystem.load(talisman_path.."/big-num/notations.lua") or nativefs.load(talisman_path.."/big-num/notations.lua"))()|' "$main_file"

    log_success "SMODS path fix applied for Android"
}

ensure_keystore() {
    local keystore_dir="$PROJECT_DIR/keys"
    local keystore_file="$keystore_dir/debug.keystore"

    mkdir -p "$keystore_dir"

    if [[ ! -f "$keystore_file" ]]; then
        log_info "Creating debug keystore..."
        keytool -genkey -v -keystore "$keystore_file" \
            -storepass android -alias androiddebugkey -keypass android \
            -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=Android Debug,O=Android,C=US"
    fi

    # Export for use in signing
    KEYSTORE_FILE="$keystore_file"
}

# Prepare files for phone transfer (only Mods folder - dump files are in APK)
prepare_transfer() {
    log_info "Preparing files for phone transfer..."

    local transfer_dir="$BUILD_DIR/phone-transfer"
    rm -rf "$transfer_dir"
    mkdir -p "$transfer_dir/Mods"

    # Copy mods to transfer folder (lovely/ payloads stripped — not used on Android)
    for mod in Steamodded Cryptid Talisman sticky-fingers; do
        if [[ -d "$MODS_DIR/$mod" ]]; then
            cp -r "$MODS_DIR/$mod" "$transfer_dir/Mods/"
            rm -rf "$transfer_dir/Mods/$mod/lovely"
        fi
    done
    cp -r "$PATCHES_DIR/reserve-shim" "$transfer_dir/Mods/"

    # Create SMODS folder with version/release files for require'SMODS.version' to work
    # The lovely injector on desktop does this automatically, but we need it explicit for Android
    mkdir -p "$transfer_dir/Mods/SMODS"
    if [[ -f "$MODS_DIR/Steamodded/version.lua" ]]; then
        cp "$MODS_DIR/Steamodded/version.lua" "$transfer_dir/Mods/SMODS/"
    fi
    if [[ -f "$MODS_DIR/Steamodded/release.lua" ]]; then
        cp "$MODS_DIR/Steamodded/release.lua" "$transfer_dir/Mods/SMODS/"
    fi

    # Create lovely.lua config (goes in save root, tells game where mods are)
    cat > "$transfer_dir/lovely.lua" << EOF
return {
  repo = "https://github.com/ethangreen-dev/lovely-injector",
  version = "0.9.0",
  mod_dir = "Mods",
}
EOF

    log_success "Transfer files prepared: $transfer_dir"
    log_info "Note: Dump files are embedded in APK, only Mods folder needs to be pushed"
}

# Deploy to phone
deploy() {
    log_info "Deploying to phone..."

    # Check ADB connection
    if ! adb devices | grep -q "device$"; then
        log_error "No device connected via ADB"
        exit 1
    fi

    local device=$(adb devices | grep "device$" | head -1 | cut -f1)
    log_info "Deploying to device: $device"

    # Install APK
    local apk_file="$BUILD_DIR/apk/$PACKAGE_ID.apk"
    if [[ -f "$apk_file" ]]; then
        log_info "Installing APK..."
        adb install -r "$apk_file"
    fi

    # Push mod files
    log_info "Pushing mod files..."
    local transfer_dir="$BUILD_DIR/phone-transfer"
    local temp_dir="/data/local/tmp/balatro_mods"

    adb shell "rm -rf $temp_dir" 2>/dev/null || true
    adb push "$transfer_dir" "$temp_dir"

    # Copy to app's internal storage
    adb shell "run-as $INSTALLED_PACKAGE_ID mkdir -p files/save"
    adb shell "run-as $INSTALLED_PACKAGE_ID cp -r $temp_dir/* files/save/"

    # Verify
    log_info "Verifying deployment..."
    local file_count=$(adb shell "run-as $INSTALLED_PACKAGE_ID ls files/save/ | wc -l")
    log_success "Deployed $file_count items to device"

    # Launch app
    log_info "Launching app..."
    adb shell am start -n "$INSTALLED_PACKAGE_ID/org.love2d.android.GameActivity"
}

# Watch logs
watch_logs() {
    log_info "Watching app logs (Ctrl+C to stop)..."
    adb logcat -c
    adb logcat | grep -E "SDL/APP|LOVE|SMODS|NATIVEFS"
}

# Clean build artifacts
clean() {
    log_info "Cleaning build artifacts..."
    rm -rf "$BUILD_DIR"
    log_success "Clean complete"
}

# Main
main() {
    cd "$PROJECT_DIR"

    local cmd="${1:-all}"

    case "$cmd" in
        check)
            check_tools
            ;;
        fetch)
            check_tools
            fetch_sources
            generate_dumps
            ;;
        build)
            check_tools
            build_apk
            prepare_transfer
            ;;
        deploy)
            check_tools
            deploy
            ;;
        logs)
            watch_logs
            ;;
        clean)
            clean
            ;;
        all)
            check_tools
            fetch_sources
            generate_dumps || log_warn "Dumps not available, continuing..."
            build_apk
            prepare_transfer
            deploy
            ;;
        *)
            echo "Usage: $0 {check|fetch|build|deploy|logs|clean|all}"
            exit 1
            ;;
    esac
}

main "$@"
