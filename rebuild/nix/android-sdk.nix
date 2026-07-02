# The rebuild's Android SDK, composed in ONE place — imported by shell.nix and
# realized (with a GC root) by tools/sdk-overlay.sh. Bump versions here only.
{ pkgs ? import <nixpkgs> { config.allowUnfree = true; config.android_sdk.accept_license = true; } }:
(pkgs.androidenv.composeAndroidPackages {
  platformVersions = [ "34" ];
  buildToolsVersions = [ "34.0.0" ];
  abiVersions = [ "arm64-v8a" ];
  includeNDK = false;
  includeEmulator = false;
  includeSystemImages = false;
}).androidsdk
