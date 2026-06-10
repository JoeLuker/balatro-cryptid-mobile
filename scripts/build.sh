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

# Apply all Mods-layer patches to a given Mods root directory.
# Called by both build_apk (embedded game copy) and prepare_transfer (save-dir
# shadow copy) so the two trees always stay in sync.  Adding a new Mods patch
# means adding it here once — not in two places.
patch_mods_dir() {
    local mods_dir="$1"   # absolute path to the Mods/ directory to patch

    apply_talisman_dim_fix "$mods_dir/Talisman/talisman.lua"
    apply_shader_eof_newlines "$(dirname "$mods_dir")"
    apply_blur_shader_reorder "$mods_dir/Cryptid/assets/shaders/blur.fs"
    apply_glitch_shader_fix   "$mods_dir/Cryptid/assets/shaders/glitched.fs"
    apply_glitched_b_fix      "$mods_dir/Cryptid/assets/shaders/glitched_b.fs"
    apply_cryptid_dead_copy_fix   "$mods_dir/Cryptid/lib/calculate.lua"
    apply_cryptid_flip_side_cache "$mods_dir/Cryptid/lib/calculate.lua" "$mods_dir/Cryptid/lib/overrides.lua"
    apply_cryptid_events_guard    "$mods_dir/Cryptid/lib/calculate.lua"
    apply_talisman_gc_dead_block  "$mods_dir/Talisman/talisman.lua"
    apply_talisman_calc_counter   "$mods_dir/Talisman/talisman.lua"
    apply_cryptid_to_big_elim \
        "$mods_dir/Cryptid/items/epic.lua" \
        "$mods_dir/Cryptid/items/exotic.lua" \
        "$mods_dir/Cryptid/items/m.lua"
    apply_hand_level_no_recalc "$mods_dir/Steamodded/src/ui.lua"
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
    apply_mobile_graphics_defaults "$game_dir/globals.lua"
    # Hide only the Video tab (monitor/resolution/vsync — desktop-only, does nothing
    # on mobile). The Graphics tab (texture scaling / CRT / bloom / shadows) stays
    # visible so quality is tunable on device.
    apply_android_video_settings_fix "$game_dir/functions/UI_definitions.lua"
    apply_fps_toggle "$game_dir/game.lua" "$game_dir/functions/UI_definitions.lua"
    apply_debug_overlay "$game_dir/game.lua" "$game_dir/functions/misc_functions.lua" "$game_dir/functions/UI_definitions.lua"
    # Use Python patcher for main.lua (more reliable than sed for complex patches)
    python3 "$SCRIPT_DIR/patch_main_lua.py" "$game_dir/main.lua"
    patch_mods_dir "$game_dir/Mods"
    apply_shake_trig_guard "$game_dir/functions/common_events.lua"
    apply_tap_description_persist "$game_dir/engine/controller.lua"
    apply_cursor_down_uptime_fix "$game_dir/engine/controller.lua"
    apply_drag_select "$game_dir/engine/controller.lua" "$game_dir/globals.lua" "$game_dir/functions/UI_definitions.lua"
    apply_shadow_height_fix "$game_dir/card.lua"
    apply_card_to_big_elim "$game_dir/card.lua"
    apply_scoring_loop_cache "$game_dir/functions/state_events.lua"
    apply_ctx_table_hoist "$game_dir/functions/state_events.lua"
    apply_hand_update_text_dedup "$game_dir/functions/button_callbacks.lua"
    apply_lvl_prefix_cache "$game_dir/functions/common_events.lua"
    apply_parse_highlighted_lean "$game_dir/cardarea.lua"
    apply_card_eval_config_elide "$game_dir/functions/common_events.lua"
    apply_get_x_same_lean "$game_dir/functions/misc_functions.lua"

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

# Hot-path fix 4: guard both SMODS.Events iteration loops in SMODS.calculate_context
# behind next(G.GAME.events). The table normally has 11 entries (choco0-choco10),
# all inactive during standard scoring. Without the guard every calculate_context
# call iterates all 11 entries twice (pre + post), even when none are active.
# The guard short-circuits both loops in O(1) for the common case.
apply_cryptid_events_guard() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "Cryptid calculate.lua not found, skipping events guard"
        return 0
    fi
    if grep -q "CRY_EVENTS_GUARDED" "$f"; then
        log_info "Cryptid events guard already applied"
        return 0
    fi
    python3 - "$f" <<'PYEOF'
import sys
path = sys.argv[1]
text = open(path).read()

TAB = "\t"

pre_loop = (
    TAB + "for k, v in pairs(SMODS.Events) do\n"
    + TAB*2 + "if G.GAME.events and G.GAME.events[k] then\n"
    + TAB*3 + "context.pre_jokers = true\n"
    + TAB*3 + "v:calculate(context)\n"
    + TAB*3 + "context.pre_jokers = nil\n"
    + TAB*2 + "end\n"
    + TAB + "end\n"
)
pre_guarded = (
    TAB + "if G.GAME.events and next(G.GAME.events) then -- CRY_EVENTS_GUARDED\n"
    + TAB*2 + "for k, v in pairs(SMODS.Events) do\n"
    + TAB*3 + "if G.GAME.events[k] then\n"
    + TAB*4 + "context.pre_jokers = true\n"
    + TAB*4 + "v:calculate(context)\n"
    + TAB*4 + "context.pre_jokers = nil\n"
    + TAB*3 + "end\n"
    + TAB*2 + "end\n"
    + TAB + "end\n"
)

post_loop = (
    TAB + "for k, v in pairs(SMODS.Events) do\n"
    + TAB*2 + "if G.GAME.events and G.GAME.events[k] then\n"
    + TAB*3 + "context.post_jokers = true\n"
    + TAB*3 + "v:calculate(context)\n"
    + TAB*3 + "context.post_jokers = nil\n"
    + TAB*2 + "end\n"
    + TAB + "end\n"
)
post_guarded = (
    TAB + "if G.GAME.events and next(G.GAME.events) then\n"
    + TAB*2 + "for k, v in pairs(SMODS.Events) do\n"
    + TAB*3 + "if G.GAME.events[k] then\n"
    + TAB*4 + "context.post_jokers = true\n"
    + TAB*4 + "v:calculate(context)\n"
    + TAB*4 + "context.post_jokers = nil\n"
    + TAB*3 + "end\n"
    + TAB*2 + "end\n"
    + TAB + "end\n"
)

if pre_loop not in text:
    print("ERROR: pre_jokers loop anchor not found in " + path, file=sys.stderr)
    sys.exit(1)
if post_loop not in text:
    print("ERROR: post_jokers loop anchor not found in " + path, file=sys.stderr)
    sys.exit(1)

text = text.replace(pre_loop, pre_guarded, 1)
text = text.replace(post_loop, post_guarded, 1)
open(path, 'w').write(text)
print("Cryptid events guard applied")
PYEOF
    if grep -q "CRY_EVENTS_GUARDED" "$f"; then
        log_success "Cryptid events guard applied (SMODS.Events loops skip-guarded via next(G.GAME.events))"
    else
        log_warn "Cryptid events guard did not apply — check calculate.lua anchors"
    fi
}

# Hot-path fix 3: guard the 6 math.sin calls in update_canvas_juice behind a
# shake_amt > 0 check.  When screenshake is at its default level (<=30) shake_amt
# evaluates to 0 every frame and all six trig results are immediately multiplied
# away.  On a non-shaking frame this saves 6 ARM math.sin calls (~60-90ns) and
# collapses the three ROOM transform assignments to their trivial zero forms.
# On a shaking frame (shake_amt > 0) the code runs identically to the original.
apply_shake_trig_guard() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "common_events.lua not found, skipping shake trig guard"
        return 0
    fi
    if grep -q "SHAKE_TRIG_GUARDED" "$f"; then
        log_info "Shake trig guard already applied"
        return 0
    fi
    python3 - "$f" <<'PYEOF'
