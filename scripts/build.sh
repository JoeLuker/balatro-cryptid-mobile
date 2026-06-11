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
    apply_talisman_config_persist "$mods_dir/Talisman/talisman.lua"
    apply_shader_eof_newlines "$(dirname "$mods_dir")"
    apply_blur_shader_reorder "$mods_dir/Cryptid/assets/shaders/blur.fs"
    apply_glitch_shader_fix   "$mods_dir/Cryptid/assets/shaders/glitched.fs"
    apply_glitch_shader_range_fix "$mods_dir/Cryptid/assets/shaders/glitched.fs"
    apply_glitched_b_fix      "$mods_dir/Cryptid/assets/shaders/glitched_b.fs"
    apply_cryptid_dead_copy_fix   "$mods_dir/Cryptid/lib/calculate.lua"
    apply_cryptid_flip_side_cache "$mods_dir/Cryptid/lib/calculate.lua" "$mods_dir/Cryptid/lib/overrides.lua"
    apply_cryptid_events_guard    "$mods_dir/Cryptid/lib/calculate.lua"
    apply_talisman_gc_dead_block  "$mods_dir/Talisman/talisman.lua"
    apply_talisman_calc_counter   "$mods_dir/Talisman/talisman.lua"
    apply_nf_big_cache            "$mods_dir/Talisman/talisman.lua"
    apply_cryptid_to_big_elim \
        "$mods_dir/Cryptid/items/epic.lua" \
        "$mods_dir/Cryptid/items/exotic.lua" \
        "$mods_dir/Cryptid/items/m.lua"
    apply_hand_level_no_recalc "$mods_dir/Steamodded/src/ui.lua"
}

