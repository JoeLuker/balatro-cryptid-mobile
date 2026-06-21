# nix/balatro-cryptid.nix — the modded-APK build (pattern A).
#
#   pristine pinned sources (sources.nix)
#     → assemble game.love  [gameLove]
#       → repackage + sign base.apk  [apk]
#
# Two stages so the lua assembly (light) can be built/verified without the
# android toolchain (heavy).
#
# STATUS (Phase 2): gameLove assembly is build-verified. The patch series
# (Phase 3, overlay/patches/series — currently empty) and a dump regenerated
# *from these pins* (see MIGRATION.md) are the remaining gates before the output
# is a shippable, patched game. The apk stage is written but build-verify is the
# next gate.
{ pkgs ? import <nixpkgs> {
    config = { allowUnfree = true; android_sdk.accept_license = true; };
  }
, sources ? import ./sources.nix { inherit pkgs; }
  # The lovely-merged game Lua — generated FROM THE PINS by nix/regen-dump.sh and
  # vendored at vendor/dump (stamped with the source revs in .source-revs). This
  # is the consistency guarantee: dump and mod sources can't drift.
, dump ? ../vendor/dump
, overlay ? ../overlay
, patchesDir ? ../patches   # Phase 5: owned modules consolidate into overlay/game
}:

let
  inherit (pkgs) lib stdenv;

  packageId = "com.unofficial.balatro.cryptid";
  appName = "Balatro Cryptid";

  # ── stage 1: game.love ────────────────────────────────────────────────────
  gameLove = stdenv.mkDerivation {
    name = "balatro-cryptid-game.love";
    dontUnpack = true;
    nativeBuildInputs = [ pkgs.unzip pkgs.zip ];

    buildPhase = ''
      runHook preBuild
      mkdir game && pushd game >/dev/null

      echo "[love] vanilla Balatro.love"
      unzip -q ${sources.balatro_love} -d .

      echo "[love] lovely-merged dump over vanilla"
      cp -r --no-preserve=mode ${dump}/. .

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
      embed_zip ${sources.debugplus}   DebugPlus

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

      echo "[love] owned standalone modules"
      for m in android-telemetry trigger-collapse idle-joker-perf lazy-shader; do
        cp --no-preserve=mode ${patchesDir}/$m.lua ./$m.lua
      done

      echo "[love] owned conf.lua"
      cp --no-preserve=mode ${overlay}/game/conf.lua ./conf.lua

      cat > lovely.lua <<'LOVELY'
      return { repo = "https://github.com/ethangreen-dev/lovely-injector", version = "0.9.0", mod_dir = "Mods" }
      LOVELY

      # ── patch series (Phase 3) ──
      # Apply overlay/patches/* in series order with hard-fail (no silent skips).
      series=${overlay}/patches/series
      applied=0
      while IFS= read -r p; do
        case "$p" in ""|"#"*) continue;; esac
        echo "[patch] $p"
        patch -p1 --dry-run < "${overlay}/patches/$p" >/dev/null
        patch -p1          < "${overlay}/patches/$p"
        applied=$((applied+1))
      done < "$series"
      echo "[patch] applied $applied patch(es)"

      # TODO Phase 3: strip_en_us_assets (size-only; omitted from the proof build)
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
      # NOTE: signing key handling (was scripts/build.sh ensure_keystore) wires in
      # next — emit the aligned APK for now so the pipeline is verifiable.
      cp aligned.apk "$out/${packageId}.unsigned-aligned.apk"
      runHook postInstall
    '';
  };
in
{
  inherit gameLove apk;
}