import sys, re
path = sys.argv[1]
text = open(path).read()

old = (
    "    G.ROOM.T.r = (0.001*math.sin(0.3*G.TIMERS.REAL)+ 0.002*(G.ROOM.jiggle)*math.sin(39.913*G.TIMERS.REAL))*shake_amt\n"
    "    G.ROOM.T.x = G.ROOM_ORIG.x + (shake_amt)*(0.015*math.sin(0.913*G.TIMERS.REAL)  + 0.01*(G.ROOM.jiggle*shake_amt)*math.sin(19.913*G.TIMERS.REAL) + (G.ARGS.eased_cursor_pos.x - 0.5*(G.ROOM.T.w + G.ROOM_ORIG.x))*0.01)\n"
    "    G.ROOM.T.y = G.ROOM_ORIG.y + (shake_amt)*(0.015*math.sin(0.952*G.TIMERS.REAL)  + 0.01*(G.ROOM.jiggle*shake_amt)*math.sin(21.913*G.TIMERS.REAL) + (G.ARGS.eased_cursor_pos.y - 0.5*(G.ROOM.T.h + G.ROOM_ORIG.y))*0.01)"
)
new = (
    "    -- SHAKE_TRIG_GUARDED: skip 6 math.sin calls when shake_amt==0 (default state)\n"
    "    if shake_amt > 0 then\n"
    "        G.ROOM.T.r = (0.001*math.sin(0.3*G.TIMERS.REAL)+ 0.002*(G.ROOM.jiggle)*math.sin(39.913*G.TIMERS.REAL))*shake_amt\n"
    "        G.ROOM.T.x = G.ROOM_ORIG.x + (shake_amt)*(0.015*math.sin(0.913*G.TIMERS.REAL)  + 0.01*(G.ROOM.jiggle*shake_amt)*math.sin(19.913*G.TIMERS.REAL) + (G.ARGS.eased_cursor_pos.x - 0.5*(G.ROOM.T.w + G.ROOM_ORIG.x))*0.01)\n"
    "        G.ROOM.T.y = G.ROOM_ORIG.y + (shake_amt)*(0.015*math.sin(0.952*G.TIMERS.REAL)  + 0.01*(G.ROOM.jiggle*shake_amt)*math.sin(21.913*G.TIMERS.REAL) + (G.ARGS.eased_cursor_pos.y - 0.5*(G.ROOM.T.h + G.ROOM_ORIG.y))*0.01)\n"
    "    else\n"
    "        G.ROOM.T.r = 0\n"
    "        G.ROOM.T.x = G.ROOM_ORIG.x\n"
    "        G.ROOM.T.y = G.ROOM_ORIG.y\n"
    "    end"
)

if old not in text:
    print("ERROR: shake trig anchor not found in " + path, file=sys.stderr)
    sys.exit(1)

open(path, 'w').write(text.replace(old, new, 1))
print("Shake trig guard applied")
PYEOF
    if grep -q "SHAKE_TRIG_GUARDED" "$f"; then
        log_success "Shake trig guard applied (6 math.sin calls skipped when shake_amt==0)"
    else
        log_warn "Shake trig guard did not apply — check common_events.lua anchors"
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

