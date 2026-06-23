# nix/balatro-cryptid.nix — the modded-APK build (pattern A).
#
#   pinned sources (sources.nix) + vendored from-pins dump
#     → assemble pristine tree   [gameLoveBase]
#       → apply patch series      [gameLove]  (overlay/patches/*, hard-fail)
#         → repackage + sign      [apk]
#
# Split so gen-patches.sh can diff against gameLoveBase (the pristine base),
# while gameLove = base + series is what ships. The lua stages build without the
# android toolchain (heavy) — only `apk` needs it.
{ pkgs ? import <nixpkgs> {
    config = { allowUnfree = true; android_sdk.accept_license = true; };
  }
, sources ? import ./sources.nix { inherit pkgs; }
  # The lovely-merged game Lua — generated FROM THE PINS by nix/regen-dump.sh and
  # vendored at vendor/dump (stamped with source revs). Dump/mod can't drift.
, dump ? ../vendor/dump
, overlay ? ../overlay
, patchesDir ? ../patches   # Phase 5: owned modules consolidate into overlay/game
}:

let
  inherit (pkgs) stdenv;

  packageId = "com.unofficial.balatro.cryptid";
  appName = "Balatro Cryptid";

  # ── stage 1a: pristine assembled tree (NO patches) ────────────────────────
  gameLoveBase = stdenv.mkDerivation {
    name = "balatro-cryptid-gametree";
    dontUnpack = true;
    nativeBuildInputs = [ pkgs.unzip ];
    buildPhase = ''
      runHook preBuild
      mkdir game && pushd game >/dev/null

      echo "[love] vanilla Balatro.love"
      unzip -q ${sources.balatro_love} -d .

      echo "[love] lovely-merged dump over vanilla"
      cp -r --no-preserve=mode ${dump}/. .

      # Deterministic build stamp (was a date+githash sed in build.sh — non-repro,
      # and it churned the globals.lua patch chain). Derive from the pinned Cryptid
      # rev so it is stable; the menu-badge patch displays G.CRYPTID_MOBILE_BUILD.
      stamp="nix-$(awk -F'\t' '$1=="cryptid"{print substr($2,1,7)}' .source-revs 2>/dev/null || echo pinned)"
      rm -f .source-revs
      sed -i "s|    self.VERSION = VERSION|    self.VERSION = VERSION\n    self.CRYPTID_MOBILE_BUILD = '$stamp' -- BUILD_STAMP|" globals.lua

      echo "[love] android nativefs wrapper (owned)"
      cp --no-preserve=mode ${patchesDir}/android-nativefs.lua nativefs.lua

      mkdir -p Mods

      # git-tree mods → Mods/<Name>, lovely/ payload dropped (dead on Android)
      embed_dir() { cp -r --no-preserve=mode "$1" "Mods/$2"; rm -rf "Mods/$2/lovely"; }
      embed_dir ${sources.steamodded}     Steamodded
      embed_dir ${sources.cryptid}        Cryptid
      embed_dir ${sources.sticky_fingers} sticky-fingers

      # zip mods → unzip, take inner dir if singly-wrapped, drop lovely/
      embed_zip() {
        local zip="$1" name="$2" tmp; tmp="$(mktemp -d)"
        unzip -q "$zip" -d "$tmp"
        local inner; inner="$(cd "$tmp" && ls -1)"
        if [ "$(printf '%s\n' "$inner" | wc -l)" = 1 ] && [ -d "$tmp/$inner" ]; then
          cp -r --no-preserve=mode "$tmp/$inner" "Mods/$name"
        else
          cp -r --no-preserve=mode "$tmp" "Mods/$name"
        fi
        rm -rf "Mods/$name/lovely" "$tmp"
      }
      embed_zip ${sources.cardsleeves} CardSleeves

      # Amulet is a FLAT zip: talisman/ + big-num/ must sit at the GAME ROOT
      # (PhysFS can't mount inside game.love); smods.* + assets stay under Mods/Amulet.
      echo "[love] Amulet (flat → root-mount talisman/ + big-num/)"
      atmp="$(mktemp -d)"; unzip -q ${sources.amulet} -d "$atmp"
      rm -rf "$atmp/lovely"
      cp -r --no-preserve=mode "$atmp/talisman" ./talisman
      cp -r --no-preserve=mode "$atmp/big-num"  ./big-num
      rm -rf "$atmp/talisman" "$atmp/big-num"
      mkdir -p Mods/Amulet
      cp -r --no-preserve=mode "$atmp"/. Mods/Amulet/
      rm -rf "$atmp"

      # reserve-shim mini-mod (owned)
      cp -r --no-preserve=mode ${patchesDir}/reserve-shim Mods/reserve-shim

      # baked-in mod config overlay (was config-overrides/, applied at fetch-time
      # by the legacy build). Intentional overrides layered over the pinned mod
      # BEFORE the patch series — Cryptid's per-item toggle UI + gameplay config.
      echo "[love] mod config overlay"
      for moddir in ${overlay}/config/*/; do
        name="$(basename "$moddir")"
        [ -d "Mods/$name" ] && cp -r --no-preserve=mode "$moddir"/. "Mods/$name/"
      done

      echo "[love] owned standalone modules"
      for m in android-telemetry trigger-collapse idle-joker-perf lazy-shader; do
        cp --no-preserve=mode ${patchesDir}/$m.lua ./$m.lua
      done

      echo "[love] owned conf.lua"
      cp --no-preserve=mode ${overlay}/game/conf.lua ./conf.lua

      cat > lovely.lua <<'LOVELY'
      -- no-lovely runtime stub. The preflight (src/preflight/core.lua) calls these
      -- lovely runtime functions; with no injector they are inert: remove_var returns
      -- false (no persisted vars across the restart that this build never does),
      -- reload_patches is a no-op success (patches are baked, not live).
      return {
        repo = "https://github.com/ethangreen-dev/lovely-injector",
        version = "0.9.0",
        mod_dir = "Mods",
        remove_var = function() return false end,
        set_var = function() end,
        reload_patches = function() return true end,
        -- apply_patches(name, content): lovely applies registered patches to a
        -- file's content at load. On this baked build, file/Lua patches are already
        -- applied (the dump) and per-shader-file patches don't exist, so those pass
        -- through unchanged. The ONE thing lovely does at runtime that the dump can't
        -- capture is its GLSL_ES shader repairs (lovely/glsl_es_patches/repair{1,2,3}
        -- .toml, target "GLSL_ES_PATCHES.fs") — applied to shaders built via SMODS's
        -- newShader wrapper when the renderer is OpenGL ES. Without them, SMODS
        -- shaders compile to garbage on mobile GPUs (Mali/Tensor) and wash the
        -- screen white. Faithfully port those repairs here for that target.
        apply_patches = function(name, content)
          if type(content) ~= 'string' then return content end
          if name ~= 'GLSL_ES_PATCHES.fs' then return content end
          local s = content
          -- int literals -> float (twice: adjacent literals share boundary chars)
          s = s:gsub('([^%w.])(%d+)([^%w.])', '%1%2.%3')
          s = s:gsub('([^%w.])(%d+)([^%w.])', '%1%2.%3')
          -- int type -> float in cast/array/decl contexts
          s = s:gsub('([%s({])int([%s([])', '%1 float%2')
          -- cleanups (undo false positives the above introduces)
          s = s:gsub('(__%w+__%s*[<>]%s*%d+)%.', '%1')            -- preprocessor compares
          s = s:gsub('([%d.]e%-?%d+)%.', '%1')                    -- scientific notation
          s = s:gsub('%[(%d+)%.%]', '[%1]')                       -- numeric array index
          s = s:gsub('%[([^%[%]]*[^%d.][^%[%]]*)%]', '[int(%1)]') -- non-numeric index
          s = s:gsub('(%d+%.%d+)f([^%w])', '%1%2')                -- decimal float suffix
          -- float/precision family -> highp (mediump overflows on Mali)
          s = s:gsub('(extern%s+)(number)', '%1highp %2')
          s = s:gsub('(uniform%s+)(number)', '%1highp %2')
          s = s:gsub('mediump(%s+)', 'highp%1')
          return s
        end,
      }
      LOVELY

      # Normalise shader EOL → LF. Cryptid ships CRLF .fs files; GLSL is
      # EOL-agnostic and the legacy build only ever appended a stray LF, so this
      # is benign — and it makes the (git-diff) shader patch series byte-stable.
      echo "[love] normalise shader EOL → LF"
      find . \( -name '*.fs' -o -name '*.vs' \) -print0 \
        | while IFS= read -r -d "" s; do sed -i 's/\r$//' "$s"; done

      popd >/dev/null
      runHook postBuild
    '';
    installPhase = ''
      runHook preInstall
      cp -r game "$out"
      runHook postInstall
    '';
  };

  # ── stage 1b: apply the patch series → game.love ──────────────────────────
  gameLove = stdenv.mkDerivation {
    name = "balatro-cryptid-game.love";
    dontUnpack = true;
    nativeBuildInputs = [ pkgs.zip pkgs.gitMinimal ];
    buildPhase = ''
      runHook preBuild
      cp -r --no-preserve=mode ${gameLoveBase} game
      chmod -R u+w game
      pushd game >/dev/null

      # Apply overlay/patches/* (git diffs) in series order with HARD-FAIL — no
      # silent skips. git apply (not GNU patch) handles git's no-newline + CRLF
      # semantics; ignore global git config so EOL is never normalised.
      export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null
      applied=0
      while IFS= read -r p; do
        case "$p" in ""|"#"*) continue;; esac
        echo "[patch] $p"
        git apply --check -p1 --whitespace=nowarn "${overlay}/patches/$p"
        git apply        -p1 --whitespace=nowarn "${overlay}/patches/$p"
        applied=$((applied+1))
      done < "${overlay}/patches/series"
      echo "[patch] applied $applied patch(es)"

      # TODO Phase 3: strip_en_us_assets (size-only)
      popd >/dev/null
      runHook postBuild
    '';
    installPhase = ''
      runHook preInstall
      ( cd game && zip -q -X -r "$out" . )
      runHook postInstall
    '';
  };

  # ── stage 2: signed APK ───────────────────────────────────────────────────
  androidComposition = pkgs.androidenv.composeAndroidPackages {
    buildToolsVersions = [ "34.0.0" ];
    platformVersions = [ "34" ];
    includeNDK = false; includeEmulator = false; includeSystemImages = false;
  };
  buildTools = "${androidComposition.androidsdk}/libexec/android-sdk/build-tools/34.0.0";

  apk = stdenv.mkDerivation {
    name = "balatro-cryptid.apk";
    dontUnpack = true;
    nativeBuildInputs = [ pkgs.apktool pkgs.openjdk17 pkgs.zip pkgs.unzip ];
    buildPhase = ''
      runHook preBuild
      apktool d -f -o apktool ${sources.base_apk}

      # launcher icon → Cryptid deck back (owned)
      for d in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
        [ -f ${patchesDir}/icon/love-$d.png ] && \
          cp --no-preserve=mode ${patchesDir}/icon/love-$d.png apktool/res/drawable-$d/love.png || true
      done

      echo "renameManifestPackage: ${packageId}" >> apktool/apktool.yml
      sed -i '/^doNotCompress:/a - assets/game.love' apktool/apktool.yml
      sed -i 's/android:label="[^"]*"/android:label="${appName}"/' apktool/AndroidManifest.xml
      sed -i 's/android:resizeableActivity="false"/android:resizeableActivity="true"/' apktool/AndroidManifest.xml
      sed -i 's/android:allowBackup="true"/android:allowBackup="true" android:debuggable="true"/' apktool/AndroidManifest.xml

      mkdir -p apktool/assets
      cp ${gameLove} apktool/assets/game.love

      apktool b -j 1 -f apktool -o unsigned.apk
      runHook postBuild
    '';
    installPhase = ''
      runHook preInstall
      mkdir -p "$out"
      ${buildTools}/zipalign -f 4 unsigned.apk aligned.apk
      # NOTE: signing-key wiring (was ensure_keystore) is the next gate — emit the
      # aligned APK for now so the pipeline is verifiable.
      cp aligned.apk "$out/${packageId}.unsigned-aligned.apk"
      runHook postInstall
    '';
  };
in
{
  inherit gameLoveBase gameLove apk;
}
