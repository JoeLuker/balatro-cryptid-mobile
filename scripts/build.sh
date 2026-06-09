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

    # Fix Cryptid's glitch shaders rendering pure-black cards on the Mali GPU
    apply_glitch_shader_fix "$game_dir/Mods/Cryptid/assets/shaders/glitched.fs"
    apply_glitched_b_fix "$game_dir/Mods/Cryptid/assets/shaders/glitched_b.fs"

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
    apply_mobile_graphics_defaults "$game_dir/globals.lua"
    # Hide only the Video tab (monitor/resolution/vsync — desktop-only, does nothing
    # on mobile). The Graphics tab (texture scaling / CRT / bloom / shadows) stays
    # visible so quality is tunable on device.
    apply_android_video_settings_fix "$game_dir/functions/UI_definitions.lua"
    apply_fps_toggle "$game_dir/game.lua" "$game_dir/functions/UI_definitions.lua"
    apply_debug_overlay "$game_dir/game.lua" "$game_dir/functions/misc_functions.lua" "$game_dir/functions/UI_definitions.lua"
    # Use Python patcher for main.lua (more reliable than sed for complex patches)
    python3 "$SCRIPT_DIR/patch_main_lua.py" "$game_dir/main.lua"
    apply_talisman_dim_fix "$game_dir/Mods/Talisman/talisman.lua"
    apply_shader_eof_newlines "$game_dir"
    apply_cryptid_dead_copy_fix "$game_dir/Mods/Cryptid/lib/calculate.lua"
    apply_cryptid_flip_side_cache "$game_dir/Mods/Cryptid/lib/calculate.lua" "$game_dir/Mods/Cryptid/lib/overrides.lua"
    apply_tap_description_persist "$game_dir/engine/controller.lua"
    apply_cursor_down_uptime_fix "$game_dir/engine/controller.lua"
    apply_drag_select "$game_dir/engine/controller.lua" "$game_dir/globals.lua" "$game_dir/functions/UI_definitions.lua"
    apply_shadow_height_fix "$game_dir/card.lua"
    apply_card_to_big_elim "$game_dir/card.lua"

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

# Lower default graphics settings for mobile. The Tensor/Mali GPU thermally
# throttles under desktop-grade settings (2x supersampling + full-screen CRT
# shader + bloom + shadows) — it shows up as the game slowing down after a few
# minutes of play (confirmed: thermal status SEVERE, ~59C). These are DEFAULTS;
# the Video settings tab is left intact so they can be raised on device.
apply_mobile_graphics_defaults() {
    local globals_file="$1"
    if [[ ! -f "$globals_file" ]]; then
        log_warn "globals.lua not found, skipping graphics defaults"
        return 0
    fi
    if grep -q "texture_scaling = 1," "$globals_file"; then
        log_info "Mobile graphics defaults already applied"
        return 0
    fi
    sed -i \
        -e "s/texture_scaling = 2,/texture_scaling = 1,/" \
        -e "s/shadows = 'On',/shadows = 'Off',/" \
        -e "s/crt = 70,/crt = 0,/" \
        -e "s/bloom = 1\$/bloom = 0/" \
        "$globals_file"
    log_success "Mobile graphics defaults applied (texture_scaling=1, crt/bloom/shadows off)"
}

# Cryptid's glitched.fs (in-game glitched cards) seeds its RGB-shift noise with
# `tan(2.*time)`, whose asymptotes produce inf/NaN on Mali -> NaN texture coords ->
# black card. Same fp16 issue as glitched_b: run the time-derived/noise math in
# highp (rand, t, iTime) so it matches desktop fp32, and keep the tan NaN guard.
# Texture path left at default precision (highp there is what Mali rejects).
apply_glitch_shader_fix() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "glitched.fs not found, skipping glitch shader fix"
        return 0
    fi
    if grep -q "Mali NaN" "$f"; then
        log_info "Glitch shader fix already applied"
        return 0
    fi
    sed -i 's|^float rand(vec2 co){|highp float rand(highp vec2 co){|' "$f"
    sed -i 's|\tfloat t = time \* 10.0 + 2003.;|\thighp float t = time * 10.0 + 2003.;|' "$f"
    sed -i 's|    float iTime = tan(2. \* time);|    highp float iTime = tan(2. * time);|' "$f"
    sed -i 's|float iTime = tan(2. \* time);|float iTime = tan(2. * time);\n    iTime = (abs(iTime) < 1000.0) ? iTime : 0.0; // Mali NaN\/inf guard: tan singularities render cards black|' "$f"
    if grep -q "Mali NaN" "$f" && grep -q "highp float rand" "$f"; then
        log_success "Glitch shader fix applied (highp math + tan guard)"
    else
        log_warn "Glitch shader fix did not fully match — check glitched.fs"
    fi
}