# Cryptid's blur.fs is the only shipped shader structured as prototypes +
# effect() + helper definitions (every other shader, vanilla and Cryptid,
# defines helpers BEFORE use with no prototypes). The Android emulator's GLES
# translator miscompiles the prototype pattern in the vertex stage ("'hue(...)'
# function definition not found" at the call inside RGB) and the game
# crash-loops at shader load. Mali and Mesa tolerate it. Reorder the file to
# the convention the other 12 hue-shaders follow — helpers first, prototypes
# dropped — a pure, content-preserving restructure. Marker: BLUR_PROTO_REORDER.
apply_blur_shader_reorder() {
    local f="$1"
    [[ -f "$f" ]] || { log_warn "blur.fs not found at $f"; return 0; }
    if grep -q "BLUR_PROTO_REORDER" "$f"; then
        log_info "blur.fs reorder already applied ($f)"
        return 0
    fi
    python3 - "$f" <<'PYBLUR'
import sys
p = sys.argv[1]
lines = open(p).read().split('\n')
protos = {'vec4 RGB(vec4 c);', 'vec4 HSL(vec4 c);',
          'vec4 dissolve_mask(vec4 final_pixel, vec2 texture_coords, vec2 uv);'}
lines = [l for l in lines if l.strip() not in protos]
def find(pred):
    for i, l in enumerate(lines):
        if pred(l): return i
    raise SystemExit('anchor missing in ' + p)
i_eff = find(lambda l: l.startswith('vec4 effect('))
while i_eff > 0 and lines[i_eff-1].lstrip().startswith('//'):
    i_eff -= 1
i_hue = find(lambda l: l.startswith('number hue(number s'))
i_tail = find(lambda l: l.strip() == 'extern PRECISION vec2 mouse_screen_pos;')
assert i_eff < i_hue < i_tail, 'unexpected blur.fs layout'
out = lines[:i_eff] + lines[i_hue:i_tail] + lines[i_eff:i_hue] + lines[i_tail:]
out.append('// BLUR_PROTO_REORDER: helpers before effect(), prototypes removed (build.sh)')
open(p, 'w').write('\n'.join(out) + '\n')
PYBLUR
    if grep -q "BLUR_PROTO_REORDER" "$f" && \
       [[ $(grep -n "number hue(number s" "$f" | cut -d: -f1) -lt $(grep -n "^vec4 effect(" "$f" | cut -d: -f1) ]]; then
        log_success "blur.fs reordered for strict GLSL translators ($f)"
    else
        log_warn "blur.fs reorder did not verify — check $f"
    fi
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
    # touch_env: stable "this is a touch device" predicate. self.HID.touch is
    # re-classified on EVERY input event, so any single mis-classified event
    # (the istouch races fixed in main.lua) flips it to mouse and silently
    # disarms every per-frame touch patch below — RELAX stops clearing
    # hover.is and the stuck-tilt warp returns. Gates use (touch or touch_env)
    # so they never depend on event-classification timing. Vanilla HID.touch
    # reads are untouched; desktop unaffected (touch_env false off Android).
    sed -i "s|self.HID = {|self.HID = {\n    touch_env = love.system.getOS() == 'Android', -- HID_TOUCH_ENV|" "$f"
    # persist: don't clear the hover on touch release
    sed -i 's|elseif (self.cursor_hover.target == nil or (self.HID.touch and not self.is_cursor_down)) and self.hovering.target then|elseif (self.cursor_hover.target == nil) and self.hovering.target then -- TAP_DESC_PERSIST|' "$f"
    # hold-gate: on touch, a hand/playing card only shows its description after a
    # deliberate hold (>0.2s, past the tap/select boundary) — a quick tap selects
    # without ever flashing the description. Jokers/etc. still show immediately.
    sed -i 's|if self.cursor_hover.target and self.cursor_hover.target.states.hover.can and (not self.HID.touch or self.is_cursor_down) then|if self.cursor_hover.target and self.cursor_hover.target.states.hover.can and (not self.HID.touch or self.is_cursor_down) and not ((self.HID.touch or self.HID.touch_env) and self.cursor_hover.target.area == G.hand and (self.cursor_down.duration or 0) < 0.2) then -- TAP_DESC_HOLDGATE|' "$f"
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
    sed -i 's|    --The object being hovered over|    if (self.HID.touch or self.HID.touch_env) and self.hovering.target and not (self.is_cursor_down and self.cursor_hover.target == self.hovering.target) then self.hovering.target.states.hover.is = false end -- TAP_DESC_RELAX\n    --The object being hovered over|' "$f"
    # hold-persist: hand cards are draggable (reorder), so a stationary hold was
    # treated as a degenerate drag — on release the drag-release path calls
    # stop_hover() and nils hovering.target, destroying the description the hold
    # just revealed ("doesn't stay"). A touch hold that never travelled past the
    # click threshold is not a reorder; skip the drag-release path so its
    # description persists. Real drags (travel >= MIN_CLICK_DIST) still reorder.
    sed -i 's|            elseif self.dragging.prev_target then |            elseif self.dragging.prev_target and not ((self.HID.touch or self.HID.touch_env) and (self.cursor_down.distance or 0) < G.MIN_CLICK_DIST) then -- TAP_DESC_HOLD_NODRAG |' "$f"
    # drag-release unhover: the released_on path nils hovering.target after
    # stop_hover(), but stop_hover only removes the popup — it never clears
    # states.hover.is. The card is orphaned with hover.is stuck true, and its
    # 3D tilt stays anchored to the live cursor forever (THE warp). On desktop
    # the next mouse-over re-acquires and heals it; on touch nothing ever does.
    # Clear the flag in the same breath. (Found by test/controller/fuzz.lua;
    # minimal repro in test/controller/min-repro.lua.)
    sed -i 's|if self.dragging.prev_target == self.hovering.target then self.hovering.target:stop_hover();self.hovering.target = nil end|if self.dragging.prev_target == self.hovering.target then self.hovering.target.states.hover.is = false; self.hovering.target:stop_hover();self.hovering.target = nil end -- DRAG_RELEASE_UNHOVER|' "$f"
    if grep -q "TAP_DESC_PERSIST" "$f" && grep -q "TAP_DESC_TOGGLE" "$f" && grep -q "TAP_DESC_RELAX" "$f" && grep -q "TAP_DESC_HOLD_NODRAG" "$f" && grep -q "HID_TOUCH_ENV" "$f" && grep -q "DRAG_RELEASE_UNHOVER" "$f"; then
        log_success "Tap-description persist + toggle + no-warp + hold-persist + touch_env + drag-release-unhover applied"
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
    sed -i 's|^        self.cursor_down.handled = true$|        if (self.HID.touch or self.HID.touch_env) and not self.dragging.target and #self.collision_list == 0 and G.SETTINGS.enable_drag_select then self.dragSelectActive.active = true end -- DRAG_SELECT_ACTIVATE\n        self.cursor_down.handled = true|' "$ctrl"
    # 4) reset on touch release
    sed -i 's|    if not self.cursor_up.handled then |    if not self.cursor_up.handled then \n        self.dragSelectActive.active = false; self.dragSelectActive.mode = nil -- DRAG_SELECT_RESET|' "$ctrl"
    # 5) per-frame while active: highlight/unhighlight the closest hand card under
    #    the finger. The first card touched sets the mode (select vs deselect) so a
    #    sweep stays consistent; the hand's 5-card limit is enforced by
    #    add_to_highlighted (over-limit cards just no-op).
    sed -i 's|    --Cursor is currently hovering over something|    if (self.HID.touch or self.HID.touch_env) and self.dragSelectActive.active then -- DRAG_SELECT_LOOP\n        local distance = math.huge; local closest = nil\n        for _, v in ipairs(self.collision_list) do\n            local cur_distance = Vector_Dist(self.cursor_hover.T, v.T)\n            if v.area ~= nil and v.area.config.type == "hand" and v.states.hover.can and (not v.states.drag.is) and (v ~= self.dragging.prev_target) and cur_distance < distance then\n                closest = v; distance = cur_distance\n            end\n        end\n        if closest and (not self.dragSelectActive.mode or self.dragSelectActive.mode == "select" and not closest.highlighted or self.dragSelectActive.mode == "deselect" and closest.highlighted) then\n            if closest.highlighted then closest.area:remove_from_highlighted(closest); self.dragSelectActive.mode = "deselect"\n            else closest.area:add_to_highlighted(closest); self.dragSelectActive.mode = "select" end\n        end\n    end\n    --Cursor is currently hovering over something|' "$ctrl"
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

# Delete the dead collectgarbage("count") > 1GB guard in Talisman's love.update
# scoring loop. The threshold (1024*1024 KB = 1 GB) is never reachable on mobile.
# The block is dead code that also contradicts nuGC: nuGC calls
# collectgarbage("stop") after its budget, and this block would immediately restart
# a full collection if the threshold were ever hit. Remove both lines.
apply_talisman_gc_dead_block() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "talisman.lua not found, skipping GC dead block removal"
        return 0
    fi
    if grep -q "TAL_GC_DEAD_REMOVED" "$f"; then
        log_info "Talisman GC dead block already removed"
        return 0
    fi
    sed -i '/^        if collectgarbage("count") > 1024\*1024 then$/,/^        end$/{/^        if collectgarbage("count") > 1024\*1024 then$/d; /^          collectgarbage("collect")$/d; s/^        end$/        -- TAL_GC_DEAD_REMOVED/}' "$f"
    if grep -q "TAL_GC_DEAD_REMOVED" "$f"; then
        log_success "Talisman GC dead block removed (unreachable 1 GB threshold + contradicts nuGC)"
    else
        log_warn "Talisman GC dead block removal did not match — check talisman.lua love.update"
    fi
}

# Replace per-frame pairs(CARD_CALC_COUNTS) re-sum with an incremental counter
# (G.CURRENT_TOTAL_CALCS) maintained at the two insertion sites in the
# calculate_joker wrapper. Also replace number_format(G.CURRENT_CALC_TIME) in
# the overlay text with string.format("%.1f", ...) — the elapsed time is always
# a small float and does not need OmegaNum formatting.
apply_talisman_calc_counter() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "talisman.lua not found, skipping calc counter patch"
        return 0
    fi
    if grep -q "TAL_CALC_COUNTER" "$f"; then
        log_info "Talisman calc counter already applied"
        return 0
    fi
    # Reset counter alongside CARD_CALC_COUNTS at scoring start.
    sed -i 's|      G\.CARD_CALC_COUNTS = {} -- keys = cards, values = table containing numbers|      G.CARD_CALC_COUNTS = {} -- keys = cards, values = table containing numbers\n      G.CURRENT_TOTAL_CALCS = 0 -- TAL_CALC_COUNTER: incremental, avoids per-frame pairs() re-sum|' "$f"
    # Increment counter at the primary insertion site (new entry path).
    sed -i 's|      G\.CARD_CALC_COUNTS\[self\] = {1, 1}|      G.CARD_CALC_COUNTS[self] = {1, 1}\n      G.CURRENT_TOTAL_CALCS = (G.CURRENT_TOTAL_CALCS or 0) + 1|' "$f"
    # Increment counter at the existing-entry path.
    sed -i 's|      G\.CARD_CALC_COUNTS\[self\]\[1\] = G\.CARD_CALC_COUNTS\[self\]\[1\] + 1|      G.CARD_CALC_COUNTS[self][1] = G.CARD_CALC_COUNTS[self][1] + 1\n      G.CURRENT_TOTAL_CALCS = (G.CURRENT_TOTAL_CALCS or 0) + 1|' "$f"
    # Replace per-frame re-sum loop in overlay block with the counter.
    sed -i '/^                    local totalCalcs = 0$/,/^                    end$/{s/^                    local totalCalcs = 0$/                    local totalCalcs = G.CURRENT_TOTAL_CALCS or 0 -- TAL_CALC_COUNTER/; /^                    for i, v in pairs(G\.CARD_CALC_COUNTS) do$/d; /^                      totalCalcs = totalCalcs + v\[1\]$/d; /^                    end$/d}' "$f"
    # Replace per-frame re-sum loop at coroutine-end (different indentation).
    sed -i '/^              local totalCalcs = 0$/,/^              end$/{s/^              local totalCalcs = 0$/              local totalCalcs = G.CURRENT_TOTAL_CALCS or 0 -- TAL_CALC_COUNTER/; /^              for i, v in pairs(G\.CARD_CALC_COUNTS) do$/d; /^                totalCalcs = totalCalcs + v\[1\]$/d; /^              end$/d}' "$f"
    # Replace number_format(G.CURRENT_CALC_TIME) with plain string.format — bypasses to_big/OmegaNum.
    sed -i 's|tostring(number_format(G\.CURRENT_CALC_TIME))|string.format("%.1f", G.CURRENT_CALC_TIME or 0)|g' "$f"
    if grep -q "TAL_CALC_COUNTER" "$f"; then
        log_success "Talisman calc counter applied (incremental counter + string.format time display)"
    else
        log_warn "Talisman calc counter did not apply — check talisman.lua"
    fi
}

