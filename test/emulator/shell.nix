# Android emulator environment for headless APK testing.
#
# Separate from the top-level shell.nix on purpose: the emulator + system
# image is a multi-GB closure that the normal build/deploy loop never needs.
#
# The system image is x86_64 (this host) with Google's ARM->x86 binary
# translation (ndk_translation, present in google_apis images since API 30),
# because our APK ships ARM libs only (arm64-v8a + armeabi-v7a). Rendering is
# SwiftShader — software GL, NOT a Mali GPU: shader-precision bugs will not
# reproduce here; that remains phone territory.
#
# Usage: nix-shell test/emulator/shell.nix --run 'test/emulator/run.sh'
{ pkgs ? import <nixpkgs> {
    config = {
      allowUnfree = true;
      android_sdk.accept_license = true;
    };
  }
}:

let
  apiLevel = "34";

  androidComposition = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ apiLevel ];
    includeEmulator = true;
    includeSystemImages = true;
    systemImageTypes = [ "google_apis" ];
    abiVersions = [ "x86_64" ];
    includeNDK = false;
  };

  androidSdk = androidComposition.androidsdk;
  sdkRoot = "${androidSdk}/libexec/android-sdk";
in
pkgs.mkShell {
  buildInputs = [
    androidSdk
    pkgs.imagemagick # screenshot histogram = crash-screen detector (logcat is silent)
  ];

  shellHook = ''
    export ANDROID_HOME="${sdkRoot}"
    export ANDROID_SDK_ROOT="${sdkRoot}"
    export BALATRO_EMU_SYSIMAGE="system-images;android-${apiLevel};google_apis;x86_64"
  '';
}