# glitched_b.fs (Cryptid's other glitch shader — used by e.g. the menu joker)
# renders pure black on the Mali GPU. Its chaotic noise math (pow^3/^5, division
# by ~0) overflows fp16 mediump (max ~65504) into inf/NaN, which propagates to
# the texture lookup. Desktop runs it in fp32 and is fine. Fix: make ONLY the
# math chain highp (the helpers mod2/bitxor and the accumulators) — leaving the
# texture/Texel path at default precision, since making the texture path highp is
# what Mali rejects ("overloaded functions must have the same precision"). Keep an
# output NaN guard as a belt-and-suspenders fallback to the plain texel.
# (Note: this shader declares a local `float mod` that shadows built-in mod() —
# do not call mod() inside effect().) Validated to compile via glslang.
apply_glitched_b_fix() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "glitched_b.fs not found, skipping fix"
        return 0
    fi
    if grep -q "Mali NaN" "$f"; then
        log_info "glitched_b fix already applied"
        return 0
    fi
    # fp32 on the overflow-prone math chain (texture path stays default precision)
    sed -i 's|^float bitxor(float val1, float val2)|highp float bitxor(highp float val1, highp float val2)|' "$f"
    sed -i 's|float mod2(float val1, float mod1)|highp float mod2(highp float val1, highp float mod1)|' "$f"
    sed -i 's|\tfloat t = glitched_b.y\*2.221 + time;|\thighp float t = glitched_b.y*2.221 + time;|' "$f"
    sed -i 's|\tfloat randnum = mod2|\thighp float randnum = mod2|' "$f"
    sed -i 's|    float cx = uv_scaled_centered.x \* 1.;|    highp float cx = uv_scaled_centered.x * 1.;|' "$f"
    sed -i 's|    float cy = uv_scaled_centered.y \* 1.;|    highp float cy = uv_scaled_centered.y * 1.;|' "$f"
    sed -i 's|    float mbx;|    highp float mbx;|; s|    float mby;|    highp float mby;|; s|    float offx;|    highp float offx;|; s|    float offy;|    highp float offy;|; s|    float rmasksum = -1.;|    highp float rmasksum = -1.;|; s|    float rectmask = 1.;|    highp float rectmask = 1.;|' "$f"
    # belt-and-suspenders: fall back to the plain texel if anything still blows up
    sed -i 's|tex.rgb = textp.rgb;|tex.rgb = textp.rgb;\n    if (!all(lessThan(abs(tex.rgb), vec3(1000000.0)))) { tex.rgb = Texel(texture, texture_coords).rgb; } // Mali NaN\/inf guard: glitch math -> black card|' "$f"
    if grep -q "Mali NaN" "$f" && grep -q "highp float randnum" "$f"; then
        log_success "glitched_b fix applied (highp math + NaN guard)"
    else
        log_warn "glitched_b fix did not fully match — check glitched_b.fs"
    fi
}

# Hot-path fix 1: delete the dead copy_table(ability) + in_context_scaling locals
# in Cryptid's Card:calculate_joker wrapper (calculate.lua:137-138 before this patch).
# orig_ability is assigned and never read; in_context_scaling is set and never read.
# Each invocation wasted a full deep-copy of the joker ability table.
apply_cryptid_dead_copy_fix() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "Cryptid calculate.lua not found, skipping dead copy_table fix"
        return 0
    fi
    if grep -q "CRY_DEAD_COPY_FIXED" "$f"; then
        log_info "Cryptid dead copy_table fix already applied"
        return 0
    fi
    # Delete the two dead locals; the line after them (local callback) remains.
    sed -i '/^\tlocal orig_ability = copy_table(active_side\.ability)$/d' "$f"
    sed -i 's|^\tlocal in_context_scaling = false$|\t-- CRY_DEAD_COPY_FIXED|' "$f"
    # Delete the dead write to in_context_scaling (inner assignment only; outer if is left inert).
    sed -i '/^\t\t\tin_context_scaling = true$/d' "$f"
    if grep -q "CRY_DEAD_COPY_FIXED" "$f"; then
        log_success "Cryptid dead copy_table fix applied (removed dead orig_ability deep-copy + in_context_scaling)"
    else
        log_warn "Cryptid dead copy_table fix did not apply — check calculate.lua"
    fi
}

