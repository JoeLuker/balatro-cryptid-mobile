# Reproducible build environment for the Android APK pipeline.
#
# Previously the build relied on an *imperatively* installed Android SDK
# (Android Studio's sdkmanager) for `zipalign` and `apksigner`. That broke as
# soon as the SDK was removed. This shell pins those tools — and the rest of
# the toolchain — through nixpkgs so `nix-shell && ./scripts/build.sh build`
# works from a clean machine.
#
# Usage:  nix-shell --run './scripts/build.sh build'
#   or:   nix-shell   (drops into a shell with everything on PATH)
#
# androidenv pulls the SDK from Google's repository, so it needs the unfree
# license accepted; that is wired in below so no global config change is
# required.
{ pkgs ? import <nixpkgs> {
    config = {
      allowUnfree = true;
      android_sdk.accept_license = true;
    };
  }
}:

let
  buildToolsVersion = "34.0.0";

  androidComposition = pkgs.androidenv.composeAndroidPackages {
    buildToolsVersions = [ buildToolsVersion ];
    platformVersions = [ "34" ];
    includeNDK = false;
    includeEmulator = false;
    includeSystemImages = false;
  };

  androidSdk = androidComposition.androidsdk;
  sdkRoot = "${androidSdk}/libexec/android-sdk";
in
pkgs.mkShell {
  buildInputs = [
    androidSdk        # provides zipalign + apksigner under build-tools/<ver>
    pkgs.apktool      # decompile/recompile APKs
    pkgs.openjdk17    # JRE for apktool + apksigner
    pkgs.android-tools # adb
    pkgs.unzip
    pkgs.zip
    pkgs.curl
    pkgs.just
  ];

  shellHook = ''
    export ANDROID_HOME="${sdkRoot}"
    export ANDROID_SDK_ROOT="${sdkRoot}"
    export PATH="${sdkRoot}/build-tools/${buildToolsVersion}:$PATH"
  '';
}