# Hot-path fix 5+8: cache get_card_areas('jokers') per scoring pass and hoist
# other_key computation outside the inner joker loop in evaluate_play_main.
#
# Fix 5 (SCORING_AREAS_CACHED): get_card_areas('jokers') builds a fresh
# {G.jokers, G.consumeables, G.vouchers} table every call. It is called once
# for the outer joker loop (line 821) and again on every outer-card iteration
# for the inner other_joker loop (line 847). Caching it once per scoring pass
# eliminates O(joker_count) redundant table allocations.
#
# Fix 8 (OTHER_KEY_HOISTED): other_key is determined entirely by _card.ability
# (the outer loop variable). It was re-computed from scratch on every inner
# _joker iteration — 3 conditional branches * joker_count times per outer card.
# Hoisting it before the inner loop reduces it to 3 branches per outer card.
# Same hoist applied to the individual loop, which repeated the same pattern.
apply_scoring_loop_cache() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "state_events.lua not found, skipping scoring loop cache"
        return 0
    fi
    if grep -q "SCORING_AREAS_CACHED" "$f"; then
        log_info "Scoring loop cache already applied"
        return 0
    fi
    python3 - "$f" <<'PYEOF'
import sys
path = sys.argv[1]
text = open(path).read()

# Fix 5a: insert _joker_areas cache before outer loop; change outer loop to use it
old1 = (
    "        percent = percent + percent_delta\n"
    "        for _, area in ipairs(SMODS.get_card_areas('jokers')) do for _, _card in ipairs(area.cards) do\n"
)
new1 = (
    "        percent = percent + percent_delta\n"
    "        local _joker_areas = SMODS.get_card_areas('jokers') -- SCORING_AREAS_CACHED\n"
    "        for _, area in ipairs(_joker_areas) do for _, _card in ipairs(area.cards) do\n"
)
if old1 not in text:
    print("ERROR: outer loop anchor not found in " + path, file=sys.stderr)
    sys.exit(1)
text = text.replace(old1, new1, 1)

# Fix 5b: inner other_joker loop uses cached _joker_areas
old2 = (
    "            -- Calculate context.other_joker effects\n"
    "            for _, _area in ipairs(SMODS.get_card_areas('jokers')) do\n"
)
new2 = (
    "            -- Calculate context.other_joker effects\n"
    "            for _, _area in ipairs(_joker_areas) do\n"
)
if old2 not in text:
    print("ERROR: inner other_joker loop anchor not found in " + path, file=sys.stderr)
    sys.exit(1)
text = text.replace(old2, new2, 1)

# Fix 8a: hoist other_key before inner joker loop (remove from per-_joker body)
old3 = (
    "            -- Calculate context.other_joker effects\n"
    "            for _, _area in ipairs(_joker_areas) do\n"
    "                for _, _joker in ipairs(_area.cards) do\n"
    "                    local other_key = 'other_unknown'\n"
    "                    if _card.ability.set == 'Joker' then other_key = 'other_joker' end\n"
    "                    if _card.ability.consumeable then other_key = 'other_consumeable' end\n"
    "                    if _card.ability.set == 'Voucher' then other_key = 'other_voucher' end\n"
    "                    -- TARGET: add context.other_something identifier to your cards\n"
)
new3 = (
    "            -- Calculate context.other_joker effects -- OTHER_KEY_HOISTED\n"
    "            local other_key = 'other_unknown'\n"
    "            if _card.ability.set == 'Joker' then other_key = 'other_joker' end\n"
    "            if _card.ability.consumeable then other_key = 'other_consumeable' end\n"
    "            if _card.ability.set == 'Voucher' then other_key = 'other_voucher' end\n"
    "            for _, _area in ipairs(_joker_areas) do\n"
    "                for _, _joker in ipairs(_area.cards) do\n"
    "                    -- TARGET: add context.other_something identifier to your cards\n"
)
if old3 not in text:
    print("ERROR: other_key-in-inner-loop anchor not found in " + path, file=sys.stderr)
    sys.exit(1)
text = text.replace(old3, new3, 1)

# Fix 8b: hoist other_key before individual loop (same other_key, no re-compute needed)
old4 = (
    "            for _, _area in ipairs(SMODS.get_card_areas('individual')) do\n"
    "                local other_key = 'other_unknown'\n"
    "                if _card.ability.set == 'Joker' then other_key = 'other_joker' end\n"
    "                if _card.ability.consumeable then other_key = 'other_consumeable' end\n"
    "                if _card.ability.set == 'Voucher' then other_key = 'other_voucher' end\n"
    "                -- TARGET: add context.other_something identifier to your cards\n"
)
new4 = (
    "            -- other_key already computed above (hoisted from inner loop)\n"
    "            for _, _area in ipairs(SMODS.get_card_areas('individual')) do\n"
    "                -- TARGET: add context.other_something identifier to your cards\n"
)
if old4 not in text:
    print("ERROR: individual loop other_key anchor not found in " + path, file=sys.stderr)
    sys.exit(1)
text = text.replace(old4, new4, 1)

open(path, 'w').write(text)
print("Scoring loop cache applied")
PYEOF
    if grep -q "SCORING_AREAS_CACHED" "$f" && grep -q "OTHER_KEY_HOISTED" "$f"; then
        log_success "Scoring loop cache applied (get_card_areas cached + other_key hoisted)"
    else
        log_warn "Scoring loop cache did not fully apply — check state_events.lua anchors"
    fi
}