# Hot-path fix 2: cache find_joker('cry-Flip Side') once per scoring pass instead
# of scanning joker+consumeable arrays on every calculate_joker invocation.
# Sets G._cry_flip_side_active at scoring entry (overrides.lua) and clears it on
# exit; calculate.lua checks the flag instead of calling find_joker each time.
apply_cryptid_flip_side_cache() {
    local calc="$1"
    local over="$2"
    if [[ ! -f "$calc" ]] || [[ ! -f "$over" ]]; then
        log_warn "Cryptid calculate.lua or overrides.lua not found, skipping flip-side cache"
        return 0
    fi
    if grep -q "CRY_FLIP_SIDE_CACHED" "$calc"; then
        log_info "Cryptid flip-side cache already applied"
        return 0
    fi
    # Replace per-call find_joker scan with cached flag check in calculate.lua.
    sed -i 's|^\t\tnext(find_joker("cry-Flip Side"))$|\t\tG._cry_flip_side_active -- CRY_FLIP_SIDE_CACHED|' "$calc"
    # Set/clear the cache around the scoring pass in overrides.lua.
    sed -i 's|^\tgfep(e)$|\tG._cry_flip_side_active = next(find_joker("cry-Flip Side"))\n\tgfep(e)\n\tG._cry_flip_side_active = nil|' "$over"
    if grep -q "CRY_FLIP_SIDE_CACHED" "$calc" && grep -q "_cry_flip_side_active" "$over"; then
        log_success "Cryptid flip-side cache applied (find_joker scan → per-pass flag)"
    else
        log_warn "Cryptid flip-side cache did not fully apply — check calculate.lua and overrides.lua"
    fi
}

# Add an in-game FPS counter toggle. The base game only draws FPS behind a debug
# flag that never runs in release builds. This adds a simple counter gated on
# G.SETTINGS.show_fps, plus a "Show FPS" toggle in Settings > Game.
apply_fps_toggle() {
    local game_lua="$1"
    local ui_file="$2"
    if [[ ! -f "$game_lua" || ! -f "$ui_file" ]]; then
        log_warn "game.lua / UI_definitions.lua not found, skipping FPS toggle"
        return 0
    fi
    if grep -q "show_fps" "$game_lua"; then
        log_info "FPS toggle already applied"
        return 0
    fi
    sed -i "s|    timer_checkpoint('canvas', 'draw')|    timer_checkpoint('canvas', 'draw')\n    if G.SETTINGS.show_fps then love.graphics.push('all'); love.graphics.origin(); love.graphics.setColor(0,1,0,1); love.graphics.print('FPS: '..love.timer.getFPS(), 15, 15); love.graphics.pop() end|" "$game_lua"
    sed -i "s|create_toggle({label = localize('b_reduced_motion'), ref_table = G.SETTINGS, ref_value = 'reduced_motion'}),|create_toggle({label = localize('b_reduced_motion'), ref_table = G.SETTINGS, ref_value = 'reduced_motion'}),\n      create_toggle({label = \"Show FPS\", ref_table = G.SETTINGS, ref_value = 'show_fps'}),|" "$ui_file"
    if grep -q "show_fps" "$game_lua" && grep -q "show_fps" "$ui_file"; then
        log_success "FPS toggle added (Settings > Game > Show FPS)"
    else
        log_warn "FPS toggle did not fully apply — check anchors"
    fi
}

