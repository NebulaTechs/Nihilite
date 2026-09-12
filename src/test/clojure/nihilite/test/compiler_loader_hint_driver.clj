(ns nihilite.test.compiler-loader-hint-driver
  "Replaces src/test/java/nihilite/test/compilerLoaderHintDriver.java.

   Validates the host-class-loader resolution flow under three paths:
   1. With the `nihilite.compiler-loader-hint` system property pointing
      at a real class on the application loader, the resolver returns
      the application loader.
   2. With a property pointing at a non-existent class, the resolver
      falls back to the system class loader.
   3. With no property at all, the resolver returns the system loader.

   Invoked from build.clj as `java nihilite.test.compilerLoaderHintDriver`."
  (:gen-class
   :name nihilite.test.compilerLoaderHintDriver
   :prefix "clhd-"
   :main true))

(def ^:private pass-count (atom 0))
(def ^:private fail-count (atom 0))

(defn- generate-class-bytes! [options]
  (let [generate-class (Class/forName "clojure.core$generate_class")
        invoke-static (.getDeclaredMethod generate-class "invokeStatic"
                                         (into-array Class [Object]))]
    (.setAccessible invoke-static true)
    (let [[cname bytecode] (.invoke invoke-static nil (object-array [options]))]
      (clojure.lang.Compiler/writeClassFile cname bytecode)
      cname)))

(defn- generate-hint-target! []
  (generate-class-bytes!
   {:name "nihilite.test.compiler_loader_hint_driver.HintTarget"
    :prefix "ht-"
    :impl-ns "nihilite.test.compiler-loader-hint-driver"
    :main false
    :methods []}))

(defn gen-all!
  "Generates the static HintTarget class into *compile-path*."
  []
  (generate-hint-target!)
  nil)

;; HintTarget has no methods -- it's only a marker class for the loader
;; hint test. The Java driver used `static class HintTarget {}` so we
;; mirror that with a no-method class.

(defn- fail! [why]
  (println "compilerLoaderHintDriver FAIL:" why)
  (swap! fail-count inc))

(defn- pass! []
  (swap! pass-count inc))

(defn- resolve-host-class-loader! []
  ((resolve 'nihilite.kernel.Agent/resolveHostClassLoader)))

(defn -main [& _args]
  (let [app-loader (.getClassLoader (Class/forName "nihilite.test.compilerLoaderHintDriver"))
        hint-class (Class/forName "nihilite.test.compiler_loader_hint_driver.HintTarget" true app-loader)
        hinted-loader (.getClassLoader hint-class)
        hint-name "nihilite/test/compiler_loader_hint_driver/HintTarget"
        saved-hint (System/getProperty "nihilite.compiler-loader-hint")]
    (try
      (System/setProperty "nihilite.compiler-loader-hint" hint-name)
      (let [resolved (resolve-host-class-loader!)]
        (if (nil? resolved)
          (fail! "resolved ClassLoader was null")
          (if (= hinted-loader resolved)
            (do (println "compilerLoaderHintDriver: hint '" hint-name
                         "' -> " resolved " (expected " hinted-loader ") OK")
                (pass!))
            (fail! (str "hint resolved to " resolved " expected " hinted-loader)))))

      (System/setProperty "nihilite.compiler-loader-hint" "totally/not/a/real/Class$InThisJvm")
      (let [fallback (resolve-host-class-loader!)]
        (if (nil? fallback)
          (fail! "fallback ClassLoader was null")
          (if (= (ClassLoader/getSystemClassLoader) fallback)
            (do (println "compilerLoaderHintDriver: unresolved hint fell back to system loader OK")
                (pass!))
            (fail! (str "unresolved hint did not fall back to system loader; got " fallback)))))

      (System/clearProperty "nihilite.compiler-loader-hint")
      (let [none-hint (resolve-host-class-loader!)]
        (if (= (ClassLoader/getSystemClassLoader) none-hint)
          (do (println "compilerLoaderHintDriver: no-hint -> system loader OK")
              (pass!))
          (fail! (str "no-hint path did not return system loader; got " none-hint))))
      (finally
        (if (nil? saved-hint)
          (System/clearProperty "nihilite.compiler-loader-hint")
          (System/setProperty "nihilite.compiler-loader-hint" saved-hint)))))

  (if (and (zero? @fail-count) (= 3 @pass-count))
    (do (println "DRIVER_PASS compiler-loader-hint: 3/3 paths OK")
        (System/exit 0))
    (do (println "DRIVER_FAIL compiler-loader-hint: pass=" @pass-count " fail=" @fail-count)
        (System/exit 1))))

(when *compile-files*
  (gen-all!))