# Eliminate paired to_big(x) OP to_big(literal) comparisons in Cryptid item files.
# These fire on every calculate_joker call for every joker that owns one of these
# items. The OmegaNum allocation on both sides is wasted when both operands are
# plain Lua numbers (joker config values stay in double range until chip totals
# exceed ~1e308). The inner ability.extra fields are always plain numbers: they
# are initialized from the joker center config and only updated via lenient_bignum
# which returns plain numbers for sub-1e300 values.
#
# Skipped (operands may be OmegaNum at runtime):
#   exotic.lua:470  to_big(context.cry_ease_dollars) — dollar total, can be big
#   exotic.lua:474  to_big(...money_remaining) >= to_big(...money_req) — accumulated
#   epic.lua:270    to_big(args.chips) >= to_big(1e100) — running chip total
#   m.lua:1897-1899 to_big(aaa) >= to_big(1234567654321) — aaa is a chip accumulator
#
# Applied: all remaining paired comparisons where both sides are ability.extra.*,
# ability.immutable.*, or small numeric literals.
apply_cryptid_to_big_elim() {
    local epic="$1"
    local exotic="$2"
    local m="$3"
    local missing=0
    for f in "$epic" "$exotic" "$m"; do
        if [[ ! -f "$f" ]]; then
            log_warn "Cryptid item file not found: $f, skipping to_big elimination"
            missing=1
        fi
    done
    [[ $missing -eq 1 ]] && return 0
    if grep -q "CRYPTID_TO_BIG_ELIM" "$epic"; then
        log_info "Cryptid to_big elimination already applied"
        return 0
    fi
    python3 - "$epic" "$exotic" "$m" <<'PYEOF'
import sys, re

# Regex: to_big(EXPR) OP to_big(EXPR2) where both EXPRs contain no function calls
# that could return OmegaNum (i.e., no bare variable that is a chip accumulator).
# We match the exact patterns present and substitute conservatively.
SAFE_SUBSTS = [
    # epic.lua
    (r'to_big\(card\.ability\.extra\.stat2\) > to_big\(1\)',
     'card.ability.extra.stat2 > 1'),
    (r'to_big\(card\.ability\.extra\.money\) > to_big\(0\)',
     'card.ability.extra.money > 0'),
    (r'to_big\(card\.ability\.extra\.chips\) > to_big\(0\)',
     'card.ability.extra.chips > 0'),
    (r'to_big\(card\.ability\.extra\.x_mult\) > to_big\(1\)',
     'card.ability.extra.x_mult > 1'),
    (r'to_big\(card\.ability\.extra\[mod_key\]\) > to_big\(1\)',
     'card.ability.extra[mod_key] > 1'),
    (r'to_big\(card\.ability\.extra\.rounds_remaining\) > to_big\(0\)',
     'card.ability.extra.rounds_remaining > 0'),
    (r'to_big\(bonus\) > to_big\(0\)',
     'bonus > 0'),
    (r'to_big\(card\.ability\.extra\.steelenhc\) ~= to_big\(1\)',
     'card.ability.extra.steelenhc ~= 1'),
    # exotic.lua
    (r'to_big\(card\.ability\.extra\.Emult\) > to_big\(1\)',
     'card.ability.extra.Emult > 1'),
    (r'to_big\(card\.ability\.extra\.chips\) > to_big\(0\)',
     'card.ability.extra.chips > 0'),
    (r'to_big\(card\.ability\.immutable\.check2\) <= to_big\(card\.ability\.extra\.check\)',
     'card.ability.immutable.check2 <= card.ability.extra.check'),
    (r'to_big\(card\.ability\.extra\.Xmult\) > to_big\(1\)',
     'card.ability.extra.Xmult > 1'),
    # m.lua
    (r'to_big\(card\.ability\.extra\.mult\) > to_big\(0\)',
     'card.ability.extra.mult > 0'),
    (r'to_big\(card\.ability\.extra\.rounds_remaining\) > to_big\(0\)',
     'card.ability.extra.rounds_remaining > 0'),
    (r'to_big\(card\.ability\.extra\.sell\) \+ 1 >= to_big\(card\.ability\.extra\.sell_req\)',
     'card.ability.extra.sell + 1 >= card.ability.extra.sell_req'),
    (r'to_big\(card\.ability\.extra\.retriggers\) < to_big\(1\)',
     'card.ability.extra.retriggers < 1'),
    (r'to_big\(card\.ability\.extra\.money\) > to_big\(0\)',
     'card.ability.extra.money > 0'),
    (r'to_big\(card\.ability\.immutable\.slots\) >= to_big\(card\.ability\.immutable\.max_slots\)',
     'card.ability.immutable.slots >= card.ability.immutable.max_slots'),
    (r'to_big\(card\.ability\.extra\.jollies\) < to_big\(1\)',
     'card.ability.extra.jollies < 1'),
    (r'to_big\(card\.ability\.extra\.unc\) < to_big\(1\)',
     'card.ability.extra.unc < 1'),
    (r'to_big\(jollycount\) > to_big\(card\.ability\.immutable\.max_jollies\)',
     'jollycount > card.ability.immutable.max_jollies'),
    (r'to_big\(summon\) < to_big\(1\)',
     'summon < 1'),
    (r'to_big\(card\.ability\.extra\.add\) < to_big\(1\)',
     'card.ability.extra.add < 1'),
    (r'to_big\(card\.ability\.extra\.amount\) < to_big\(card\.ability\.immutable\.max_amount\)',
     'card.ability.extra.amount < card.ability.immutable.max_amount'),
    (r'to_big\(card\.ability\.extra\.amount\) > to_big\(card\.ability\.immutable\.max_amount\)',
     'card.ability.extra.amount > card.ability.immutable.max_amount'),
    (r'to_big\(card\.ability\.extra\.amount\) > to_big\(0\)',
     'card.ability.extra.amount > 0'),
    (r'to_big\(card\.ability\.extra\.monster\) > to_big\(1\)',
     'card.ability.extra.monster > 1'),
]

total = 0
for path in sys.argv[1:]:
    text = open(path).read()
    orig = text
    for pattern, replacement in SAFE_SUBSTS:
        text, n = re.subn(pattern, replacement, text)
        total += n
    if text != orig:
        open(path, 'w').write(text)

# Add marker to epic.lua so idempotency check works
epic_path = sys.argv[1]
epic_text = open(epic_path).read()
if 'CRYPTID_TO_BIG_ELIM' not in epic_text:
    # Append marker as a comment at end of file
    open(epic_path, 'a').write('\n-- CRYPTID_TO_BIG_ELIM\n')

print(f"Cryptid to_big elimination: {total} substitutions across 3 item files")
PYEOF
    if grep -q "CRYPTID_TO_BIG_ELIM" "$epic"; then
        log_success "Cryptid to_big elimination applied (paired OmegaNum comparisons → plain Lua)"
    else
        log_warn "Cryptid to_big elimination did not apply marker — check epic.lua"
    fi
}

# Hoist the eval_card context table outside the inner other_joker loop in
# state_events.lua. The inner loop constructs a new 6-field table literal on
# every eval_card call. Since full_hand / scoring_hand / scoring_name /
# poker_hands are constant across all inner iterations, we can allocate one
# table before the loop and only swap in the per-iteration dynamic fields
# (other_key slot and other_main) between calls.
#
# Safety: eval_card is synchronous and does not retain the context reference
# after returning. SMODS.push_to_context_stack stores the reference during the
# call but pops it before return — so the table is not on the stack when we
# mutate it for the next iteration. The dynamic fields (pre_jokers,
# post_jokers, main_eval) written by wrappers are cleaned up within the same
# stack frame. The retrigger call at line 863 sets retrigger_joker=true; we
# clear it after that inner call.
apply_ctx_table_hoist() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "state_events.lua not found, skipping context table hoist"
        return 0
    fi
    if grep -q "CTX_TABLE_HOISTED" "$f"; then
        log_info "Context table hoist already applied"
        return 0
    fi
    python3 - "$f" <<'PYEOF'
import sys
path = sys.argv[1]
text = open(path).read()