# Enable the game's built-in per-frame perf overlay (the timer_checkpoint
# breakdown: per-subsystem ms + trend bars + GC) behind a "Debug Overlay" toggle.
# The base game gates collection on F_ENABLE_PERF_OVERLAY and the draw on debug
# flags that are off in release — retarget both to G.SETTINGS.perf_mode, and force
# screen-space coords (origin) so it isn't mispositioned by the active transform.
apply_debug_overlay() {
    local game_lua="$1"
    local misc="$2"
    local ui_file="$3"
    if [[ ! -f "$game_lua" || ! -f "$misc" || ! -f "$ui_file" ]]; then
        log_warn "files for debug overlay not found, skipping"
        return 0
    fi
    if grep -q "Debug Overlay" "$ui_file"; then
        log_info "Debug overlay already applied"
        return 0
    fi
    sed -i 's|if not G.F_ENABLE_PERF_OVERLAY then return end|if not G.SETTINGS.perf_mode then return end|' "$misc"
    sed -i 's|if not _RELEASE_MODE and G.DEBUG and not G.video_control and G.F_VERBOSE then|if G.SETTINGS.perf_mode then|' "$game_lua"
    sed -i 's|        love.graphics.setColor(0, 1, 1,1)|        love.graphics.origin()\n        love.graphics.setColor(0, 1, 1,1)|' "$game_lua"
    sed -i "s|create_toggle({label = \"Show FPS\", ref_table = G.SETTINGS, ref_value = 'show_fps'}),|create_toggle({label = \"Show FPS\", ref_table = G.SETTINGS, ref_value = 'show_fps'}),\n      create_toggle({label = \"Debug Overlay\", ref_table = G.SETTINGS, ref_value = 'perf_mode'}),|" "$ui_file"
    if grep -q "if G.SETTINGS.perf_mode then" "$game_lua" && grep -q "Debug Overlay" "$ui_file"; then
        log_success "Debug overlay added (Settings > Game > Debug Overlay)"
    else
        log_warn "Debug overlay did not fully apply — check anchors"
    fi
}

# Ensure every shipped shader file ends with a trailing newline. Cryptid's
# blur.fs ends in '#endif' with no final newline; Mali and llvmpipe tolerate
# it, but stricter GLSL translators (Android-emulator/ANGLE) reject the file
# with "unexpected end of file found in directive" and the game crash-loops
# at shader load. A directive at EOF without a newline is invalid GLSL —
# normalize all .fs/.vs we ship rather than special-casing one file.
apply_shader_eof_newlines() {
    local root="$1"
    local fixed=0
    while IFS= read -r -d '' f; do
        if [[ -s "$f" && -n "$(tail -c1 "$f")" ]]; then
            echo >> "$f"
            fixed=$((fixed+1))
        fi
    done < <(find "$root" -name '*.fs' -print0 -o -name '*.vs' -print0 2>/dev/null)
    log_success "Shader EOF newlines normalized under $root ($fixed fixed)"
}

# Talisman runs hand-scoring in a coroutine and opens a dimmed "Abort" overlay
# while it runs — but it opens the dim the instant scoring starts, so a fast hand
# (instant scoring, any chip scale) flashes the dim on for ~1 frame: the dark
# flicker on every hand. Gate the dim on elapsed scoring time so it only appears
# for genuinely-long scoring (>0.3s, where Abort is actually useful). The scoring
# coroutine still resumes every update regardless, so scoring is unaffected.
#
# Target: Mods/Talisman/talisman.lua — the single live harness now that
# patch_main_lua.py step 10 removes main.lua's baked duplicate of it. Applied to
# BOTH the game.love-embedded copy and the phone-transfer copy: the loader
# enumerates mods from the embedded archive (verified: stale save-dir mods don't
# load), but LÖVE save-dir reads can shadow same-path files, so we patch both
# shipped copies rather than bet on the read path.
apply_talisman_dim_fix() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "talisman.lua not found at $f, skipping Talisman dim fix"
        return 0
    fi
    if grep -q "G.SCORING_START" "$f"; then
        log_info "Talisman dim fix already applied ($f)"
        return 0
    fi
    sed -i 's|G.SCORING_COROUTINE = coroutine.create(oldplay)|G.SCORING_COROUTINE = coroutine.create(oldplay)\n      G.SCORING_START = love.timer.getTime() -- TALISMAN_DIM_GATE|' "$f"
    sed -i 's|              if not G.OVERLAY_MENU then|              if not G.OVERLAY_MENU and love.timer.getTime() - (G.SCORING_START or love.timer.getTime()) > 0.3 then -- TALISMAN_DIM_GATE|' "$f"
    if grep -q "G.SCORING_START or love.timer.getTime()) > 0.3" "$f"; then
        log_success "Talisman scoring-dim fix applied (no dim flicker on fast hands): $f"
    else
        log_warn "Talisman dim fix did not fully apply — check $f"
    fi
}

