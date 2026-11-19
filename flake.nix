{
  description = "jz — command-line client for the Janzeer blockchain";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs =
    { self, nixpkgs }:
    let
      # x86_64-darwin is not listed: current nixpkgs (26.11) has dropped
      # support for it, so it cannot even be evaluated.
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "aarch64-darwin"
      ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in
    {
      packages = forAllSystems (system: {
        default = nixpkgs.legacyPackages.${system}.callPackage ./packaging/nix/package.nix { };
      });

      apps = forAllSystems (system: {
        default = {
          type = "app";
          program = nixpkgs.lib.getExe self.packages.${system}.default;
          meta = {
            description = "Run jz, the Janzeer command-line client";
            mainProgram = "jz";
          };
        };
      });

      checks = forAllSystems (system: {
        inherit (self.packages.${system}) default;
      });
    };
}
