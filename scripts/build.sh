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

    for tool in apktool zipalign apksigner adb keytool unzip curl; do
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

    mkdir -p "$BUILD_DIR"/{apk,apktool,phone-transfer}

    # Decompile base APK
    if [[ ! -d "$BUILD_DIR/apktool/AndroidManifest.xml" ]]; then
        log_info "Decompiling base.apk..."
        apktool d -f -o "$BUILD_DIR/apktool" "$SRC_DIR/base.apk"
    fi

    # Modify AndroidManifest.xml
    log_info "Patching AndroidManifest.xml..."
    sed -i "s/package=\"[^\"]*\"/package=\"$PACKAGE_ID\"/" "$BUILD_DIR/apktool/AndroidManifest.xml"
    sed -i "s/android:label=\"[^\"]*\"/android:label=\"$APP_NAME\"/" "$BUILD_DIR/apktool/AndroidManifest.xml"

    if [[ "$DEBUGGABLE" == "true" ]]; then
        sed -i 's/android:allowBackup="true"/android:allowBackup="true" android:debuggable="true"/' "$BUILD_DIR/apktool/AndroidManifest.xml"
    fi

    # Extract Balatro.love into assets
    log_info "Extracting Balatro.love..."
    unzip -q -o "$SRC_DIR/Balatro.love" -d "$BUILD_DIR/apktool/assets/"

    # Copy lovely dump files INTO the APK (critical - game loads main.lua from APK, not save dir)
    if [[ -d "$SRC_DIR/dump" ]]; then
        log_info "Embedding lovely dump files into APK..."
        # Copy patched main.lua and other dump files
        cp -r "$SRC_DIR/dump/"*.lua "$BUILD_DIR/apktool/assets/" 2>/dev/null || true
        cp -r "$SRC_DIR/dump/"*.txt "$BUILD_DIR/apktool/assets/" 2>/dev/null || true
        cp -r "$SRC_DIR/dump/engine" "$BUILD_DIR/apktool/assets/" 2>/dev/null || true
        cp -r "$SRC_DIR/dump/functions" "$BUILD_DIR/apktool/assets/" 2>/dev/null || true
        cp -r "$SRC_DIR/dump/SMODS" "$BUILD_DIR/apktool/assets/" 2>/dev/null || true

        # Copy nativefs folder (original FFI version for non-Android)
        cp -r "$SRC_DIR/dump/nativefs" "$BUILD_DIR/apktool/assets/" 2>/dev/null || true

        # Replace top-level nativefs.lua with Android-compatible wrapper
        cp "$PATCHES_DIR/android-nativefs.lua" "$BUILD_DIR/apktool/assets/nativefs.lua"
        log_success "Dump files embedded in APK"
    else
        log_warn "No dump files found in $SRC_DIR/dump - mods won't work!"
    fi

    # Embed Mods folder in APK (so love.filesystem.read can access them)
    log_info "Embedding Mods folder in APK..."
    mkdir -p "$BUILD_DIR/apktool/assets/Mods"
    for mod in Steamodded Cryptid Talisman lovely sticky-fingers; do
        if [[ -d "$MODS_DIR/$mod" ]]; then
            cp -r "$MODS_DIR/$mod" "$BUILD_DIR/apktool/assets/Mods/"
            log_info "  Embedded $mod"
        fi
    done

    # Create lovely.lua config in assets
    cat > "$BUILD_DIR/apktool/assets/lovely.lua" << EOF
return {
  repo = "https://github.com/ethangreen-dev/lovely-injector",
  version = "0.9.0",
  mod_dir = "Mods",
}
EOF
    log_success "Mods embedded in APK"

    # Apply CRT shader fix
    log_info "Applying CRT shader fix..."
    apply_crt_fix "$BUILD_DIR/apktool/assets/resources/shaders/CRT.fs"

    # Ensure conf.lua doesn't have externalstorage
    log_info "Patching conf.lua..."
    cat > "$BUILD_DIR/apktool/assets/conf.lua" << 'EOF'
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

    # Rebuild APK
    log_info "Rebuilding APK..."
    apktool b -f "$BUILD_DIR/apktool" -o "$BUILD_DIR/apk/unsigned.apk"

    # Align and sign
    log_info "Aligning APK..."
    zipalign -f 4 "$BUILD_DIR/apk/unsigned.apk" "$BUILD_DIR/apk/aligned.apk"

    log_info "Signing APK..."
    ensure_keystore
    apksigner sign --ks "$KEYSTORE_FILE" --ks-pass pass:android \
        --out "$BUILD_DIR/apk/$PACKAGE_ID.apk" "$BUILD_DIR/apk/aligned.apk"

    log_success "APK built: $BUILD_DIR/apk/$PACKAGE_ID.apk"
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

    # Copy mods to transfer folder
    for mod in Steamodded Cryptid Talisman lovely sticky-fingers; do
        if [[ -d "$MODS_DIR/$mod" ]]; then
            cp -r "$MODS_DIR/$mod" "$transfer_dir/Mods/"
        fi
    done

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
    adb shell "run-as $PACKAGE_ID mkdir -p files/save"
    adb shell "run-as $PACKAGE_ID cp -r $temp_dir/* files/save/"

    # Verify
    log_info "Verifying deployment..."
    local file_count=$(adb shell "run-as $PACKAGE_ID ls files/save/ | wc -l")
    log_success "Deployed $file_count items to device"

    # Launch app
    log_info "Launching app..."
    adb shell am start -n "$PACKAGE_ID/org.love2d.android.GameActivity"
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