# Replace the inner other_joker loop body with a hoisted context table.
# The old pattern constructs {full_hand=..., scoring_hand=..., ...} on
# every eval_card call. The new pattern builds it once before the loop.
old = (
    "            -- Calculate context.other_joker effects -- OTHER_KEY_HOISTED\n"
    "            local other_key = 'other_unknown'\n"
    "            if _card.ability.set == 'Joker' then other_key = 'other_joker' end\n"
    "            if _card.ability.consumeable then other_key = 'other_consumeable' end\n"
    "            if _card.ability.set == 'Voucher' then other_key = 'other_voucher' end\n"
    "            for _, _area in ipairs(_joker_areas) do\n"
    "                for _, _joker in ipairs(_area.cards) do\n"
    "                    -- TARGET: add context.other_something identifier to your cards\n"
    "                    local joker_eval,post = eval_card(_joker, {full_hand = G.play.cards, scoring_hand = scoring_hand, scoring_name = text, poker_hands = poker_hands, [other_key] = _card, other_main = _card })\n"
    "                    if next(joker_eval) then\n"
    "                        if joker_eval.edition then joker_eval.edition = {} end\n"
    "                        joker_eval.jokers.juice_card = _joker\n"
    "                        table.insert(effects, joker_eval)\n"
    "                        for _, v in ipairs(post) do effects[#effects+1] = v end\n"
    "                        if joker_eval.retriggers then\n"
    "                            for rt = 1, #joker_eval.retriggers do\n"
    "                                local rt_eval, rt_post = eval_card(_joker, {full_hand = G.play.cards, scoring_hand = scoring_hand, scoring_name = text, poker_hands = poker_hands, [other_key] = _card, retrigger_joker = true})\n"
    "                                if next(rt_eval) then\n"
    "                                    table.insert(effects, {retriggers = joker_eval.retriggers[rt]})\n"
    "                                    table.insert(effects, rt_eval)\n"
    "                                    for _, v in ipairs(rt_post) do effects[#effects+1] = v end\n"
    "                                end\n"
    "                            end\n"
    "                        end\n"
    "                    end\n"
    "                end\n"
    "            end\n"
)
new = (
    "            -- Calculate context.other_joker effects -- OTHER_KEY_HOISTED CTX_TABLE_HOISTED\n"
    "            local other_key = 'other_unknown'\n"
    "            if _card.ability.set == 'Joker' then other_key = 'other_joker' end\n"
    "            if _card.ability.consumeable then other_key = 'other_consumeable' end\n"
    "            if _card.ability.set == 'Voucher' then other_key = 'other_voucher' end\n"
    "            local _other_ctx = {full_hand = G.play.cards, scoring_hand = scoring_hand, scoring_name = text, poker_hands = poker_hands}\n"
    "            _other_ctx[other_key] = _card\n"
    "            _other_ctx.other_main = _card\n"
    "            for _, _area in ipairs(_joker_areas) do\n"
    "                for _, _joker in ipairs(_area.cards) do\n"
    "                    -- TARGET: add context.other_something identifier to your cards\n"
    "                    local joker_eval,post = eval_card(_joker, _other_ctx)\n"
    "                    if next(joker_eval) then\n"
    "                        if joker_eval.edition then joker_eval.edition = {} end\n"
    "                        joker_eval.jokers.juice_card = _joker\n"
    "                        table.insert(effects, joker_eval)\n"
    "                        for _, v in ipairs(post) do effects[#effects+1] = v end\n"
    "                        if joker_eval.retriggers then\n"
    "                            _other_ctx.retrigger_joker = true\n"
    "                            _other_ctx[other_key] = nil\n"
    "                            _other_ctx.other_main = nil\n"
    "                            for rt = 1, #joker_eval.retriggers do\n"
    "                                local rt_eval, rt_post = eval_card(_joker, _other_ctx)\n"
    "                                if next(rt_eval) then\n"
    "                                    table.insert(effects, {retriggers = joker_eval.retriggers[rt]})\n"
    "                                    table.insert(effects, rt_eval)\n"
    "                                    for _, v in ipairs(rt_post) do effects[#effects+1] = v end\n"
    "                                end\n"
    "                            end\n"
    "                            _other_ctx.retrigger_joker = nil\n"
    "                            _other_ctx[other_key] = _card\n"
    "                            _other_ctx.other_main = _card\n"
    "                        end\n"
    "                    end\n"
    "                end\n"
    "            end\n"
)
if old not in text:
    print("ERROR: other_joker inner loop anchor not found in " + path, file=sys.stderr)
    sys.exit(1)
text = text.replace(old, new, 1)
open(path, 'w').write(text)
print("Context table hoist applied")
PYEOF
    if grep -q "CTX_TABLE_HOISTED" "$f"; then
        log_success "Context table hoist applied (other_joker inner loop reuses one table)"
    else
        log_warn "Context table hoist did not apply — check state_events.lua anchor"
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

    # Apply all Mods-layer patches. patch_mods_dir is the single canonical list —
    # save-dir Mods shadow the APK-embedded ones, so these must match exactly.
    patch_mods_dir "$transfer_dir/Mods"

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

    # HARD GATE: deploying force-restarts the app on Joe's PHONE. That is never
    # an automated step — it interrupts whatever he is doing and must only
    # happen when he has explicitly said to deploy, every single time.
    # Emulator targets (emulator-*) are exempt; physical devices require the
    # operator to acknowledge by setting BALATRO_DEPLOY_PHONE=1 for this run.
    if [[ "$device" != emulator-* && "${BALATRO_DEPLOY_PHONE:-}" != "1" ]]; then
        log_error "Refusing to deploy to physical device $device."
        log_info  "Deploys restart the app on Joe's phone. Only deploy when he has"
        log_info  "explicitly asked for it in the current conversation, then run:"
        log_info  "    BALATRO_DEPLOY_PHONE=1 ./scripts/build.sh deploy"
        exit 1
    fi
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

# UI hot-path fix: remove the redundant update_text() call in hand_chip_UI_set and
# hand_mult_UI_set. Both functions call e.config.object:update_text() explicitly
# (lines 1940, 1956) and then immediately call G.FUNCS.text_super_juice(e, num),
# which calls update_text() a SECOND time. The first call is wasted because the
# scale hasn't been committed to the font layout yet and text_super_juice re-does
# the work. Removing the two early calls cuts update_text() invocations in half
# during the per-chip/mult scoring animation (~60-76 Hz * 2 calls = 120-152 saved
# per second while scoring).
apply_hand_update_text_dedup() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "button_callbacks.lua not found, skipping hand update_text dedup"
        return 0
    fi
    if grep -q "HAND_UPDATE_TEXT_DEDUP" "$f"; then
        log_info "Hand update_text dedup already applied"
        return 0
    fi
    python3 - "$f" <<'PYEOF'
import sys
path = sys.argv[1]
text = open(path).read()

# Remove the explicit update_text() call from hand_mult_UI_set (text_super_juice repeats it)
old_mult = (
    "    e.config.object.scale = scale_number(G.GAME.current_round.current_hand.mult, 0.9, 1000)\n"
    "    e.config.object:update_text()\n"
    "    local num = 0\n"
)
new_mult = (
    "    e.config.object.scale = scale_number(G.GAME.current_round.current_hand.mult, 0.9, 1000)\n"
    "    -- HAND_UPDATE_TEXT_DEDUP: removed early update_text(); text_super_juice calls it again\n"
    "    local num = 0\n"
)

