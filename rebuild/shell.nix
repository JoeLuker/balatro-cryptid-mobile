{ pkgs ? import <nixpkgs> { config.allowUnfree = true; config.android_sdk.accept_license = true; } }:
let
  # SDK composition lives in ONE place (nix/android-sdk.nix) — shared with
  # tools/sdk-overlay.sh, which realizes it with a GC root for plain ./gradlew runs.
  androidsdk = import ./nix/android-sdk.nix { inherit pkgs; };
  sdk = "${androidsdk}/libexec/android-sdk";
in pkgs.mkShell {
  buildInputs = [ pkgs.gradle pkgs.jdk17 androidsdk ];
  ANDROID_HOME = sdk;
  ANDROID_SDK_ROOT = sdk;
  JAVA_HOME = "${pkgs.jdk17.home}";
  # NixOS: gradle's maven aapt2 won't run; point AGP at the nix SDK's aapt2.
  GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/34.0.0/aapt2";
}
