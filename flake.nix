{
  description = "balatro-cryptid-mobile — modded Balatro APK (pinned, Nix-native build)";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = { allowUnfree = true; android_sdk.accept_license = true; };
      };
    in
    {
      # Build toolchain (apktool, android build-tools, love, luajit) — the real
      # shell.nix, reused so there is one definition.
      devShells.${system}.default = import ./shell.nix { inherit pkgs; };

      # The modded APK build (pattern A). Built today via:
      #   nix-build nix/balatro-cryptid.nix -A gameLove --arg dump <path>
      # Not yet a *pure* flake output: the lovely dump is gitignored, so a pure
      # flake can't see it. Phase 3 vendors a dump regenerated from the pins,
      # after which this becomes packages.${system}.default:
      #   packages.${system}.default =
      #     (import ./nix/balatro-cryptid.nix { inherit pkgs; }).apk;
    };
}