# Remove the explicit update_text() call from hand_chip_UI_set
old_chip = (
    "      e.config.object.scale = scale_number(G.GAME.current_round.current_hand.chips, 0.9, 1000)\n"
    "      e.config.object:update_text()\n"
    "      local num = 0\n"
)
new_chip = (
    "      e.config.object.scale = scale_number(G.GAME.current_round.current_hand.chips, 0.9, 1000)\n"
    "      -- HAND_UPDATE_TEXT_DEDUP: removed early update_text(); text_super_juice calls it again\n"
    "      local num = 0\n"
)

if old_mult not in text:
    print("ERROR: hand_mult_UI_set anchor not found", file=sys.stderr)
    sys.exit(1)
if old_chip not in text:
    print("ERROR: hand_chip_UI_set anchor not found", file=sys.stderr)
    sys.exit(1)

text = text.replace(old_mult, new_mult, 1)
text = text.replace(old_chip, new_chip, 1)
open(path, 'w').write(text)
print("Hand update_text dedup applied")
PYEOF
    if grep -q "HAND_UPDATE_TEXT_DEDUP" "$f"; then
        log_success "Hand update_text dedup applied (removed 2 redundant update_text() calls per scoring tick)"
    else
        log_warn "Hand update_text dedup did not apply — check button_callbacks.lua"
    fi
}

# UI hot-path fix: add no_recalc = true to the hand_level T-element in SMODS GUI.
# Without it, UIElement:update_text() fires a full UIBox:recalculate() whenever the
# hand_level string length changes (e.g. "Lv 1" -> "Lv 10"). recalculate() walks the
# entire HUD tree — all children's calculate_xywh, set_wh, set_alignments, and
# initialize_VT — on every level-up display update. With no_recalc, the string is
# updated in-place and the layout walk is suppressed (safe because the hand_level
# column uses a fixed-width parent container).
apply_hand_level_no_recalc() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "Steamodded src/ui.lua not found, skipping hand_level no_recalc"
        return 0
    fi
    if grep -q "HAND_LEVEL_NO_RECALC" "$f"; then
        log_info "hand_level no_recalc already applied"
        return 0
    fi
    python3 - "$f" <<'PYEOF'
import sys
path = sys.argv[1]
text = open(path).read()

old = "{n=G.UIT.T, config={ref_table = G.GAME.current_round.current_hand, ref_value='hand_level', scale = scale, colour = G.C.UI.TEXT_LIGHT, id = 'hand_level', shadow = true}},"
new = "{n=G.UIT.T, config={ref_table = G.GAME.current_round.current_hand, ref_value='hand_level', scale = scale, colour = G.C.UI.TEXT_LIGHT, id = 'hand_level', shadow = true, no_recalc = true}}, -- HAND_LEVEL_NO_RECALC"

if old not in text:
    print("ERROR: hand_level T-element anchor not found in " + path, file=sys.stderr)
    sys.exit(1)

open(path, 'w').write(text.replace(old, new, 1))
print("hand_level no_recalc applied")
PYEOF
    if grep -q "HAND_LEVEL_NO_RECALC" "$f"; then
        log_success "hand_level no_recalc applied (suppresses full HUD recalculate on level-up display)"
    else
        log_warn "hand_level no_recalc did not apply — check Steamodded src/ui.lua"
    fi
}

# UI hot-path fix: cache the localize('k_lvl') prefix string in update_hand_text.
# The level update path in common_events.lua calls localize('k_lvl') twice on every
# invocation where vals.level is set — once in the comparison guard and again when
# assigning the new hand_level string. localize() itself is cheap (O(1) table lookup)
# but the string concatenation (' '..localize('k_lvl')..tostring(vals.level)) produces
# two short-lived strings that the GC must collect. Caching the prefix in a local
# reduces this to one concatenation and zero repeated localize() calls per level update.
apply_lvl_prefix_cache() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "common_events.lua not found, skipping lvl prefix cache"
        return 0
    fi
    if grep -q "LVL_PREFIX_CACHED" "$f"; then
        log_info "lvl prefix cache already applied"
        return 0
    fi
    python3 - "$f" <<'PYEOF'
import sys
path = sys.argv[1]
text = open(path).read()

old = (
    "        if vals.level and G.GAME.current_round.current_hand.hand_level ~= ' '..localize('k_lvl')..tostring(vals.level) then\n"
    "            if vals.level == '' then\n"
    "                G.GAME.current_round.current_hand.hand_level = vals.level\n"
    "            else\n"
    "                G.GAME.current_round.current_hand.hand_level = ' '..localize('k_lvl')..tostring(vals.level)\n"
)
new = (
    "        local _lvl_pfx = ' '..localize('k_lvl') -- LVL_PREFIX_CACHED\n"
    "        if vals.level and G.GAME.current_round.current_hand.hand_level ~= _lvl_pfx..tostring(vals.level) then\n"
    "            if vals.level == '' then\n"
    "                G.GAME.current_round.current_hand.hand_level = vals.level\n"
    "            else\n"
    "                G.GAME.current_round.current_hand.hand_level = _lvl_pfx..tostring(vals.level)\n"
)

if old not in text:
    print("ERROR: lvl prefix anchor not found in " + path, file=sys.stderr)
    sys.exit(1)

open(path, 'w').write(text.replace(old, new, 1))
print("lvl prefix cache applied")
PYEOF
    if grep -q "LVL_PREFIX_CACHED" "$f"; then
        log_success "lvl prefix cache applied (1 localize() + 1 concat eliminated per hand-level update)"
    else
        log_warn "lvl prefix cache did not apply — check common_events.lua"
    fi
}

# parse_highlighted runs on every selection change (and again per add when a
# sweep multi-selects). Three allocation sources, all in the same function:
#   1. DEAD duplicate hand evaluation: the Cryptid lovely patch redeclares
#      text/disp_text/poker_hands right after vanilla's call, so the first
#      G.FUNCS.get_poker_hand_info(self.highlighted) — a full O(hand²)
#      evaluate_poker_hand with its 12 result sub-tables — is computed and
#      thrown away on EVERY call. Delete it.
#   2. The update_hand_text first-arg option tables: identical literals
#      allocated per call (and per Scoring_Parameters loop iteration). Hoist
#      as module constants — update_hand_text only reads .immediate/.delay/
#      .nopulse, never mutates or retains.
#   3. The joker-merge: fresh tbl {} + Cryptid.table_merge fresh copy per
#      call. Reuse one module scratch table — evaluate_poker_hand stores CARD
#      references in its results, never the input table, so the scratch never
#      escapes (verified SMODS overrides.lua:1518 + Cryptid ascended.lua:25).
apply_parse_highlighted_lean() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "cardarea.lua not found, skipping parse_highlighted lean"
        return 0
    fi
    if grep -q "PARSE_HL_LEAN" "$f"; then
        log_info "parse_highlighted lean already applied"
        return 0
    fi
    python3 - "$f" <<'PYEOF'
import sys
path = sys.argv[1]
text = open(path).read()

# constants + scratch, hoisted above the function
old_head = "function CardArea:parse_highlighted()"
new_head = """local PH_CFG_PULSE = {immediate = true, nopulse = true, delay = 0} -- PARSE_HL_LEAN
local PH_CFG_PLAIN = {immediate = true, delay = 0}
local PH_JOKER_SCRATCH = {}
function CardArea:parse_highlighted()"""

# 1. dead duplicate evaluation (vanilla call immediately shadowed by the
#    Cryptid-patched redeclaration)
old_dead = """    local text,disp_text,poker_hands = G.FUNCS.get_poker_hand_info(self.highlighted)
    local text,disp_text,poker_hands
"""
new_dead = """    local text,disp_text,poker_hands
"""

