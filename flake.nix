{
  description = "balatro-cryptid-mobile";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
  };

  outputs = { self, nixpkgs }: let
    system = "x86_64-linux";
    pkgs = nixpkgs.legacyPackages.${system};
  in {
    meta = {
      status = "dormant";
      stack = [ "unknown" ];
      category = "root";
    };

    devShells.${system}.default = pkgs.mkShell {
      packages = with pkgs; [
        # Add project dependencies here
      ];
    };
  };
}
