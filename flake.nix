{
  description = "(x11/wayland roots) keyboard grabber and key press reporter";
  inputs.nixpkgs.url = "nixpkgs/nixos-unstable";
  inputs.flake-utils.url = "github:numtide/flake-utils";
  inputs.zn-nix.url = "github:mitchdzugan/zn.nix";
  inputs.zn-nix.inputs.nixpkgs.follows = "nixpkgs";
  inputs.zkg.url = "github:mitchdzugan/zkg";
  inputs.ztr.url = "github:mitchdzugan/ztr-clj";
  outputs = { self, nixpkgs, zn-nix, flake-utils, zkg, zlr, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        zn = zn-nix.mk-zn system;
        zkg-bin = "${zkg.packages.${system}.default}/bin/zkg";
        ztr-bin = "${ztr.packages.${system}.default}/bin/ztr";
      in rec {
        packages.default = packages.zkm;
        packages.zkm = zn.writeBbScriptBin' "zkm" [zkg-x11 zkg-wlr] ''
          (def zkg-bin "${zkg-bin}")
          (def ztr-bin "${ztr-bin}")
          ${builtins.readFile ./zkg.clj}
        '';
    });
}