# Strip assets unused in an en-us Android build.
# Saves ~60 MB from game.love by removing non-English fonts and locale files.
# All removals are safe: game.lua's font loader guards with love.filesystem.getInfo
# (missing = skipped silently) and SMODS locale loading is by exact name (no dir
# scan). NOTE resources/gamecontrollerdb.txt must NOT be stripped: LÖVE's
# loadGamepadMappings does not return false on a missing file — it parses the
# path string itself as mapping data and raises "Invalid gamepad mappings.",
# crash-looping boot at game.lua:148 (proven by test/telemetry-gate.sh 2026-06-10).
strip_en_us_assets() {
    local game_dir="$1"
    local saved=0

    # Fonts: only m6x11plus.ttf is used for en-us. All others are CJK / script fonts
    # for languages this build never selects. GoNotoCurrent/CJKCore are 'all1'/'all2'
    # omit=true entries — no language selects them; font slots 8/9 go unreferenced.
    local fonts_dir="$game_dir/resources/fonts"
    local strip_fonts=(
        "NotoSansSC-Bold.ttf"   # zh_CN  — 11 MB
        "NotoSansTC-Bold.ttf"   # zh_TW  —  7 MB
        "NotoSansKR-Bold.ttf"   # ko     —  6 MB
        "NotoSansJP-Bold.ttf"   # ja     —  6 MB
        "NotoSans-Bold.ttf"     # ru     —  1 MB
        "GoNotoCurrent-Bold.ttf" # all1 omit=true — 14 MB
        "GoNotoCJKCore.ttf"     # all2 omit=true — 18 MB
    )
    for f in "${strip_fonts[@]}"; do
        local path="$fonts_dir/$f"
        if [[ -f "$path" ]]; then
            local sz
            sz=$(du -k "$path" | cut -f1)
            saved=$((saved + sz))
            rm "$path"
        fi
    done

    # Locale files: strip all non-English from every localization/ directory.
    # Keep en-us.lua and default.lua (SMODS loads both by name; others are never read).
    local loc_dirs=(
        "$game_dir/localization"
        "$game_dir/Mods/Cryptid/localization"
        "$game_dir/Mods/Talisman/localization"
    )
    for dir in "${loc_dirs[@]}"; do
        if [[ -d "$dir" ]]; then
            for f in "$dir"/*.lua "$dir"/*.json; do
                [[ -f "$f" ]] || continue
                local base
                base="$(basename "$f")"
                # Keep en-us.lua and default.lua; strip everything else
                if [[ "$base" != "en-us.lua" && "$base" != "default.lua" && \
                      "$base" != "en-us.json" && "$base" != "default.json" ]]; then
                    local sz
                    sz=$(du -k "$f" | cut -f1)
                    saved=$((saved + sz))
                    rm "$f"
                fi
            done
        fi
    done

    log_success "Asset strip complete — freed ~$((saved / 1024)) MB from game.love"
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

    # Launcher icon: the balatro-mobile-maker base template ships its author's
    # cat photo as @drawable/love. Replace all density variants with the
    # Cryptid glowing deck back (extracted from the mod's own b_cry_glowing
    # atlas into patches/icon/; regenerate from there if the art changes).
    for _icon_d in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
        if [[ -f "$PATCHES_DIR/icon/love-$_icon_d.png" ]]; then
            cp "$PATCHES_DIR/icon/love-$_icon_d.png" "$BUILD_DIR/apktool/res/drawable-$_icon_d/love.png"
        fi
    done
    log_success "Launcher icon set to Cryptid glowing deck back"

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
    apply_android_quit_fix "$game_dir/functions/button_callbacks.lua"
    apply_drag_reject_feedback "$game_dir/functions/button_callbacks.lua"
    apply_fps_toggle "$game_dir/game.lua" "$game_dir/functions/UI_definitions.lua"
    apply_debug_overlay "$game_dir/game.lua" "$game_dir/functions/misc_functions.lua" "$game_dir/functions/UI_definitions.lua"
    # Use Python patcher for main.lua (more reliable than sed for complex patches)
    python3 "$SCRIPT_DIR/patch_main_lua.py" "$game_dir/main.lua"
    patch_mods_dir "$game_dir/Mods"
    apply_shake_trig_guard "$game_dir/functions/common_events.lua"
    apply_tap_description_persist "$game_dir/engine/controller.lua"
    apply_cursor_down_uptime_fix "$game_dir/engine/controller.lua"
    apply_drag_self_drop_exclude "$game_dir/engine/controller.lua"
    apply_ui_colour_guard "$game_dir/engine/ui.lua"
    apply_drag_select "$game_dir/engine/controller.lua" "$game_dir/globals.lua" "$game_dir/functions/UI_definitions.lua"
    apply_telemetry_toggles "$game_dir/functions/UI_definitions.lua" "$game_dir/game.lua"
    apply_shadow_height_fix "$game_dir/card.lua"
    apply_card_to_big_elim "$game_dir/card.lua"
    apply_scoring_loop_cache "$game_dir/functions/state_events.lua"
    apply_ctx_table_hoist "$game_dir/functions/state_events.lua"
    apply_hand_update_text_dedup "$game_dir/functions/button_callbacks.lua"
    apply_lvl_prefix_cache "$game_dir/functions/common_events.lua"
    apply_parse_highlighted_lean "$game_dir/cardarea.lua"
    apply_card_eval_config_elide "$game_dir/functions/common_events.lua"
    apply_get_x_same_lean "$game_dir/functions/misc_functions.lua"
    apply_ces_sign_fast "$game_dir/functions/common_events.lua"
    apply_dynatext_glyph_cache "$game_dir/engine/text.lua"
    apply_letter_table_reuse   "$game_dir/engine/text.lua"
    # NF_BIG_CACHE must ALSO hit the lovely-merged Talisman copy inside
    # main.lua — that copy is what executes on Android (the Mods/Talisman
    # tree is dead code at runtime there); patching only the mod copy left
    # the fix inert on-device.
    apply_nf_big_cache         "$game_dir/main.lua"
    apply_nugc_adaptive        "$game_dir/functions/misc_functions.lua"

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

    # Strip assets that are never used in an en-us Android build (CJK fonts +
    # non-English locales — see strip_en_us_assets for what must NOT be stripped).
    strip_en_us_assets "$game_dir"

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

# The prior glitched.fs fixes (highp + tan NaN guard) are necessary but NOT
# sufficient on Mali: tan(2.*time) takes arguments up to ~6000 rad and the
# sin-based rand() hash feeds sin() up to ~13000 rad. Desktop Mesa range-
# reduces large transcendental arguments precisely; Mali's cheap range
# reduction returns garbage there REGARDLESS of declared precision — the
# glitch edition renders as a black/garbage card. Eliminate the class:
# reduce tan's argument by its own period first (mathematically what a
# correct tan implementation computes, so desktop visuals are unchanged),
# and replace the sin-hash with a sineless fract hash whose intermediates
# are bounded by construction — also drops three sin calls per fragment.
apply_glitch_shader_range_fix() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "glitched.fs not found, skipping glitch range fix"
        return 0
    fi
    if grep -q "MALI_RANGE_FIX" "$f"; then
        log_info "Glitch shader range fix already applied"
        return 0
    fi
    sed -i 's|tan(2. \* time)|tan(mod(2. * time, 3.14159265358979))|' "$f"
    python3 - "$f" <<'PYEOF'
import sys
path = sys.argv[1]
text = open(path).read()

old = """highp float rand(highp vec2 co){
    return fract(sin(dot(co.xy ,vec2(12.9898,78.233))) * 43758.5453);
}"""
new = """highp float rand(highp vec2 co){
    // MALI_RANGE_FIX: sineless hash. The sin-based hash fed sin() arguments
    // up to ~13000 rad, where Mali's range reduction returns garbage. fract
    // keeps every intermediate bounded; same statistical character, and no
    // transcendentals.
    highp vec3 p3 = fract(vec3(co.xyx) * .1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}"""

if old not in text:
    print('ERROR: rand() anchor not found (did apply_glitch_shader_fix run first?)', file=sys.stderr)
    sys.exit(1)
open(path, 'w').write(text.replace(old, new, 1))
print('glitched.fs range fix applied')
PYEOF
    if grep -q "MALI_RANGE_FIX" "$f" && grep -q "tan(mod(2. \* time" "$f"; then
        log_success "Glitch shader range fix applied (bounded tan + sineless hash)"
    else
        log_warn "Glitch shader range fix did not fully match — check glitched.fs"
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
    # collection runs with EITHER toggle: Debug Logging alone gives headless
    # per-checkpoint timing through telemetry (no on-screen overlay — the
    # draw stays gated on perf_mode below)
    sed -i 's|if not G.F_ENABLE_PERF_OVERLAY then return end|if not (G.SETTINGS.perf_mode or G.SETTINGS.telemetry_log) then return end|' "$misc"
    sed -i 's|if not _RELEASE_MODE and G.DEBUG and not G.video_control and G.F_VERBOSE then|if G.SETTINGS.perf_mode then|' "$game_lua"
    sed -i 's|        love.graphics.setColor(0, 1, 1,1)|        love.graphics.origin()\n        love.graphics.setColor(0, 1, 1,1)|' "$game_lua"
    sed -i "s|create_toggle({label = \"Show FPS\", ref_table = G.SETTINGS, ref_value = 'show_fps'}),|create_toggle({label = \"Show FPS\", ref_table = G.SETTINGS, ref_value = 'show_fps'}),\n      create_toggle({label = \"Debug Overlay\", ref_table = G.SETTINGS, ref_value = 'perf_mode'}),|" "$ui_file"
    if grep -q "if G.SETTINGS.perf_mode then" "$game_lua" && grep -q "Debug Overlay" "$ui_file"; then
        log_success "Debug overlay added (Settings > Game > Debug Overlay)"
    else
        log_warn "Debug overlay did not fully apply — check anchors"
    fi
}

# Telemetry & debug logging are OFF by default so the APK is shareable — on a
# phone that never flips the toggles the game prints nothing, writes no
# telemetry.log, and never starts the phone-home thread. Two toggles in
# Settings > Game (persisted in settings.jkr like every other setting):
#   "Debug Logging"        -> G.SETTINGS.telemetry_log
#   "Phone Home Telemetry" -> G.SETTINGS.telemetry_home
# patches/android-telemetry.lua reads both live each frame. This applier adds
# the toggles (anchored on the Slide-to-select toggle, so it must run after
# apply_drag_select) and gates the vanilla LONG DT logcat print behind
# telemetry_log — the PERF-FINDINGS LONG_DT entry, realized as a gate.
apply_telemetry_toggles() {
    local ui_file="$1"
    local game_lua="$2"
    if [[ ! -f "$ui_file" || ! -f "$game_lua" ]]; then
        log_warn "UI_definitions.lua / game.lua not found, skipping telemetry toggles"
        return 0
    fi
    if ! grep -q "telemetry_log" "$ui_file"; then
        sed -i "s|create_toggle({label = \"Slide to select cards\", ref_table = G.SETTINGS, ref_value = 'enable_drag_select'}),|create_toggle({label = \"Slide to select cards\", ref_table = G.SETTINGS, ref_value = 'enable_drag_select'}),\n      create_toggle({label = \"Debug Logging\", ref_table = G.SETTINGS, ref_value = 'telemetry_log'}),\n      create_toggle({label = \"Phone Home Telemetry\", ref_table = G.SETTINGS, ref_value = 'telemetry_home'}),|" "$ui_file"
    fi
    if ! grep -q "G.SETTINGS.telemetry_log and self.real_dt" "$game_lua"; then
        sed -i "s|    if self.real_dt > 0.05 then print('LONG DT|    if G.SETTINGS.telemetry_log and self.real_dt > 0.05 then print('LONG DT|" "$game_lua"
    fi
    if grep -q "telemetry_home" "$ui_file" && grep -q "G.SETTINGS.telemetry_log and self.real_dt" "$game_lua"; then
        log_success "Telemetry toggles added (Settings > Game — both default OFF; LONG DT print gated)"
    else
        # hard failure: a silent anchor miss here ships an APK whose telemetry
        # can never be enabled (or whose LONG DT print is ungated) — the whole
        # point of the gating is consent, so a broken gate fails the build
        log_error "Telemetry toggles did not fully apply — check anchors"
        exit 1
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
    # 1.0s, not 0.3: the gate measures WALL-CLOCK scoring time, so at low frame
    # rates (big Cryptid decks) every hand crosses a 0.3s threshold and the
    # overlay legitimately flashes for the last frame or two of scoring. One
    # second matches the original intent: appear only when Abort is useful.
    sed -i 's|              if not G.OVERLAY_MENU then|              if not G.OVERLAY_MENU and love.timer.getTime() - (G.SCORING_START or love.timer.getTime()) > 1.0 then -- TALISMAN_DIM_GATE|' "$f"
    if grep -q "G.SCORING_START or love.timer.getTime()) > 1.0" "$f"; then
        log_success "Talisman scoring-dim fix applied (no dim flicker on fast hands): $f"
    else
        log_warn "Talisman dim fix did not fully apply — check $f"
    fi
    # TAL_CALC_TRACE: the user reports the Calculating/Abort overlay NEVER
    # appears, even on multi-second scorings where it should. The decisive
    # facts (does the coroutine stay alive across frames? is G.OVERLAY_MENU
    # blocking the gate?) are only observable live — one event per completed
    # scoring carries them all.
    sed -i 's|              G.SCORING_TEXT = nil|              G.SCORING_FRAMES = (G.SCORING_FRAMES or 0) + 1 -- TAL_CALC_TRACE\n              G.SCORING_TEXT = nil|' "$f"
    sed -i 's|              G.GAME.LAST_CALCS = totalCalcs|              if ATLOG then ATLOG("TAL_CALC_DONE", {calcs = totalCalcs, frames = G.SCORING_FRAMES or 0, elapsed = string.format("%.2f", love.timer.getTime() - (G.SCORING_START or love.timer.getTime())), ovl = G.OVERLAY_MENU and 1 or 0}) end G.SCORING_FRAMES = 0 -- TAL_CALC_TRACE\n              G.GAME.LAST_CALCS = totalCalcs|' "$f"
    sed -i 's|                  G.scoring_text = {localize("talisman_string_D"), "", "", ""}|                  if ATLOG then ATLOG("TAL_CALC_OVERLAY", {at = string.format("%.2f", love.timer.getTime() - (G.SCORING_START or 0))}) end -- TAL_CALC_TRACE\n                  G.scoring_text = {localize("talisman_string_D"), "", "", ""}|' "$f"
    if [[ $(grep -c "TAL_CALC_TRACE" "$f") -ge 3 ]]; then
        log_success "Talisman calc-screen trace applied"
    else
        log_warn "Talisman calc-screen trace did not fully apply — check $f"
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
    # press-position sync: on touch, the synthetic mouse position teleports via
    # a motion event that can arrive a batch AFTER the press (same cross-batch
    # delivery as the HID istouch race). set_cursor_position then describes
    # where the finger USED to be, so the whole press frame — cursor_hover,
    # collision_list, cursor_down.target, DRAG_SELECT_ACTIVATE's empty-space
    # test — runs against the previous touch location: slide-to-select never
    # arms, and the press can grab the previously-touched card instead. The
    # press event carries the true coordinates; trust them for that frame.
    sed -i 's|        self.cursor_position.x, self.cursor_position.y = love.mouse.getPosition()|        self.cursor_position.x, self.cursor_position.y = love.mouse.getPosition()\n        if (self.HID.touch or self.HID.touch_env) and self.L_cursor_queue then self.cursor_position.x, self.cursor_position.y = self.L_cursor_queue.x, self.L_cursor_queue.y end -- TOUCH_PRESS_POS_SYNC|' "$f"
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
    # hold-keep: descriptions appear at the 0.2s hold, but the tap window
    # (click_timeout) is 0.3s — lifting right when the popup shows registered
    # as a TAP, which for hand cards both SELECTED the card and ran the
    # TAP_DESC_TOGGLE dismiss, destroying the description the hold just
    # summoned (trace-confirmed on device). Holds >= 0.2s are not taps for
    # hand cards on touch: no select, no toggle-dismiss.
    sed -i 's|                    if self.cursor_down.target.area == G.hand then|                    if self.cursor_down.target.area == G.hand and ((self.cursor_up.time or 0) - (self.cursor_down.time or 0)) < 0.2*G.SPEEDFACTOR then -- TAP_DESC_HOLD_KEEP (tap-window clock is TOTAL: game-speed scaled, like the vanilla click_timeout check)|' "$f"
    sed -i 's|                    if self.cursor_down.target.states.click.can then|                    if self.cursor_down.target.states.click.can and not ((self.HID.touch or self.HID.touch_env) and self.cursor_down.target.area == G.hand and ((self.cursor_up.time or 0) - (self.cursor_down.time or 0)) >= 0.2*G.SPEEDFACTOR) then -- TAP_DESC_HOLD_NOSELECT|' "$f"
    # popup no-collide: vanilla disables collision on the popup ROOT only; the
    # inner UIElement tree still collides (and the root is drag.can=true), so
    # a persisted description squats over its card and eats the next touch —
    # the press lands on a popup element, drag-select arms empty-space style,
    # and the card cannot be re-summoned. Description popups are display-only:
    # disable collision on the whole tree.
    local node_f="$(dirname "$f")/node.lua"
    sed -i 's|            self.children.h_popup.states.collide.can = false|            self.children.h_popup.states.collide.can = false\n            self.children.h_popup.states.drag.can = false\n            local function _popup_nc(n) -- TAP_DESC_POPUP_NOCOLLIDE\n                n.states.collide.can = false\n                if n.children then for _, _c in pairs(n.children) do _popup_nc(_c) end end\n            end\n            _popup_nc(self.children.h_popup.UIRoot)|' "$node_f"
    # TAP_DESC_STALE_CLEAR: shown_desc is the toggle's memory of "whose
    # description is on screen"; tapping that card again means dismiss. The
    # tap branches clear it, but every OTHER dismissal path (tap empty space,
    # play a hand, state change) closes the popup via Node:stop_hover and
    # left shown_desc stale — so the next tap on the same card took the
    # dismiss branch and the description could not be re-summoned without
    # tapping a different card first. Clear it where the popup actually dies
    # (inside the removal branch, so Cryptid's force_tooltips exception —
    # popup kept — correctly keeps the state too).
    sed -i 's|^        self.children.h_popup = nil$|        self.children.h_popup = nil\n        if G.CONTROLLER and G.CONTROLLER.shown_desc == self then G.CONTROLLER.shown_desc = nil end -- TAP_DESC_STALE_CLEAR|' "$node_f"
    # TAP_DESC_REHOVER: the controller only calls hover() (which creates the
    # description popup) when hovering.target CHANGES. After an in-place
    # press-release on a draggable card, hovering.target stays pointed at
    # that card with hover.is dead — re-pressing the SAME card is "no change"
    # so hover() never re-fires and the description cannot be shown twice in
    # a row (trace-confirmed on-device 2026-06-10: j_cry_coin, first press
    # popup up, second press hover re-acquired, no popup). Clearing the
    # STALE hovering.target on such a press makes the per-frame prev-stamp
    # (which runs AFTER press processing — prev_target itself gets
    # overwritten, so nil'ing prev directly is a no-op) propagate nil, and
    # the normal acquisition then re-assigns the card as a genuine change,
    # re-firing hover() through the MIN_HOVER_TIME path.
    # The popup-absence guard is load-bearing: pressing a card whose
    # description IS up (the dismiss gesture) must NOT re-fire hover() —
    # Card:hover would create a second popup over the live one, orphaning it
    # (UIBox leak; observed on-device 2026-06-10 as ~200MB heap growth and
    # ~30fps after an evening of description taps).
    sed -i 's|    local press_node =  (self.HID.touch and self.cursor_hover.target) or self.hovering.target or self.focused.target|    local press_node =  (self.HID.touch and self.cursor_hover.target) or self.hovering.target or self.focused.target\n    if press_node and press_node == self.hovering.target and not press_node.states.hover.is and not (press_node.children and press_node.children.h_popup) then self.hovering.target = nil end -- TAP_DESC_REHOVER|' "$f"
    # pending-release ownership: Node:remove nils the controller's
    # released_on.target when the node dies — correct cleanup, EXCEPT while a
    # release dispatch is still pending (handled == false) in the same frame.
    # Sticky-fingers' drag-to-buy targets are destroyed by drag-end cleanup
    # BEFORE the released_on dispatch runs: vanilla then crashed (the booster
    # Pull crash), and with RELEASED_ON_NIL_GUARD alone the buy was silently
    # swallowed (trace-confirmed: G_REL_SKIP on v_cry_double_vision drag-buys).
    # A pending dispatch owns the reference: the node is already removed from
    # the world, but its release callback acts on the CARD, so dispatching on
    # the removed node is exactly the intended behavior. The nil-guard stays
    # for genuinely never-registered releases.
    sed -i 's|    if G.CONTROLLER.released_on.target ==self then |    if G.CONTROLLER.released_on.target ==self and G.CONTROLLER.released_on.handled then -- RELEASED_ON_PENDING_KEEP |' "$node_f"
    # drag-release unhover: the released_on path nils hovering.target after
    # stop_hover(), but stop_hover only removes the popup — it never clears
    # states.hover.is. The card is orphaned with hover.is stuck true, and its
    # 3D tilt stays anchored to the live cursor forever (THE warp). On desktop
    # the next mouse-over re-acquires and heals it; on touch nothing ever does.
    # Clear the flag in the same breath. (Found by test/controller/fuzz.lua;
    # minimal repro in test/controller/min-repro.lua.)
    sed -i 's|if self.dragging.prev_target == self.hovering.target then self.hovering.target:stop_hover();self.hovering.target = nil end|if self.dragging.prev_target == self.hovering.target then self.hovering.target.states.hover.is = false; self.hovering.target:stop_hover();self.hovering.target = nil end -- DRAG_RELEASE_UNHOVER|' "$f"
    # released_on dispatch liveness guard: vanilla only sets released_on.handled
    # = false in the branch that assigns a valid target, but Node:remove nils
    # the controller's released_on.target when that node is destroyed (its own
    # cleanup contract) without setting handled — anything that removes nodes
    # between assignment and dispatch (clicked:click(), drag targets being
    # destroyed at drag end — sticky-fingers Pull creates temp drag-target
    # nodes per drag in the booster screen) leaves handled=false with a nil
    # target and the dispatch crashes (seen on device: controller.lua:444 in
    # SMODS_BOOSTER_OPENED). A dead release target means there is nothing to
    # dispatch to; skip it.
    sed -i 's|        self.released_on.target:release(self.dragging.prev_target)|        if self.released_on.target then self.released_on.target:release(self.dragging.prev_target) else local _k = tostring(self.dragging.prev_target and self.dragging.prev_target.config and self.dragging.prev_target.config.center and self.dragging.prev_target.config.center.key or "?"); if ATLOG then ATLOG("G_REL_SKIP", {card=_k, state=tostring(G.STATE)}) else print("[TEL] G_REL_SKIP card=" .. _k .. " state=" .. tostring(G.STATE)) end end -- RELEASED_ON_NIL_GUARD|' "$f"
    if grep -q "TAP_DESC_PERSIST" "$f" && grep -q "TAP_DESC_TOGGLE" "$f" && grep -q "TAP_DESC_RELAX" "$f" && grep -q "TAP_DESC_HOLD_NODRAG" "$f" && grep -q "HID_TOUCH_ENV" "$f" && grep -q "DRAG_RELEASE_UNHOVER" "$f" && grep -q "TOUCH_PRESS_POS_SYNC" "$f" && grep -q "TAP_DESC_REHOVER" "$f" && grep -q "TAP_DESC_STALE_CLEAR" "$node_f"; then
        log_success "Tap-description persist + toggle + no-warp + hold-persist + touch_env + drag-release-unhover + press-pos-sync + stale-clear applied"
    else
        log_warn "Tap-description fix did not fully match — check controller.lua/node.lua"
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
    sed -i 's|self.cursor_down = {T = {x=0, y=0}, target = nil, time = 0, handled = true}|self.cursor_down = {T = {x=0, y=0}, target = nil, time = 0, handled = true}\nself.dragSelectActive = {active = false, mode = nil, start_card = nil} -- DRAG_SELECT_INIT|' "$ctrl"
    # 2b) card-start slides: a touch press on a HAND card no longer picks the
    #     card up for reorder immediately — pickup is deferred to a ~0.1s hold
    #     (DRAG_SELECT_HOLD_REORDER below). Until then the press is an armed
    #     slide: crossing onto a neighbor begins a multi-select sweep. Quick
    #     tap (select) and hold-for-description are unchanged. Other areas
    #     (jokers/consumables) keep instant pickup.
    sed -i 's|        if self.cursor_down.target.states.drag.can then|        if self.cursor_down.target.states.drag.can and not ((self.HID.touch or self.HID.touch_env) and G.SETTINGS.enable_drag_select and self.cursor_down.target.area == G.hand) then -- DRAG_SELECT_CARD_START: hand pickup deferred to hold|' "$ctrl"
    # 3) activate on a touch that starts on empty space with nothing being
    #    dragged, OR on a hand card (card-start slide; start_card remembered so
    #    the sweep can seed its mode from it)
    sed -i 's|^        self.cursor_down.handled = true$|        if (self.HID.touch or self.HID.touch_env) and not self.dragging.target and #self.collision_list == 0 and G.SETTINGS.enable_drag_select then self.dragSelectActive.active = true end -- DRAG_SELECT_ACTIVATE\n        if (self.HID.touch or self.HID.touch_env) and G.SETTINGS.enable_drag_select and not self.dragging.target and self.cursor_down.target and self.cursor_down.target.area == G.hand and self.cursor_down.target.states.hover.can then self.dragSelectActive.active = true; self.dragSelectActive.start_card = self.cursor_down.target end -- DRAG_SELECT_CARD_START\n        self.cursor_down.handled = true|' "$ctrl"
    # 4) reset on touch release
    sed -i 's|    if not self.cursor_up.handled then |    if not self.cursor_up.handled then \n        self.dragSelectActive.active = false; self.dragSelectActive.mode = nil; self.dragSelectActive.start_card = nil -- DRAG_SELECT_RESET|' "$ctrl"
    # 4b) a card-start press held ~0.1s without sweeping becomes the vanilla
    #     reorder pickup (the deferral from 2b ends; same threshold as the
    #     description hold, so "hold then drag" reorders while the description
    #     shows — matching the pre-existing hold feel)
    sed -i "s|^    self.dragging.prev_target = self.dragging.target$|    if self.dragSelectActive.active and self.dragSelectActive.start_card and not self.dragSelectActive.mode and self.is_cursor_down and (self.cursor_down.duration or 0) >= 0.1 then -- DRAG_SELECT_HOLD_REORDER\n        local _sc = self.dragSelectActive.start_card\n        self.dragSelectActive.active = false; self.dragSelectActive.start_card = nil\n        if _sc.states.drag.can and not _sc.REMOVED then\n            _sc.states.drag.is = true\n            _sc:set_offset(self.cursor_down.T, 'Click')\n            self.dragging.target = _sc\n            self.dragging.handled = false\n        end\n    end\n    self.dragging.prev_target = self.dragging.target|" "$ctrl"
    # 5) per-frame while active: highlight/unhighlight the closest hand card
    #    under the finger. Empty-space starts: the first card touched seeds the
    #    mode. Card starts: nothing toggles until the finger crosses OFF the
    #    start card (so tap/hold semantics survive); the first crossing seeds
    #    the mode from the start card's state and toggles it plus the new card.
    #    The hand's selection limit is enforced by add_to_highlighted (no-op
    #    when over limit).
    sed -i 's|    --Cursor is currently hovering over something|    if (self.HID.touch or self.HID.touch_env) and self.dragSelectActive.active then -- DRAG_SELECT_LOOP\n        local distance = math.huge; local closest = nil\n        for _, v in ipairs(self.collision_list) do\n            local cur_distance = Vector_Dist(self.cursor_hover.T, v.T)\n            if v.area ~= nil and v.area.config.type == "hand" and v.states.hover.can and (not v.states.drag.is) and (v ~= self.dragging.prev_target) and cur_distance < distance then\n                closest = v; distance = cur_distance\n            end\n        end\n        local _start = self.dragSelectActive.start_card\n        if closest and _start and not self.dragSelectActive.mode and closest ~= _start then -- DRAG_SELECT_CARD_START sweep begins\n            self.dragSelectActive.mode = _start.highlighted and "deselect" or "select"\n            if _start.highlighted then _start.area:remove_from_highlighted(_start) else _start.area:add_to_highlighted(_start) end\n        end\n        if closest and (closest ~= _start or self.dragSelectActive.mode) and (not self.dragSelectActive.mode or self.dragSelectActive.mode == "select" and not closest.highlighted or self.dragSelectActive.mode == "deselect" and closest.highlighted) then\n            if closest.highlighted then closest.area:remove_from_highlighted(closest); self.dragSelectActive.mode = "deselect"\n            else closest.area:add_to_highlighted(closest); self.dragSelectActive.mode = "select" end\n        end\n    end\n    --Cursor is currently hovering over something|' "$ctrl"
    # 6) toggle in Settings > Game (after the Debug Overlay toggle)
    sed -i "s|create_toggle({label = \"Debug Overlay\", ref_table = G.SETTINGS, ref_value = 'perf_mode'}),|create_toggle({label = \"Debug Overlay\", ref_table = G.SETTINGS, ref_value = 'perf_mode'}),\n      create_toggle({label = \"Slide to select cards\", ref_table = G.SETTINGS, ref_value = 'enable_drag_select'}),|" "$ui_file"
    if grep -q "DRAG_SELECT_LOOP" "$ctrl" && grep -q "DRAG_SELECT_ACTIVATE" "$ctrl" && grep -q "DRAG_SELECT_CARD_START" "$ctrl" && grep -q "DRAG_SELECT_HOLD_REORDER" "$ctrl" && grep -q "enable_drag_select" "$ui_file"; then
        log_success "Drag-select (slide to select, incl. card-start sweeps + hold-to-reorder) applied"
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
# Audit correction (2026-06-10): ability fields CAN be OmegaNum after Talisman
# save round-trips / Cryptid ascension — bare comparisons crash. tb_* helpers used.
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
    # Type-safe rework: the ORIGINAL elimination replaced to_big(a) OP
    # to_big(b) with bare a OP b on the claim that ability fields are always
    # plain numbers — FALSE: Talisman save round-trips and Cryptid ascension
    # leave Big tables in ability fields (device crash: 'attempt to compare
    # number with table', Bootstraps x_mult 1.2 as OmegaNum vs plain 1). The
    # tb_* helpers keep the optimization's win (plain x plain compares
    # allocate nothing) and fall back to exact to_big coercion for any Big
    # operand. Mixed ARITHMETIC (a - b, a / b) stays bare: OmegaNum's
    # arithmetic metamethods coerce plain operands on either side, unlike
    # Lua 5.1 comparisons.
    python3 - "$f" <<'PYEOF'
import sys
path = sys.argv[1]
text = open(path).read()

helpers = """-- CARD_TO_BIG_ELIM: type-safe comparison fast paths, shared globally
-- (Cryptid item appliers use them too — card.lua loads before mods)
function tb_lt(a, b) if type(a) == 'number' and type(b) == 'number' then return a < b end return to_big(a) < to_big(b) end
function tb_le(a, b) if type(a) == 'number' and type(b) == 'number' then return a <= b end return to_big(a) <= to_big(b) end
function tb_gt(a, b) if type(a) == 'number' and type(b) == 'number' then return a > b end return to_big(a) > to_big(b) end
function tb_ge(a, b) if type(a) == 'number' and type(b) == 'number' then return a >= b end return to_big(a) >= to_big(b) end
function tb_ne(a, b) if type(a) == 'number' and type(b) == 'number' then return a ~= b end return to_big(a) ~= to_big(b) end

"""

reps = [
    ('if to_big(self.ability.extra) < to_big(1) then self.ability.extra = 1 end',
     'if tb_lt(self.ability.extra, 1) then self.ability.extra = 1 end', None),
    ('if to_big(self.ability.x_mult) - to_big(self.ability.extra) <= to_big(1) then',
     'if tb_le(self.ability.x_mult - self.ability.extra, 1) then', None),
    ('to_big(G.GAME.chips)/G.GAME.blind.chips >= to_big(0.25)',
     'tb_ge(G.GAME.chips / G.GAME.blind.chips, 0.25)', None),
    ('to_big(self.ability.x_mult) > to_big(1)',
     'tb_gt(self.ability.x_mult, 1)', 'g'),
    ('to_big(self.ability.t_mult) > to_big(0)',
     'tb_gt(self.ability.t_mult, 0)', 'g'),
    ('to_big(self.ability.t_chips) > to_big(0)',
     'tb_gt(self.ability.t_chips, 0)', 'g'),
    ('to_big(self.ability.mult) > to_big(0)',
     'tb_gt(self.ability.mult, 0)', 'g'),
    ('to_big(G.GAME.dollars) <= to_big(self.ability.extra)',
     'tb_le(G.GAME.dollars, self.ability.extra)', None),
    ('to_big(self.ability.extra.chips) > to_big(0)',
     'tb_gt(self.ability.extra.chips, 0)', 'g'),
    ('to_big(G.GAME.dollars + (G.GAME.dollar_buffer or 0)) > to_big(0)',
     'tb_gt(G.GAME.dollars + (G.GAME.dollar_buffer or 0), 0)', None),
    ('to_big(math.floor((G.GAME.dollars + (G.GAME.dollar_buffer or 0))/self.ability.extra.dollars)) >= to_big(1)',
     'tb_ge(math.floor((G.GAME.dollars + (G.GAME.dollar_buffer or 0))/self.ability.extra.dollars), 1)', None),
    ('to_big(self.ability.caino_xmult) > to_big(1)',
     'tb_gt(self.ability.caino_xmult, 1)', None),
]

total = 0
for old, new, mode in reps:
    n = text.count(old)
    if n == 0:
        print(f'WARNING: anchor not found: {old[:60]}...', file=sys.stderr)
        continue
    text = text.replace(old, new) if mode == 'g' else text.replace(old, new, 1)
    total += n if mode == 'g' else 1

open(path, 'w').write(helpers + text)
print(f'card.lua to_big elimination applied type-safe ({total} sites -> tb_* helpers)')
PYEOF
    if grep -q "CARD_TO_BIG_ELIM" "$f" && grep -q "tb_gt(self.ability.x_mult, 1)" "$f"; then
        log_success "card.lua to_big elimination applied (type-safe: plain fast path, Big fallback)"
    else
        log_warn "card.lua to_big elimination did not apply — check card.lua"
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
     'tb_gt(card.ability.extra.stat2, 1)'),
    (r'to_big\(card\.ability\.extra\.money\) > to_big\(0\)',
     'tb_gt(card.ability.extra.money, 0)'),
    (r'to_big\(card\.ability\.extra\.chips\) > to_big\(0\)',
     'tb_gt(card.ability.extra.chips, 0)'),
    (r'to_big\(card\.ability\.extra\.x_mult\) > to_big\(1\)',
     'tb_gt(card.ability.extra.x_mult, 1)'),
    (r'to_big\(card\.ability\.extra\[mod_key\]\) > to_big\(1\)',
     'tb_gt(card.ability.extra[mod_key], 1)'),
    (r'to_big\(card\.ability\.extra\.rounds_remaining\) > to_big\(0\)',
     'tb_gt(card.ability.extra.rounds_remaining, 0)'),
    (r'to_big\(bonus\) > to_big\(0\)',
     'tb_gt(bonus, 0)'),
    (r'to_big\(card\.ability\.extra\.steelenhc\) ~= to_big\(1\)',
     'tb_ne(card.ability.extra.steelenhc, 1)'),
    # exotic.lua
    (r'to_big\(card\.ability\.extra\.Emult\) > to_big\(1\)',
     'tb_gt(card.ability.extra.Emult, 1)'),
    (r'to_big\(card\.ability\.extra\.chips\) > to_big\(0\)',
     'tb_gt(card.ability.extra.chips, 0)'),
    (r'to_big\(card\.ability\.immutable\.check2\) <= to_big\(card\.ability\.extra\.check\)',
     'tb_le(card.ability.immutable.check2, card.ability.extra.check)'),
    (r'to_big\(card\.ability\.extra\.Xmult\) > to_big\(1\)',
     'tb_gt(card.ability.extra.Xmult, 1)'),
    # m.lua
    (r'to_big\(card\.ability\.extra\.mult\) > to_big\(0\)',
     'tb_gt(card.ability.extra.mult, 0)'),
    (r'to_big\(card\.ability\.extra\.rounds_remaining\) > to_big\(0\)',
     'tb_gt(card.ability.extra.rounds_remaining, 0)'),
    (r'to_big\(card\.ability\.extra\.sell\) \+ 1 >= to_big\(card\.ability\.extra\.sell_req\)',
     'tb_ge(card.ability.extra.sell + 1, card.ability.extra.sell_req)'),
    (r'to_big\(card\.ability\.extra\.retriggers\) < to_big\(1\)',
     'tb_lt(card.ability.extra.retriggers, 1)'),
    (r'to_big\(card\.ability\.extra\.money\) > to_big\(0\)',
     'tb_gt(card.ability.extra.money, 0)'),
    (r'to_big\(card\.ability\.immutable\.slots\) >= to_big\(card\.ability\.immutable\.max_slots\)',
     'tb_ge(card.ability.immutable.slots, card.ability.immutable.max_slots)'),
    (r'to_big\(card\.ability\.extra\.jollies\) < to_big\(1\)',
     'tb_lt(card.ability.extra.jollies, 1)'),
    (r'to_big\(card\.ability\.extra\.unc\) < to_big\(1\)',
     'tb_lt(card.ability.extra.unc, 1)'),
    (r'to_big\(jollycount\) > to_big\(card\.ability\.immutable\.max_jollies\)',
     'tb_gt(jollycount, card.ability.immutable.max_jollies)'),
    (r'to_big\(summon\) < to_big\(1\)',
     'tb_lt(summon, 1)'),
    (r'to_big\(card\.ability\.extra\.add\) < to_big\(1\)',
     'tb_lt(card.ability.extra.add, 1)'),
    (r'to_big\(card\.ability\.extra\.amount\) < to_big\(card\.ability\.immutable\.max_amount\)',
     'tb_lt(card.ability.extra.amount, card.ability.immutable.max_amount)'),
    (r'to_big\(card\.ability\.extra\.amount\) > to_big\(card\.ability\.immutable\.max_amount\)',
     'tb_gt(card.ability.extra.amount, card.ability.immutable.max_amount)'),
    (r'to_big\(card\.ability\.extra\.amount\) > to_big\(0\)',
     'tb_gt(card.ability.extra.amount, 0)'),
    (r'to_big\(card\.ability\.extra\.monster\) > to_big\(1\)',
     'tb_gt(card.ability.extra.monster, 1)'),
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
        self.F_QUIT_BUTTON = false  -- Android has no meaningful quit; users swipe out (matches Switch/PS behavior)\
        -- FPS_CAP_DISPLAY: the run loop defaults G.FPS_CAP to 500 and vsync\
        -- does not engage on this device — light scenes burned 240 fps on a\
        -- 120 Hz panel (pure battery drain). Cap at the panel refresh rate.\
        local _, _, _wflags = love.window.getMode()\
        self.FPS_CAP = (_wflags and _wflags.refreshrate and _wflags.refreshrate > 0) and _wflags.refreshrate or 120\
    end
    }' "$globals_file"

    log_success "Android settings fix applied"
}

# Patch G.FUNCS.quit in button_callbacks.lua:
# DRAG_SELF_DROP_EXCLUDE: vouchers carry an attached redeem-button UIBox that
# travels with the card during a drag and is drawn on top of everything at the
# finger. The drop-resolution collision walk (TAP_DESC_HOLD_NODRAG block)
# picked it over the sticky-fingers buy zone beneath whenever the release
# happened with the finger over the card's lower strip — so voucher drag-buys
# failed position-dependently (trace: G_RELON t=node<v_cry_double_vision on a
# full 8.4-unit drag; jokers always worked because their price tags are not
# hoverable). Releasing a card onto its OWN descendants is never the intent:
# exclude the dragged card's subtree from drop resolution.
apply_drag_self_drop_exclude() {
    local f="$1"
    if grep -q "DRAG_SELF_DROP_EXCLUDE" "$f"; then
        log_info "Drag self-drop exclude already applied"
        return 0
    fi
    sed -i 's|                local releasable = nil|                local releasable = nil\n                local _drag_anc = function(n) local p, d = n.parent, 0 while p and d < 8 do if p == self.dragging.prev_target then return true end p = p.parent; d = d + 1 end return false end -- DRAG_SELF_DROP_EXCLUDE|' "$f"
    sed -i 's|                    if v.states.hover.can and (not v.states.drag.is) and (v ~= self.dragging.prev_target) then|                    if v.states.hover.can and (not v.states.drag.is) and (v ~= self.dragging.prev_target) and not _drag_anc(v) then -- DRAG_SELF_DROP_EXCLUDE|' "$f"
    if [[ $(grep -c "DRAG_SELF_DROP_EXCLUDE" "$f") -ge 2 ]]; then
        log_success "Drag self-drop exclude applied (dragged card's own UI cannot steal its drop)"
    else
        log_error "Drag self-drop exclude did not fully apply — check anchors"
        exit 1
    fi
}

# UI_COLOUR_GUARD: engine/ui.lua draw_self indexes self.config.colour[4]
# unconditionally; an element created without a colour crashes the whole draw
# loop (hit on-device 2026-06-10 at ante 9: 'attempt to index field colour',
# button_active=true, creator unknown). Never let one cosmetic field kill the
# game: default the colour to transparent and NAME the element through ATLOG
# so the creating site can be fixed at the root when it next occurs.
apply_ui_colour_guard() {
    local f="$1"
    if grep -q "UI_COLOUR_GUARD" "$f"; then
        log_info "UI colour guard already applied"
        return 0
    fi
    sed -i 's|    if self.config.colour\[4\] > 0.01 then|    if not self.config.colour then -- UI_COLOUR_GUARD\n        if ATLOG then ATLOG("UI_NIL_COLOUR", {btn = tostring(self.config.button), id = tostring(self.config.id), ut = tostring(self.UIT), pk = tostring(self.parent and self.parent.config and (self.parent.config.button or self.parent.config.id))}) end\n        self.config.colour = {0, 0, 0, 0}\n    end\n    if self.config.colour[4] > 0.01 then|' "$f"
    if grep -q "UI_COLOUR_GUARD" "$f"; then
        log_success "UI colour guard applied (colourless elements draw transparent + self-identify)"
    else
        log_error "UI colour guard did not apply — check anchor"
        exit 1
    fi
}

# DRAG_REJECT_FEEDBACK: sticky-fingers' drag-drop targets silently no-op when
# the drop is rejected (check_drag_target_active sets release_func=nil while
# the card is unaffordable). On a touchscreen that silence is
# indistinguishable from "drag-to-buy is broken" — proven live 2026-06-10
# when a Crystal Ball drag at $9 against a $10 cost did nothing and read as a
# bug. Replace the nil with a loud-reject: cancel sound, card shake, and the
# unmet cost flashed in red over the card.
apply_drag_reject_feedback() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "button_callbacks.lua not found, skipping drag reject feedback"
        return 0
    fi
    if grep -q "DRAG_REJECT_FEEDBACK" "$f"; then
        log_info "Drag reject feedback already applied"
        return 0
    fi
    python3 - "$f" <<'PYEOF'
import sys
path = sys.argv[1]
text = open(path).read()

old = "      e.config.release_func = nil"
new = """      -- DRAG_REJECT_FEEDBACK: reject drops loudly instead of doing nothing
      e.config.release_func = function(other)
        play_sound('cancel', 0.7, 0.6)
        if other and other.juice_up then other:juice_up(0.3, 0.2) end
        if other and other.cost and other.cost > 0 then
          attention_text({scale = 0.9, text = localize('$')..other.cost, hold = 0.7, align = 'cm', offset = {x = 0, y = -1.2}, major = other, colour = G.C.RED})
        end
      end"""

if old not in text:
    print("ERROR: release_func=nil anchor not found in " + path, file=sys.stderr)
    sys.exit(1)
if text.count(old) != 1:
    print("ERROR: release_func=nil anchor not unique in " + path, file=sys.stderr)
    sys.exit(1)

open(path, 'w').write(text.replace(old, new, 1))
print("drag reject feedback applied")
PYEOF
    if grep -q "DRAG_REJECT_FEEDBACK" "$f"; then
        log_success "Drag reject feedback applied (rejected drops: cancel sound + shake + cost flash)"
    else
        log_error "Drag reject feedback did not apply — check anchor"
        exit 1
    fi
}

#   1. Hide the Quit button on Android (F_QUIT_BUTTON already false from
#      apply_android_settings_fix, but the callback fix is platform-agnostic).
#   2. Flush staged FILE_HANDLER saves before exiting so queued async writes
#      (save_progress, save_settings) are not lost when the process exits.
#      The bare love.event.quit() call skips the save-dispatch window in
#      Game:update(); defer quit by one frame via E_MANAGER so the forced
#      FILE_HANDLER flush can dispatch through the normal update path.
apply_android_quit_fix() {
    local callbacks_file="$1"

    if [[ ! -f "$callbacks_file" ]]; then
        log_warn "button_callbacks.lua not found, skipping quit fix"
        return
    fi

    if grep -q "QUIT_FLUSH" "$callbacks_file"; then
        log_info "Quit flush already patched"
        return
    fi

    log_info "Patching G.FUNCS.quit for safe save-flush..."

    # Replace the bare love.event.quit() call with a flush-then-defer pattern.
    # Use Python for multiline replacement (sed multiline is fragile across platforms).
    python3 - "$callbacks_file" << 'PYEOF'
import sys, re

path = sys.argv[1]
content = open(path, 'r', encoding='utf-8').read()

old = (
    'G.FUNCS.quit = function(e)\n'
    '  love.event.quit()\n'
    'end'
)
new = (
    'G.FUNCS.quit = function(e) -- QUIT_FLUSH\n'
    '  -- Flush any staged save work before the Lua loop exits.\n'
    '  -- save_progress() marks FILE_HANDLER dirty; the channel push happens in\n'
    '  -- Game:update(). force=true makes it dispatch on the very next tick\n'
    '  -- instead of waiting for the 30s F_SAVE_TIMER. Defer quit by one frame\n'
    '  -- so that update tick fires before the loop returns.\n'
    '  G:save_progress()\n'
    '  if G.FILE_HANDLER then G.FILE_HANDLER.force = true end\n'
    '  G.E_MANAGER:add_event(Event({\n'
    '    trigger = \'after\', delay = 0.05, blockable = false, blocking = false,\n'
    '    func = function() love.event.quit() return true end\n'
    '  }))\n'
    'end'
)

if old not in content:
    print("WARNING: G.FUNCS.quit anchor not found — quit flush NOT patched")
    sys.exit(0)

content = content.replace(old, new, 1)
open(path, 'w', encoding='utf-8').write(content)
print("G.FUNCS.quit patched.")
PYEOF

    log_success "Quit flush fix applied"
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

    # Copy to app's internal storage — preserving on-device mod CONFIG files:
    # Talisman (and any mod using an in-folder config.lua) writes user settings
    # into files/save/Mods/<mod>/config.lua, and a blind cp -r clobbers them on
    # every deploy (Joe's Talisman settings were wiped ~10x in one night).
    # Back configs up before the overlay, restore after.
    adb shell "run-as $INSTALLED_PACKAGE_ID mkdir -p files/save"
    adb shell "run-as $INSTALLED_PACKAGE_ID sh -c 'cd files/save 2>/dev/null && find Mods -name config.lua 2>/dev/null | while read f; do cp \"\$f\" \"\$f.keep\"; done'" 2>/dev/null || true
    adb shell "run-as $INSTALLED_PACKAGE_ID cp -r $temp_dir/* files/save/"
    adb shell "run-as $INSTALLED_PACKAGE_ID sh -c 'cd files/save 2>/dev/null && find Mods -name config.lua.keep 2>/dev/null | while read f; do mv \"\$f\" \"\${f%.keep}\"; done'" 2>/dev/null || true

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
    # Nudge the panel to its full refresh rate before the window is created:
    # G.FPS_CAP reads the rate once at boot, and an idle LTPO panel reports
    # 60Hz — a deploy-launched session then runs at half rate until manual
    # restart (proven via telemetry 2026-06-10; runtime re-reads don't work,
    # see the FPS-cap note in patch_main_lua.py). A wakeup + harmless swipe on
    # the navigation area upshifts the panel the same way a human launch does.
    adb shell input keyevent KEYCODE_WAKEUP
    adb shell input swipe 100 10 110 10 50
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

# Tier-2 item 8 (re-scoped): a GLOBAL to_big fast-path returning plain numbers
# below a threshold is UNWORKABLE — LÖVE's LuaJIT has no 5.2-compat (verified:
# mixed number<table comparison errors before the metamethod), and scoring
# routinely compares small values against huge stored Bigs. The safe shape is
# site-level: type-aware sign/zero helpers for the per-trigger hot path.
# 14 sites: 8 in card_eval_status_text (per scoring trigger) + 6 to_big(mod)<0
# checks in the HUD chip/mult updaters (per scoring tick). Differential-tested
# against real OmegaNum across the 0 / -0.01 / 1e15 / 1e300 boundaries in both
# plain and Big forms. Isolated alloc (GC stopped): 16417 -> 1 KB per 30k
# checks; ~300 KB less garbage per scored hand at 550+ checks/hand.
apply_ces_sign_fast() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "common_events.lua not found, skipping sign-check fast helpers"
        return 0
    fi
    if grep -q "CES_SIGN_FAST" "$f"; then
        log_info "sign-check fast helpers already applied"
        return 0
    fi
    python3 - "$f" <<'PYEOF'
import sys
path = sys.argv[1]
text = open(path).read()

helpers = """-- CES_SIGN_FAST: sign/zero checks against constants without the paired
-- to_big() allocations. These run per scoring trigger (card_eval_status_text)
-- and per HUD chip/mult update — together 550+ times per scored hand. amt/mod
-- is a plain Lua number at normal chip scale (lenient_bignum keeps values
-- plain below 1e300): that path allocates nothing. The non-number path keeps
-- the exact to_big coercion semantics but compares against a cached Big
-- constant, halving its allocations. Mixed plain/Big comparison is never
-- attempted (LuaJIT 5.1 semantics reject it).
local CES_BIG_ZERO, CES_BIG_NEGP
local function ces_is_neg(x)
    if type(x) == 'number' then return x < 0 end
    CES_BIG_ZERO = CES_BIG_ZERO or to_big(0)
    return to_big(x) < CES_BIG_ZERO
end
local function ces_lt_negp(x)
    if type(x) == 'number' then return x < -0.01 end
    CES_BIG_NEGP = CES_BIG_NEGP or to_big(-0.01)
    return to_big(x) < CES_BIG_NEGP
end
local function ces_nonzero(x)
    if type(x) == 'number' then return x ~= 0 end
    CES_BIG_ZERO = CES_BIG_ZERO or to_big(0)
    return to_big(x) ~= CES_BIG_ZERO
end

"""

reps = [
    ('to_big(mod) < to_big(0)', 'ces_is_neg(mod)', 6),
    ('to_big(amt)<to_big(0)', 'ces_is_neg(amt)', 5),
    ('to_big(amt) < to_big(-0.01)', 'ces_lt_negp(amt)', 2),
    ('to_big(amt) ~= to_big(0)', 'ces_nonzero(amt)', 1),
]
for old, new, expected in reps:
    n = text.count(old)
    if n != expected:
        print(f'ERROR: expected {expected} of "{old}", found {n}', file=sys.stderr)
        sys.exit(1)
    text = text.replace(old, new)

open(path, 'w').write(helpers + text)
print('CES_SIGN_FAST applied (14 paired to_big sign checks -> allocation-free helpers)')
PYEOF
    if grep -q "CES_SIGN_FAST" "$f"; then
        log_success "sign-check fast helpers applied (14 hot paired-to_big sites, plain path allocation-free)"
    else
        log_warn "sign-check fast helpers did not apply — check common_events.lua"
    fi
}

# DynaText creates a love Text GPU object PER GLYPH on every update_text —
# 300-380/sec during scoring animations, and a chunk of the description-popup
# open cost. The objects are immutable after creation (both draw sites pass
# them straight to love.graphics.draw; no :set()/:add(); no DynaTextEffect is
# registered in this modpack), so they are shared via a permanent (font, char)
# cache — content-addressed, staleness impossible, bounded by distinct glyphs
# per session. Measured: joker description open 3.14 -> 2.50 ms (-20%).
apply_dynatext_glyph_cache() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "engine/text.lua not found, skipping glyph cache"
        return 0
    fi
    if grep -q "DYNATEXT_GLYPH_CACHE" "$f"; then
        log_info "DynaText glyph cache already applied"
        return 0
    fi
    python3 - "$f" <<'PYEOF'
import sys
path = sys.argv[1]
text = open(path).read()

header = """-- DYNATEXT_GLYPH_CACHE: love Text objects are immutable after creation here
-- (both draw sites pass them straight to love.graphics.draw with per-letter
-- transforms; no :set()/:add() anywhere, and no DynaTextEffect is registered
-- in this modpack). Cache them per (font, char): DynaText rebuilds its letter
-- tables on every update_text — 300-380 newText GPU objects/sec during
-- scoring and a large share of the popup-open cost. Distinct glyphs per
-- session are bounded (a few hundred), so the cache is permanent by design.
local GLYPH_CACHE = {}
local function cached_glyph(font, c)
    local fc = GLYPH_CACHE[font]
    if not fc then fc = {}; GLYPH_CACHE[font] = fc end
    local g = fc[c]
    if not g then g = love.graphics.newText(font, c); fc[c] = g end
    return g
end

"""

old = "                    local let_tab = {letter = love.graphics.newText(self.font.FONT, c), char = c, scale = old_letter and old_letter.scale or part_scale}"
new = "                    local let_tab = {letter = cached_glyph(self.font.FONT, c), char = c, scale = old_letter and old_letter.scale or part_scale} -- DYNATEXT_GLYPH_CACHE"

if old not in text:
    print('ERROR: newText anchor not found', file=sys.stderr); sys.exit(1)

open(path, 'w').write(header + text.replace(old, new, 1))
print('DynaText glyph cache applied')
PYEOF
    if grep -q "DYNATEXT_GLYPH_CACHE" "$f"; then
        log_success "DynaText glyph cache applied (shared Text objects per font+char)"
    else
        log_warn "DynaText glyph cache did not apply — check engine/text.lua"
    fi
}

# Talisman config persistence on Android. talisman.lua reads and writes its
# settings using love.filesystem (TAL_CONFIG_PERSIST routes through it) with
# the relative path "Mods/Talisman/config.lua". love.filesystem.write resolves
# that into the writable save dir (files/save/game/Mods/Talisman/config.lua),
# and the read checks the save dir before the embedded archive so the written
# copy naturally shadows defaults on the next boot.
#
# Root cause of the reset: love.filesystem.write does NOT auto-create
# intermediate directories. files/save/game/Mods/Talisman/ only exists inside
# the read-only game.love archive, never as a real directory in the save dir
# unless explicitly created. Without createDirectory the write silently returns
# false (pcall swallows it) and settings reset on every restart.
# Fix: inject love.filesystem.createDirectory(talisman_path) on Android right
# before the first read so the directory exists before any write attempt.
apply_talisman_config_persist() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "talisman.lua not found, skipping config persistence fix"
        return 0
    fi
    if grep -q "TAL_CONFIG_PERSIST" "$f"; then
        log_info "Talisman config persistence already applied"
        return 0
    fi
    # 1. Ensure the save-dir directory exists before any read/write attempt.
    #    Inject createDirectory right before the config_read_result line.
    sed -i 's|local config_read_result = nativefs.read(talisman_path.."/config.lua")|if love.system.getOS() == "Android" then love.filesystem.createDirectory("Mods") love.filesystem.createDirectory(talisman_path) end -- TAL_CONFIG_PERSIST\nlocal config_read_result = (love.system.getOS() == "Android" and love.filesystem.read or nativefs.read)(talisman_path.."/config.lua") -- TAL_CONFIG_PERSIST|' "$f"
    # 2. Route all write sites through love.filesystem on Android.
    sed -i 's|nativefs.write(talisman_path .. "/config.lua", STR_PACK(Talisman.config_file))|do local _w = love.system.getOS() == "Android" and love.filesystem.write or nativefs.write; _w(talisman_path .. "/config.lua", STR_PACK(Talisman.config_file)) end -- TAL_CONFIG_PERSIST|g' "$f"
    # 2b. TAL_BREAKINF_CLAMP (same clamp as the main.lua layer — see
    #     apply_android_smods_path_fix): a persisted break_infinity="" crash-loops
    #     boot; this pack requires omeganum.
    sed -i 's|Talisman.config_file = STR_UNPACK(config_read_result)|do local _ok, _cfg = pcall(STR_UNPACK, config_read_result) if _ok and type(_cfg) == "table" then Talisman.config_file = _cfg end end -- TAL_CFG_SAFE_UNPACK: a truncated config (process killed mid-write) must not crash boot\n    if Talisman.config_file.break_infinity ~= "omeganum" then Talisman.config_file.break_infinity = "omeganum" Talisman.config_file.score_opt_id = 2 end -- TAL_BREAKINF_CLAMP|' "$f"
    local n
    n=$(grep -c "TAL_CONFIG_PERSIST" "$f")
    if [[ "$n" -ge 4 ]]; then
        log_success "Talisman config persistence applied ($n sites -> love.filesystem on Android)"
    else
        log_warn "Talisman config persistence matched only $n/4 sites — check talisman.lua"
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

# NF_BIG_CACHE: Talisman's number_format override calls to_big(num) unconditionally
# at line 194, which always allocates a fresh OmegaNum table. The .str cache written
# on that table at line 198 is therefore on a throwaway object and can never be
# re-read on subsequent calls — every invocation for a Big value fully recomputes
# the notation format string AND pays the to_big allocation cost.
#
# DynaText polls chips/mult via ref_table/ref_value every update_text() call (once
# per frame for animated HUD displays), so this path fires at 120fps. At
# GAMESPEED=4 with Cryptid ascension active, chips/mult are persistent Big tables.
#
# Fix: branch on type(num) before touching to_big.
#   - Plain Lua number + small enough for vanilla formatter: return nf() directly
#     (eliminates the to_big allocation entirely on the dominant path).
#   - Plain Lua number + needs notation format: allocate Big once, format, no cache
#     (ephemeral number → ephemeral Big; no point caching since next call gets a
#     fresh number value anyway).
#   - Already a Big: cache the format string on the original object using a
#     notation-keyed field name ('str_'..notation) so different notations don't
#     collide and no invalidation is needed. Big objects produced by arithmetic are
#     transient so their cached field is collected with them; persistent Bigs
#     (current_hand.chips/mult) reuse the cached string on every DynaText poll.
apply_nf_big_cache() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "talisman.lua not found, skipping NF_BIG_CACHE"
        return 0
    fi
    if grep -q "NF_BIG_CACHE" "$f"; then
        log_info "NF_BIG_CACHE already applied"
        return 0
    fi
    python3 - "$f" <<'PYEOF'
import sys
path = sys.argv[1]
text = open(path).read()

old = (
    "  local nf = number_format\n"
    "  function number_format(num, e_switch_point)\n"
    "      if is_number(num) then\n"
    "          num = to_big(num)\n"
    "          if num.str then return num.str end\n"
    "          if num:arraySize() > 2 then\n"
    "            local str = Notations[Talisman.config_file.notation_key or Talisman.default_notation]:format(num, 3)\n"
    "            num.str = str\n"
    "            return str\n"
    "          end\n"
    "          G.E_SWITCH_POINT = Notations[Talisman.config_file.notation_key or Talisman.default_notation].E_SWITCH_POINT or G.E_SWITCH_POINT or 100000000000\n"
    "          if ((num or 0) < (to_big(G.E_SWITCH_POINT) or 0)) and not Notations[Talisman.config_file.notation_key or Talisman.default_notation].always_use then\n"
    "              return nf(num:to_number(), e_switch_point)\n"
    "          else\n"
    "            return Notations[Talisman.config_file.notation_key or Talisman.default_notation]:format(num, 3)\n"
    "          end\n"
    "      else return nf(num, e_switch_point) end\n"
    "  end\n"
)

new = (
    "  local nf = number_format\n"
    "  function number_format(num, e_switch_point) -- NF_BIG_CACHE\n"
    "      if is_number(num) then\n"
    "          local notation = Talisman.config_file.notation_key or Talisman.default_notation\n"
    "          local Notation  = Notations[notation]\n"
    "          if type(num) == 'number' then\n"
    "              -- Plain Lua number: avoid to_big on the small-value fast path\n"
    "              G.E_SWITCH_POINT = Notation.E_SWITCH_POINT or G.E_SWITCH_POINT or 100000000000\n"
    "              if num < G.E_SWITCH_POINT and not Notation.always_use then\n"
    "                  return nf(num, e_switch_point)\n"
    "              end\n"
    "              -- Large plain number: allocate Big once, format, return (no cache —\n"
    "              -- plain numbers change every frame so a per-object cache never hits)\n"
    "              return Notation:format(to_big(num), 3)\n"
    "          end\n"
    "          -- num is already a Big — cache the formatted string on the object itself\n"
    "          -- using a notation-keyed field so notation changes don't serve stale strings\n"
    "          local cache_key = 'str_'..notation\n"
    "          if num[cache_key] then return num[cache_key] end\n"
    "          local str\n"
    "          G.E_SWITCH_POINT = Notation.E_SWITCH_POINT or G.E_SWITCH_POINT or 100000000000\n"
    "          if num:arraySize() > 2 then\n"
    "              str = Notation:format(num, 3)\n"
    "          elseif (num < to_big(G.E_SWITCH_POINT)) and not Notation.always_use then\n"
    "              return nf(num:to_number(), e_switch_point)\n"
    "          else\n"
    "              str = Notation:format(num, 3)\n"
    "          end\n"
    "          num[cache_key] = str\n"
    "          return str\n"
    "      else return nf(num, e_switch_point) end\n"
    "  end\n"
)

if old not in text:
    print('ERROR: number_format anchor not found in talisman.lua', file=sys.stderr)
    sys.exit(1)

open(path, 'w').write(text.replace(old, new, 1))
print('NF_BIG_CACHE applied')
PYEOF
    if grep -q "NF_BIG_CACHE" "$f"; then
        log_success "NF_BIG_CACHE applied (skip to_big for small numbers; cache .str on persistent Bigs)"
    else
        log_warn "NF_BIG_CACHE did not apply — check talisman.lua number_format anchor"
    fi
}

# LETTER_TABLE_REUSE: DynaText.update_text() rebuilds self.strings[k].letters from
# scratch on every string change: each character gets a fresh let_tab table plus a
# fresh dims={x,y} sub-table (unconditionally) and a fresh offset={x,y} sub-table
# (when there is no old_letter). With animated chip/mult HUD displays at 120fps,
# this generates hundreds of small table allocations per second that feed GC
# pressure on Mali beyond what the minor collector can keep up with.
#
# Fix: reuse the old let_tab and its dims/offset sub-tables in-place when the
# same character appears at the same position (common case: "12,345,678" stays the
# same length while the number animates). New-position or new-character entries
# still allocate fresh tables. The letter.pop_in, .prefix, .suffix, .colour fields
# are cheap scalars that we just overwrite. The dims.x/y values are mutated in-
# place on the reused sub-table — safe because old_letters is a local that goes
# out of scope after the loop and the draw pass reads from the new letters array.

# NUGC_ADAPTIVE: nuGC runs every frame with a FIXED 0.3ms collection budget and
# the GC stopped otherwise (misc_functions.lua). Under Big-number churn at
# GAMESPEED=4 the budget cannot drain allocation, so the heap drifts ~1-2MB/s
# (census-confirmed: reachable-table count flat while gc_kb climbs 140->267MB)
# until the 300MB emergency full-collect fires — a multi-second hitch. Scale
# the budget with heap pressure instead: 0.3ms while healthy (<100MB), ramping
# linearly to a 4ms cap by ~174MB, with the step cap scaled to match so the
# time budget is the binding limit. At the frame times where this matters the
# extra milliseconds are invisible; while healthy nothing changes.
apply_nugc_adaptive() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "misc_functions.lua not found, skipping NUGC_ADAPTIVE"
        return 0
    fi
    if grep -q "NUGC_ADAPTIVE" "$f"; then
        log_info "NUGC_ADAPTIVE already applied"
        return 0
    fi
    python3 - "$f" <<'PYEOF'
import sys
path = sys.argv[1]
text = open(path).read()

old = (
    "\ttime_budget = time_budget or 3e-4\n"
    "\tmemory_ceiling = memory_ceiling or 300\n"
    "\tlocal max_steps = 1000\n"
)
new = (
    "\t-- NUGC_ADAPTIVE: scale the default budget with heap pressure so steady\n"
    "\t-- collection keeps pace with allocation churn instead of riding the\n"
    "\t-- memory_ceiling emergency full-collect (a multi-second hitch)\n"
    "\tif not time_budget then\n"
    "\t\tlocal mb = collectgarbage(\"count\") / 1024\n"
    "\t\ttime_budget = mb < 100 and 3e-4 or math.min(4e-3, 3e-4 + (mb - 100) * 5e-5)\n"
    "\tend\n"
    "\tmemory_ceiling = memory_ceiling or 300\n"
    "\tlocal max_steps = math.max(1000, math.ceil(time_budget * 3.4e6))\n"
)

if old not in text:
    print("ERROR: nuGC budget anchor not found", file=sys.stderr)
    sys.exit(1)
if text.count(old) != 1:
    print("ERROR: nuGC budget anchor not unique", file=sys.stderr)
    sys.exit(1)

open(path, 'w').write(text.replace(old, new, 1))
print("NUGC_ADAPTIVE applied")
PYEOF
    if grep -q "NUGC_ADAPTIVE" "$f"; then
        log_success "NUGC_ADAPTIVE applied (GC budget scales 0.3ms -> 4ms with heap pressure)"
    else
        log_error "NUGC_ADAPTIVE did not apply — check nuGC anchor"
        exit 1
    fi
}

apply_letter_table_reuse() {
    local f="$1"
    if [[ ! -f "$f" ]]; then
        log_warn "engine/text.lua not found, skipping LETTER_TABLE_REUSE"
        return 0
    fi
    if grep -q "LETTER_TABLE_REUSE" "$f"; then
        log_info "LETTER_TABLE_REUSE already applied"
        return 0
    fi
    python3 - "$f" <<'PYEOF'
import sys
path = sys.argv[1]
text = open(path).read()

old = (
    "                for _, c in utf8.chars(v) do\n"
    "                    local old_letter = old_letters and old_letters[current_letter] or nil\n"
    "                    local let_tab = {letter = cached_glyph(self.font.FONT, c), char = c, scale = old_letter and old_letter.scale or part_scale} -- DYNATEXT_GLYPH_CACHE\n"
    "                    self.strings[k].letters[current_letter] = let_tab\n"
    "                    local tx = self.font.FONT:getWidth(c)*self.scale*part_scale*G.TILESCALE*self.font.FONTSCALE + 2.7*(self.config.spacing or 0)*G.TILESCALE*self.font.FONTSCALE\n"
    "                    local ty = self.font.FONT:getHeight(c)*self.scale*part_scale*G.TILESCALE*self.font.FONTSCALE*self.font.TEXT_HEIGHT_SCALE\n"
    "                    let_tab.offset = old_letter and old_letter.offset or {x = 0, y = 0}\n"
    "                    let_tab.dims = {x = tx/(self.font.FONTSCALE*G.TILESCALE), y = ty/(self.font.FONTSCALE*G.TILESCALE)}\n"
)

new = (
    "                for _, c in utf8.chars(v) do\n"
    "                    local old_letter = old_letters and old_letters[current_letter] or nil\n"
    "                    -- LETTER_TABLE_REUSE: reuse the old let_tab and its sub-tables when\n"
    "                    -- the same char is at the same position (common during number animation).\n"
    "                    -- Allocates fresh tables only on first appearance or char-position mismatch.\n"
    "                    local let_tab\n"
    "                    if old_letter and old_letter.char == c then\n"
    "                        let_tab = old_letter\n"
    "                    else\n"
    "                        let_tab = {char = c}\n"
    "                        if old_letter then\n"
    "                            let_tab.scale  = old_letter.scale\n"
    "                            let_tab.offset = old_letter.offset\n"
    "                        end\n"
    "                    end\n"
    "                    let_tab.letter = cached_glyph(self.font.FONT, c) -- DYNATEXT_GLYPH_CACHE\n"
    "                    if not let_tab.scale then let_tab.scale = part_scale end\n"
    "                    self.strings[k].letters[current_letter] = let_tab\n"
    "                    local tx = self.font.FONT:getWidth(c)*self.scale*part_scale*G.TILESCALE*self.font.FONTSCALE + 2.7*(self.config.spacing or 0)*G.TILESCALE*self.font.FONTSCALE\n"
    "                    local ty = self.font.FONT:getHeight(c)*self.scale*part_scale*G.TILESCALE*self.font.FONTSCALE*self.font.TEXT_HEIGHT_SCALE\n"
    "                    if not let_tab.offset then let_tab.offset = {x = 0, y = 0} end\n"
    "                    if let_tab.dims then\n"
    "                        let_tab.dims.x = tx/(self.font.FONTSCALE*G.TILESCALE)\n"
    "                        let_tab.dims.y = ty/(self.font.FONTSCALE*G.TILESCALE)\n"
    "                    else\n"
    "                        let_tab.dims = {x = tx/(self.font.FONTSCALE*G.TILESCALE), y = ty/(self.font.FONTSCALE*G.TILESCALE)}\n"
    "                    end\n"
)

if old not in text:
    print('ERROR: letter loop anchor not found in engine/text.lua', file=sys.stderr)
    sys.exit(1)

open(path, 'w').write(text.replace(old, new, 1))
print('LETTER_TABLE_REUSE applied')
PYEOF
    if grep -q "LETTER_TABLE_REUSE" "$f"; then
        log_success "LETTER_TABLE_REUSE applied (reuse let_tab+dims in-place on char match)"
    else
        log_warn "LETTER_TABLE_REUSE did not apply — check engine/text.lua letter loop anchor"
    fi
}

main "$@"