# The lovely patch for cursor_down.uptime (sticky-fingers controller.toml) injected
# the assignment *after* the L_cursor_queue flush line, i.e. outside L_cursor_press.
# That means if L_cursor_press returned early (locked during a screen wipe or menu
# transition) cursor_down.uptime was still stamped, making cursor_down.duration wrong
# on the next real press (duration = UPTIME - stale_uptime => falsely short).
# Fix: move the assignment inside L_cursor_press, immediately after cursor_down.time,
# so it only stamps when the press actually lands.
apply_cursor_down_uptime_fix() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "controller.lua not found, skipping cursor_down.uptime fix"
        return 0
    fi
    # Idempotency: if the assignment is already inside L_cursor_press (alongside
    # cursor_down.time = G.TIMERS.TOTAL) the marker tag below will be present.
    if grep -q "CURSOR_DOWN_UPTIME_FIX" "$f"; then
        log_info "cursor_down.uptime fix already applied"
        return 0
    fi
    # Remove any existing cursor_down.uptime line (with or without leading whitespace)
    # so the insert below produces exactly one correctly-marked copy inside L_cursor_press.
    # Handles two source variants: lovely placed it outside the function (bare, no indent),
    # or already inside the function (4-space indent). Both are deleted; the insert re-adds
    # exactly one copy with the CURSOR_DOWN_UPTIME_FIX marker.
    sed -i '/^\s*self\.cursor_down\.uptime = G\.TIMERS\.UPTIME[[:space:]]*$/d' "$f"
    # Insert inside L_cursor_press, right after cursor_down.time.
    sed -i 's|    self\.cursor_down\.time = G\.TIMERS\.TOTAL$|    self.cursor_down.time = G.TIMERS.TOTAL\n    self.cursor_down.uptime = G.TIMERS.UPTIME -- CURSOR_DOWN_UPTIME_FIX|' "$f"
    if grep -q "CURSOR_DOWN_UPTIME_FIX" "$f"; then
        log_success "cursor_down.uptime fix applied (uptime now set inside L_cursor_press)"
    else
        log_warn "cursor_down.uptime fix did not match — check controller.lua"
    fi
}

# On touch, the card description popup (the "hover") only shows while a finger is
# held down — the controller force-clears it the instant you release
# (self.HID.touch and not self.is_cursor_down). The touch cursor stays over the
# card after release, so dropping that release-clear clause makes the description
# persist after you let go (and it updates when you tap another card). Desktop is
# unaffected — that clause was only ever true in touch mode.
apply_tap_description_persist() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "controller.lua not found, skipping tap-description persist"
        return 0
    fi
    if grep -q "TAP_DESC_PERSIST" "$f"; then
        log_info "Tap-description persist already applied"
        return 0
    fi
    # persist: don't clear the hover on touch release
    sed -i 's|elseif (self.cursor_hover.target == nil or (self.HID.touch and not self.is_cursor_down)) and self.hovering.target then|elseif (self.cursor_hover.target == nil) and self.hovering.target then -- TAP_DESC_PERSIST|' "$f"
    # hold-gate: on touch, a hand/playing card only shows its description after a
    # deliberate hold (>0.2s, past the tap/select boundary) — a quick tap selects
    # without ever flashing the description. Jokers/etc. still show immediately.
    sed -i 's|if self.cursor_hover.target and self.cursor_hover.target.states.hover.can and (not self.HID.touch or self.is_cursor_down) then|if self.cursor_hover.target and self.cursor_hover.target.states.hover.can and (not self.HID.touch or self.is_cursor_down) and not (self.HID.touch and self.cursor_hover.target.area == G.hand and (self.cursor_down.duration or 0) < 0.2) then -- TAP_DESC_HOLDGATE|' "$f"
    # tap behaviour by card type: hand/playing cards quick-tap = select only (no
    # description; description is hold-only via the persist above, and any tap
    # dismisses it). Jokers/consumables/shop = tap toggles the persistent desc.
    sed -i 's|                    self.touch_control.s_tap.handled = false|                    self.touch_control.s_tap.handled = false\n                    if self.cursor_down.target.area == G.hand then\n                        if self.hovering.target then self.hovering.target.states.hover.is = false end\n                        self.hovering.target = nil; self.shown_desc = nil\n                    elseif self.cursor_down.target == self.shown_desc then\n                        if self.hovering.target then self.hovering.target.states.hover.is = false end\n                        self.hovering.target = nil; self.shown_desc = nil\n                    else self.shown_desc = self.cursor_down.target end -- TAP_DESC_TOGGLE|' "$f"
    # no-warp: a persisted hover target (description shown after the finger left
    # the card) kept states.hover.is = true, and the card draw aims its 3D mesh
    # tilt (sprite.lua 'mouse_screen_pos' = tilt_var.mx/my) at the LIVE cursor
    # whenever hover.is is true (card.lua:4921 / SMODS card_draw.lua:88). So the
    # old card skewed toward wherever the finger went next — the warp/stretch.
    # Fix at the source: when the finger is no longer physically on the hovering
    # target (touch released, or moved to another card), drop its hover.is so the
    # tilt falls to the ambient branch (orbits its own centre, no warp). The
    # description popup is tied to hovering.target — NOT hover.is — so it stays;
    # hover.is's only other card effects are a benign +5% zoom and collision buffer.
    sed -i 's|    --The object being hovered over|    if self.HID.touch and self.hovering.target and not (self.is_cursor_down and self.cursor_hover.target == self.hovering.target) then self.hovering.target.states.hover.is = false end -- TAP_DESC_RELAX\n    --The object being hovered over|' "$f"
    # hold-persist: hand cards are draggable (reorder), so a stationary hold was
    # treated as a degenerate drag — on release the drag-release path calls
    # stop_hover() and nils hovering.target, destroying the description the hold
    # just revealed ("doesn't stay"). A touch hold that never travelled past the
    # click threshold is not a reorder; skip the drag-release path so its
    # description persists. Real drags (travel >= MIN_CLICK_DIST) still reorder.
    sed -i 's|            elseif self.dragging.prev_target then |            elseif self.dragging.prev_target and not (self.HID.touch and (self.cursor_down.distance or 0) < G.MIN_CLICK_DIST) then -- TAP_DESC_HOLD_NODRAG |' "$f"
    if grep -q "TAP_DESC_PERSIST" "$f" && grep -q "TAP_DESC_TOGGLE" "$f" && grep -q "TAP_DESC_RELAX" "$f" && grep -q "TAP_DESC_HOLD_NODRAG" "$f"; then
        log_success "Tap-description persist + toggle + no-warp + hold-persist applied"
    else
        log_warn "Tap-description fix did not fully match — check controller.lua"
    fi
}

