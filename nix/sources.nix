# nix/sources.nix — consume the pin lockfile (nix/sources.json) as Nix fetchers.
#
# This is the niv/npins pattern: a generated JSON lockfile + a thin Nix reader.
# Every build input is content-addressed and reproducible. Bump a pin by editing
# the source table in update-sources.sh and re-running it; commit the json diff.
#
#   nix-build nix/sources.nix -A cryptid     # realise one pinned source
#
{ pkgs ? import <nixpkgs> { } }:

let
  lock = builtins.fromJSON (builtins.readFile ./sources.json);

  fetch = name: spec:
    if spec.kind == "github" then
      pkgs.fetchFromGitHub {
        inherit (spec) owner repo rev sha256;
      }
    else if spec.kind == "release" || spec.kind == "url" then
    # release-asset zips and plain URLs are pinned by FLAT file hash
    # (nix-prefetch-url, no --unpack) — the derivation unzips them itself.
      pkgs.fetchurl { inherit (spec) url sha256; }
    else if spec.kind == "file" then
    # non-redistributable blob (© LocalThunk): bytes never enter the repo.
    # Add it once with:  nix-store --add-fixed sha256 /path/to/Balatro.love
      pkgs.requireFile {
        inherit (spec) name sha256;
        message = ''
          ${spec.name} is not redistributable and must be supplied locally:
            nix-store --add-fixed sha256 /path/to/${spec.name}
        '';
      }
    else
      throw "sources.nix: unknown pin kind '${spec.kind}' for '${name}'";
in
builtins.mapAttrs fetch lock