# 3. joker-merge scratch reuse (replaces fresh tbl + table_merge copy)
old_merge = """    	local tbl = {}
    	for i, v in pairs(G.jokers.cards) do
    		if v.base.nominal and v.base.suit then
    			tbl[#tbl+1] = v
    		end
    	end
    	text,disp_text,poker_hands = G.FUNCS.get_poker_hand_info(Cryptid.table_merge(self.highlighted, tbl))"""
new_merge = """    	local tbl = PH_JOKER_SCRATCH
    	for i = #tbl, 1, -1 do tbl[i] = nil end
    	for i, v in ipairs(self.highlighted) do tbl[#tbl+1] = v end
    	for i, v in pairs(G.jokers.cards) do
    		if v.base.nominal and v.base.suit then
    			tbl[#tbl+1] = v
    		end
    	end
    	text,disp_text,poker_hands = G.FUNCS.get_poker_hand_info(tbl)"""

for name, old in (('head', old_head), ('dead-call', old_dead), ('joker-merge', old_merge)):
    if old not in text:
        print(f"ERROR: parse_highlighted anchor not found: {name}", file=sys.stderr)
        sys.exit(1)

text = text.replace(old_dead, new_dead, 1)
text = text.replace(old_merge, new_merge, 1)
text = text.replace(old_head, new_head, 1)

# 2. option-table hoists — only within this function's known call shapes
n1 = text.count("update_hand_text({immediate = true, nopulse = true, delay = 0},")
text = text.replace("update_hand_text({immediate = true, nopulse = true, delay = 0},",
                    "update_hand_text(PH_CFG_PULSE,")
n2 = text.count("update_hand_text({immediate = true, nopulse = nil, delay = 0},")
text = text.replace("update_hand_text({immediate = true, nopulse = nil, delay = 0},",
                    "update_hand_text(PH_CFG_PLAIN,")

open(path, 'w').write(text)
print(f"parse_highlighted lean applied (dead call removed, {n1}+{n2} option tables hoisted, joker scratch)")
PYEOF
    if grep -q "PARSE_HL_LEAN" "$f"; then
        log_success "parse_highlighted lean applied (dead hand evaluation removed + option tables hoisted + joker-merge scratch)"
    else
        log_warn "parse_highlighted lean did not apply — check cardarea.lua"
    fi
}

# card_eval_status_text fires once per scoring trigger. Its `local config = {}`
# escaped into the deferred E_MANAGER event closure (attention_text reads
# config.scale when the event fires), so the PERF-FINDINGS scratch-table shape
# is UNSAFE — a shared scratch would alias every queued popup. The safe shape
# is stronger: .scale becomes a plain local (per-call frame, captured correctly
# by the closure), the 16 config.type writes were never read anywhere (dead),
# and the config.colour read happened while the table was still empty (always
# nil). Net: one table alloc per trigger gone + dead code removed.
apply_card_eval_config_elide() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "common_events.lua not found, skipping card_eval config elide"
        return 0
    fi
    if grep -q "CES_CONFIG_ELIDED" "$f"; then
        log_info "card_eval config elide already applied"
        return 0
    fi
    python3 - "$f" <<'PYEOF'
import sys, re
path = sys.argv[1]
text = open(path).read()

start = text.find('function card_eval_status_text(')
if start < 0:
    print('ERROR: card_eval_status_text not found', file=sys.stderr); sys.exit(1)
nxt = text.find('\nfunction ', start + 1)
body = text[start:nxt]

body = body.replace(
    '    local config = {}\n',
    '    local config_scale -- CES_CONFIG_ELIDED: .scale -> local; .type writes were dead; .colour read was always nil\n', 1)
body = body.replace(
    'local colour = config.colour or (extra and extra.colour) or ( G.C.FILTER )',
    'local colour = (extra and extra.colour) or ( G.C.FILTER )', 1)
body, n_type = re.subn(r'[ \t]*config\.type = \'(?:fade|fall)\'\n', '', body)
body, n_w = re.subn(r'\bconfig\.scale = ', 'config_scale = ', body)
body, n_r = re.subn(r'\bconfig\.scale or 1\b', 'config_scale or 1', body)

leftover = [l for l in body.splitlines() if 'config' in l and 'config_scale' not in l and 'CES_CONFIG' not in l]
if leftover:
    print('ERROR: unhandled config references remain:', file=sys.stderr)
    for l in leftover: print('  ' + l.strip(), file=sys.stderr)
    sys.exit(1)

open(path, 'w').write(text[:start] + body + text[nxt:])
print(f'card_eval config elided ({n_type} dead .type writes removed, {n_w}+{n_r} .scale sites -> local)')
PYEOF
    if grep -q "CES_CONFIG_ELIDED" "$f"; then
        log_success "card_eval_status_text config elided (1 escaping table/trigger removed + 16 dead writes)"
    else
        log_warn "card_eval config elide did not apply — check common_events.lua"
    fi
}

# get_X_same is called 5x per hand evaluation (SMODS _2/_3/_4/_5/_all_pairs
# parts), which runs on every selection change. Vanilla is O(hand²) and
# allocates ~16 preallocated empty rank buckets plus a discarded `curr` table
# per card per call. Replaced by a two-pass O(n): count ranks (no allocation),
# then build group tables only for qualifying ranks. Outputs differential-
# tested identical to vanilla over 5000 randomized hands (group membership,
# member order, result order, ids<1 exclusion). Isolated bench (GC stopped):
# 9.5 -> 1.6 KB gross per evaluation (-83%), 3.4x faster.
apply_get_x_same_lean() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "misc_functions.lua not found, skipping get_X_same lean"
        return 0
    fi
    if grep -q "GET_X_SAME_LEAN_V2" "$f"; then
        log_info "get_X_same lean already applied"
        return 0
    fi
    python3 - "$f" <<'PYEOF'
import sys, re
path = sys.argv[1]
text = open(path).read()

start = text.find('function get_X_same(')
if start < 0:
    print('ERROR: get_X_same not found', file=sys.stderr); sys.exit(1)
m = re.search(r'\nend\n', text[start:])
if not m:
    print('ERROR: get_X_same end not found', file=sys.stderr); sys.exit(1)

new = """function get_X_same(num, hand, or_more) -- GET_X_SAME_LEAN_V2
  -- Two-pass O(n): count ranks (no allocation), then build group tables ONLY
  -- for qualifying ranks. Replaces the vanilla O(n²) scan that preallocated
  -- ~16 empty rank buckets and a discarded `curr` per card — called 5x per
  -- hand evaluation by the SMODS _2/_3/_4/_5/_all_pairs parts. Outputs are
  -- differential-tested identical to vanilla: groups hold cards in hand
  -- order, result ordered by descending rank id, ids < 1 excluded.
  local counts = {}
  local max_id = 0
  for i = 1, #hand do
    local id = hand[i]:get_id()
    counts[id] = (counts[id] or 0) + 1
    if id > max_id then max_id = id end
  end
  local ret = {}
  for id = math.floor(max_id), 1, -1 do
    local n = counts[id]
    if n and (or_more and (n >= num) or (n == num)) then
      local g = {}
      for i = 1, #hand do
        if hand[i]:get_id() == id then g[#g + 1] = hand[i] end
      end
      ret[#ret + 1] = g
    end
  end
  return ret
end
"""

open(path, 'w').write(text[:start] + new + text[start + m.end():])
print('get_X_same lean v2 applied')
PYEOF
    if grep -q "GET_X_SAME_LEAN_V2" "$f"; then
        log_success "get_X_same lean applied (O(n) count-first; -83% alloc, 3.4x faster per evaluation)"
    else
        log_warn "get_X_same lean did not apply — check misc_functions.lua"
    fi
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