# Slide-to-select: drag a finger from empty space across the hand to highlight
# several cards in one gesture (adapted from BalatroMobileLikeDragging's
# dragSelectActive mechanism). Designed to coexist with Sticky Fingers — it
# activates ONLY when a touch starts on empty space (no card under the finger)
# with nothing being dragged, so single-tap select and card-reorder drag are
# untouched. Gated on G.SETTINGS.enable_drag_select (Settings > Game), default on.
apply_drag_select() {
    local ctrl="$1"
    local globals="$2"
    local ui_file="$3"
    if [[ ! -f "$ctrl" ]]; then
        log_warn "controller.lua not found, skipping drag-select"
        return 0
    fi
    if grep -q "DRAG_SELECT_LOOP" "$ctrl"; then
        log_info "Drag-select already applied"
        return 0
    fi
    # 1) default the setting on (seed into the SETTINGS table in globals.lua)
    sed -i 's|    self.SETTINGS = {|    self.SETTINGS = {\n        enable_drag_select = true, -- DRAG_SELECT_DEFAULT|' "$globals"
    # 2) init the drag-select state alongside cursor_down
    sed -i 's|self.cursor_down = {T = {x=0, y=0}, target = nil, time = 0, handled = true}|self.cursor_down = {T = {x=0, y=0}, target = nil, time = 0, handled = true}\nself.dragSelectActive = {active = false, mode = nil} -- DRAG_SELECT_INIT|' "$ctrl"
    # 3) activate on a touch that starts on empty space with nothing being dragged
    sed -i 's|^        self.cursor_down.handled = true$|        if self.HID.touch and not self.dragging.target and #self.collision_list == 0 and G.SETTINGS.enable_drag_select then self.dragSelectActive.active = true end -- DRAG_SELECT_ACTIVATE\n        self.cursor_down.handled = true|' "$ctrl"
    # 4) reset on touch release
    sed -i 's|    if not self.cursor_up.handled then |    if not self.cursor_up.handled then \n        self.dragSelectActive.active = false; self.dragSelectActive.mode = nil -- DRAG_SELECT_RESET|' "$ctrl"
    # 5) per-frame while active: highlight/unhighlight the closest hand card under
    #    the finger. The first card touched sets the mode (select vs deselect) so a
    #    sweep stays consistent; the hand's 5-card limit is enforced by
    #    add_to_highlighted (over-limit cards just no-op).
    sed -i 's|    --Cursor is currently hovering over something|    if self.HID.touch and self.dragSelectActive.active then -- DRAG_SELECT_LOOP\n        local distance = math.huge; local closest = nil\n        for _, v in ipairs(self.collision_list) do\n            local cur_distance = Vector_Dist(self.cursor_hover.T, v.T)\n            if v.area ~= nil and v.area.config.type == "hand" and v.states.hover.can and (not v.states.drag.is) and (v ~= self.dragging.prev_target) and cur_distance < distance then\n                closest = v; distance = cur_distance\n            end\n        end\n        if closest and (not self.dragSelectActive.mode or self.dragSelectActive.mode == "select" and not closest.highlighted or self.dragSelectActive.mode == "deselect" and closest.highlighted) then\n            if closest.highlighted then closest.area:remove_from_highlighted(closest); self.dragSelectActive.mode = "deselect"\n            else closest.area:add_to_highlighted(closest); self.dragSelectActive.mode = "select" end\n        end\n    end\n    --Cursor is currently hovering over something|' "$ctrl"
    # 6) toggle in Settings > Game (after the Debug Overlay toggle)
    sed -i "s|create_toggle({label = \"Debug Overlay\", ref_table = G.SETTINGS, ref_value = 'perf_mode'}),|create_toggle({label = \"Debug Overlay\", ref_table = G.SETTINGS, ref_value = 'perf_mode'}),\n      create_toggle({label = \"Slide to select cards\", ref_table = G.SETTINGS, ref_value = 'enable_drag_select'}),|" "$ui_file"
    if grep -q "DRAG_SELECT_LOOP" "$ctrl" && grep -q "DRAG_SELECT_ACTIVATE" "$ctrl" && grep -q "enable_drag_select" "$ui_file"; then
        log_success "Drag-select (slide to select) applied"
    else
        log_warn "Drag-select did not fully match — check controller.lua/globals.lua/UI_definitions.lua"
    fi
}

