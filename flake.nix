{
  description = "🬷z🬛 text renderer";
  inputs.nixpkgs.url = "nixpkgs/nixos-unstable";
  inputs.flake-utils.url = "github:numtide/flake-utils";
  inputs.zn-nix.url = "github:mitchdzugan/zn.nix";
  inputs.zn-nix.inputs.nixpkgs.follows = "nixpkgs";
  inputs.zkg.url = "github:mitchdzugan/zkg";
  inputs.ztr.url = "path:/home/dz/Projects/ztr-clj";
  outputs = { self, nixpkgs, zn-nix, flake-utils, zkg, ztr, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        version = builtins.substring 0 8 self.lastModifiedDate;
        pkgs = nixpkgs.legacyPackages.${system};
        zn = zn-nix.mk-zn system;
        zkg-pkg = zkg.packages.${system}.default;
        ztr-pkg = ztr.packages.${system}.default;
        baseZkmModuleConfig = {
          projectSrc = ./.;
          name = "org.mitchdzugan/zkm";
          main-ns = "zkm.core";
          builder-extra-inputs = [zkg-pkg ztr-pkg];
          builder-preBuild = with pkgs; ''
            # TODO overwite src/zkm/bins.clj using nix pkg paths
          '';
        };
        buildZkmApp = extraConfig: zn.mkCljApp {
          pkgs = pkgs;
          modules = [(extraConfig // baseZkmModuleConfig)];
        };
      in rec {
        packages.default = packages.zkm;
        packages.zkm = buildZkmApp {
          nativeImage.enable = true;
          nativeImage.extraNativeImageBuildArgs = [
            "--initialize-at-build-time"
            "-J-Dclojure.compiler.direct-linking=true"
            "-Dskija.staticLoad=false"
            "--native-image-info"
            "-march=compatibility"
            "-H:+JNI"
            "-H:+ReportExceptionStackTraces"
            "--report-unsupported-elements-at-runtime"
            "--verbose"
            "-Dskija.logLevel=DEBUG"
            "-H:DashboardDump=target/dashboard-dump"
          ];
        };
    });
}
