{
  description = "(x11/wayland roots) keyboard grabber and key press reporter";
  inputs.nixpkgs.url = "nixpkgs/nixos-unstable";
  inputs.flake-utils.url = "github:numtide/flake-utils";
  inputs.zn-nix.url = "github:mitchdzugan/zn.nix";
  inputs.zn-nix.inputs.nixpkgs.follows = "nixpkgs";
  inputs.zkg.url = "github:mitchdzugan/zkg";
  inputs.ztr.url = "path:/home/dz/Projects/ztr-clj";
  outputs = { self, nixpkgs, zn-nix, flake-utils, zkg, ztr, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        zn = zn-nix.mk-zn system;
        zkg-pkg = zkg.packages.${system}.default;
        ztr-pkg = ztr.packages.${system}.default;
      in rec {
        packages.default = packages.zkm;
        packages.zkm = zn.writeBbScriptBin' "zkm" [zkg-pkg ztr-pkg] ''
          (def zkg-bin "${zkg-pkg}/bin/zkg")
          (def ztr-bin "${ztr-pkg}/bin/ztr")
          ${builtins.readFile ./zkm.clj}
        '';
    });
}