# Card shadow height: when a card is hovered the card lifts visually (+5% scale
# via move_scale hover.is branch) but shadow_height stays at the idle 0.1,
# leaving the shadow floor-parked under the risen card. drag.is gets 0.35;
# hover.is gets nothing. Since drag and hover are mutually exclusive on the same
# card (controller.lua:381,399), and hover lifts half as much as drag (+0.05 vs
# +0.10 scale), the proportional shadow_height for hover is 0.2 (half of 0.35).
apply_shadow_height_fix() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "card.lua not found, skipping shadow_height fix"
        return 0
    fi
    if grep -q "HOVER_SHADOW_HEIGHT" "$f"; then
        log_info "Shadow height hover fix already applied"
        return 0
    fi
    sed -i 's|and 0.35) or (self.area and self.area.config.type == .title_2.) and 0.04 or 0.1)|and 0.35) or self.states.hover.is and 0.2 or (self.area and self.area.config.type == '"'"'title_2'"'"') and 0.04 or 0.1) -- HOVER_SHADOW_HEIGHT|' "$f"
    if grep -q "HOVER_SHADOW_HEIGHT" "$f"; then
        log_success "Shadow height hover fix applied (hover.is -> 0.2, proportional to drag 0.35)"
    else
        log_warn "Shadow height hover fix did not match — check card.lua shadow_height line"
    fi
}

