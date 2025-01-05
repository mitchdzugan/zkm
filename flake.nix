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
            l1='(ns zkm.bins)'
            l2='(def zkg "/${zkg-pkg}/bin/zkg")'
            l3='(def ztr "/${ztr-pkg}/bin/ztr")'
            { echo "$l1"; echo "$l2"; echo "$l3"; } > src/zkm/bins.clj
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
            "--native-image-info"
            "-march=compatibility"
            "-H:+JNI"
            "-H:JNIConfigurationFiles=${./.}/.graal-support/jni.json"
            "-H:ResourceConfigurationFiles=${./.}/.graal-support/resources.json"
            "-H:+ReportExceptionStackTraces"
            "--report-unsupported-elements-at-runtime"
            "--verbose"
            "-Dskija.logLevel=DEBUG"
            "-H:DashboardDump=target/dashboard-dump"
          ];
        };
        packages.uberjar = buildZkmApp {};
        packages.trace-run = zn.uuFlakeWrap (zn.writeBashScriptBin'
          "trace-run"
          [ zkg-pkg ztr-pkg packages.uberjar pkgs.graalvm-ce pkgs.xorg.libX11 ]
          ''
            export LD_LIBRARY_PATH="${zn.mkLibPath [pkgs.xorg.libX11]}"
            gvmh="$GRAALVM_HOME"
            if [ ! -f "$gmvh/bin/java" ]; then
              gmvh="${pkgs.graalvm-ce}"
            fi
            jar_path=$(cat "${packages.uberjar}/nix-support/jar-path")
            $gmvh/bin/java \
              -agentlib:native-image-agent=config-merge-dir=./.graal-support \
              -jar $jar_path "$@"
          ''
        );
        packages.trace-normalize = zn.uuFlakeWrap (zn.writeBbScriptBin
          "trace-normalize"
          ''
            (require '[cheshire.core :as json]
                     '[clojure.string :as str])
            (println "normalizing trace data")
            (def trace-dir "./.graal-support/")
            (def trace-filename (partial str trace-dir))
            (def md (-> (trace-filename "reachability-metadata.json")
                        slurp
                        str/join
                        json/parse-string))
            (def rn-key #(-> %1 (dissoc %2) (assoc %3 (get %1 %2))))
            (defn md-out [l md]
              (spit (trace-filename (str l ".json")) (json/generate-string md)))
            (md-out "jni" (map #(rn-key %1 "type" "name") (get md "jni")))
            (md-out "resources" {"globs" (get md "resources")})
            (println "trace data normalized. namaste and good luck =^)")
          ''
        );
    });
}
