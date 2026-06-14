{ pkgs ? import <nixpkgs> { config.allowUnfree = true; config.android_sdk.accept_license = true; } }:
let
  android = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "34" ];
    buildToolsVersions = [ "34.0.0" ];
    abiVersions = [ "arm64-v8a" ];
    includeNDK = false;
    includeEmulator = false;
    includeSystemImages = false;
  };
  sdk = "${android.androidsdk}/libexec/android-sdk";
in pkgs.mkShell {
  buildInputs = [ pkgs.gradle pkgs.jdk17 android.androidsdk ];
  ANDROID_HOME = sdk;
  ANDROID_SDK_ROOT = sdk;
  JAVA_HOME = "${pkgs.jdk17.home}";
  # NixOS: gradle's maven aapt2 won't run; point AGP at the nix SDK's aapt2.
  GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/34.0.0/aapt2";
}