# Eliminate redundant OmegaNum allocations at the 22 to_big call sites in card.lua.
# Every operand at these sites is a plain Lua number (joker ability fields like
# x_mult, mult, t_mult, t_chips, dollars — all initialized as plain numbers and
# never assigned an OmegaNum value). Replacing to_big(a) OP to_big(b) with a OP b
# directly avoids two OmegaNum table allocations per comparison.
# Line 3593 divides G.GAME.chips (plain number) by blind.chips (plain number).
# Line 3328 (Ramen) subtracts plain-number fields before comparing.
# Audit: all 22 sites verified safe — no OmegaNum accumulator operands in card.lua.
apply_card_to_big_elim() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "card.lua not found, skipping to_big elimination"
        return 0
    fi
    if grep -q "CARD_TO_BIG_ELIM" "$f"; then
        log_info "card.lua to_big elimination already applied"
        return 0
    fi
    # Line 2101: extra < 1 guard
    sed -i 's|if to_big(self\.ability\.extra) < to_big(1) then self\.ability\.extra = 1 end|if self.ability.extra < 1 then self.ability.extra = 1 end -- CARD_TO_BIG_ELIM|' "$f"
    # Line 3328: Ramen x_mult - extra <= 1
    sed -i 's|if to_big(self\.ability\.x_mult) - to_big(self\.ability\.extra) <= to_big(1) then|if self.ability.x_mult - self.ability.extra <= 1 then|' "$f"
    # Line 3593: Mr. Bones chips/blind ratio
    sed -i 's|to_big(G\.GAME\.chips)/G\.GAME\.blind\.chips >= to_big(0\.25)|G.GAME.chips / G.GAME.blind.chips >= 0.25|' "$f"
    # Lines 3462, 3571, 4120, 4202: x_mult > 1
    sed -i 's|to_big(self\.ability\.x_mult) > to_big(1)|self.ability.x_mult > 1|g' "$f"
    # Lines 4209, 4215: t_mult / t_chips > 0
    sed -i 's|to_big(self\.ability\.t_mult) > to_big(0)|self.ability.t_mult > 0|g' "$f"
    sed -i 's|to_big(self\.ability\.t_chips) > to_big(0)|self.ability.t_chips > 0|g' "$f"
    # Lines 4285, 4497, 4509, 4515, 4521, 4527, 4533, 4557: mult > 0
    sed -i 's|to_big(self\.ability\.mult) > to_big(0)|self.ability.mult > 0|g' "$f"
    # Line 4292: dollars <= extra
    sed -i 's|to_big(G\.GAME\.dollars) <= to_big(self\.ability\.extra)|G.GAME.dollars <= self.ability.extra|' "$f"
    # Line 4403: extra.chips > 0
    sed -i 's|to_big(self\.ability\.extra\.chips) > to_big(0)|self.ability.extra.chips > 0|g' "$f"
    # Line 4459: dollars + buffer > 0
    sed -i 's|to_big(G\.GAME\.dollars + (G\.GAME\.dollar_buffer or 0)) > to_big(0)|G.GAME.dollars + (G.GAME.dollar_buffer or 0) > 0|' "$f"
    # Line 4569: Bootstraps floor division >= 1
    sed -i 's|to_big(math\.floor((G\.GAME\.dollars + (G\.GAME\.dollar_buffer or 0))/self\.ability\.extra\.dollars)) >= to_big(1)|math.floor((G.GAME.dollars + (G.GAME.dollar_buffer or 0))/self.ability.extra.dollars) >= 1|' "$f"
    # Line 4575: caino_xmult > 1
    sed -i 's|to_big(self\.ability\.caino_xmult) > to_big(1)|self.ability.caino_xmult > 1|' "$f"
    if grep -q "CARD_TO_BIG_ELIM" "$f"; then
        log_success "card.lua to_big elimination applied (22 OmegaNum alloc pairs → plain Lua comparisons)"
    else
        log_warn "card.lua to_big elimination did not apply the marker — check card.lua"
    fi
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
# Hide the Video settings tab on Android (monitor/resolution/vsync are desktop-only
# and do nothing on a phone). The separate Graphics tab is left visible.
apply_android_video_settings_fix() {
    local ui_file="$1"

    if [[ ! -f "$ui_file" ]]; then
        log_warn "UI_definitions.lua not found, skipping video settings fix"
        return
    fi

    if grep -q "Android video settings hidden" "$ui_file"; then
        log_info "Video settings already patched for Android"
        return
    fi

    log_info "Patching UI_definitions.lua to hide video settings on Android..."

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

    # Same dim-gate as the embedded copy — save-dir reads can shadow the archive
    apply_talisman_dim_fix "$transfer_dir/Mods/Talisman/talisman.lua"
    apply_shader_eof_newlines "$transfer_dir"

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

    # Launch app — force-stop and wait for the old instance to die first, so the
    # new build is what loads and we don't hit LÖVE's "filesystem already
    # initialized" abort from a second instance racing the first.
    log_info "Launching app..."
    adb shell am force-stop "$INSTALLED_PACKAGE_ID"
    for _ in $(seq 1 20); do
        adb shell pidof "$INSTALLED_PACKAGE_ID" >/dev/null 2>&1 || break
        sleep 0.3
    done
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
