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
          builder-extra-inputs = [zkg-pkg ztr-pkg pkgs.xorg.libX11 pkgs.pkg-config];
          builder-preBuild = with pkgs; ''
            rm -rf fakelib
            mkdir -p fakelib
            ln -s "${pkgs.xorg.libX11}/lib" fakelib/linux-x86-64
            export LD_LIBRARY_PATH="fakelib"
            l1='(ns zkm.bins)'
            l2='(def zkg "${zkg-pkg}/bin/zkg")'
            l3='(def ztr "${ztr-pkg}/bin/ztr")'
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
            # "--initialize-at-run-time=com.sun.jna.platform.unix.X11"
            # "--initialize-at-run-time=com.sun.jna.Structure$FFIType"
            # "--trace-object-instantiation=com.sun.jna.internal.Cleaner$CleanerThread"

            "--initialize-at-build-time"
            "--initialize-at-run-time=com.sun.jna"
            "--initialize-at-run-time=clojure.core.async.impl.concurrent__init"
            "--initialize-at-run-time=babashka.fs__init"
            "--initialize-at-run-time=babashka.process__init"
            "--initialize-at-run-time=cheshire.generate_seq__init"
            "--initialize-at-run-time=clojure.core.async__init"
            "--initialize-at-run-time=clojure.core.async.impl.buffers__init"
            "--initialize-at-run-time=clojure.core.async.impl.channels__init"
            "--initialize-at-run-time=clojure.core.async.impl.exec.threadpool__init"
            "--initialize-at-run-time=clojure.core.async.impl.dispatch__init"
            "--initialize-at-run-time=clojure.core.async.impl.go__init"
            "--initialize-at-run-time=clojure.core.async.impl.timers__init"
            "--initialize-at-run-time=clojure.core.cache__init"
            "--initialize-at-run-time=clojure.datafy__init"
            "--initialize-at-run-time=clojure.reflect.java__init"
            "--initialize-at-run-time=clojure.tools.analyzer.jvm.utils__init"
            "--initialize-at-run-time=clojure.tools.reader__init"
            "--initialize-at-run-time=edamame.impl.parser__init"
            "--initialize-at-run-time=sci.core__init"
            "--initialize-at-run-time=sci.impl.analyzer__init"
            "--initialize-at-run-time=sci.impl.callstack__init"
            "--initialize-at-run-time=sci.impl.copy_vars__init"
            "--initialize-at-run-time=sci.impl.deftype__init"
            "--initialize-at-run-time=sci.impl.evaluator__init"
            "--initialize-at-run-time=sci.impl.interop__init"
            "--initialize-at-run-time=sci.impl.interpreter__init"
            "--initialize-at-run-time=sci.impl.fns__init"
            "--initialize-at-run-time=sci.impl.multimethods__init"
            "--initialize-at-run-time=sci.impl.io__init"
            "--initialize-at-run-time=sci.impl.parser__init"
            "--initialize-at-run-time=sci.impl.namespaces__init"

            "-march=compatibility"
            "-H:+JNI"
            "-H:JNIConfigurationFiles=${./.}/.graal-support/jni.json"
            "-H:ResourceConfigurationFiles=${./.}/.graal-support/resources.json"
            "-H:ReflectionConfigurationFiles=${./.}/.graal-support/reflection.json"
            "-H:DynamicProxyConfigurationFiles=${./.}/.graal-support/proxy.json"
            "-H:+ReportExceptionStackTraces"
            "--report-unsupported-elements-at-runtime"
            "--verbose"
            "-Djna.library.path=${pkgs.xorg.libX11}/lib"
            "-H:CLibraryPath=${pkgs.xorg.libX11}/lib"
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
            (md-out "reflection" (map #(rn-key %1 "type" "name") (get md "reflection")))
            (md-out "resources" {"globs" (get md "resources")})
            (println "trace data normalized. namaste and good luck =^)")
          ''
        );
    });
}
