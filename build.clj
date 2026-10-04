(ns build
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b])
  (:import [java.util.jar JarFile]))

(def class-dir "target/classes")
(def test-class-dir "target/test-classes")
(def uber-file "target/nihilite.jar")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def test-basis (delay (b/create-basis {:project "deps.edn" :aliases [:dev]})))

(def manifest
  {"Premain-Class" "nihilite.kernel.Agent"
   "Agent-Class" "nihilite.kernel.Agent"
   "Can-Redefine-Classes" "true"
   "Can-Retransform-Classes" "true"
   "Main-Class" "nihilite.kernel.Agent"
   "Implementation-Title" "Nihilite"
   "Multi-Release" "true"})

(def forbidden-entries
  ["examples/" "minecraft/" "fabric/" "init.clj" "server.properties"])

(def ^:private base-driver-opts
  ["-Djdk.attach.allowAttachSelf=true"
   "-Dnet.bytebuddy.safe=false"])

(def ^:private dynamic-agent-loading-opts
  ["-XX:+EnableDynamicAgentLoading"])

(defn- java-major-version
  "Returns the JDK feature version (21, 25, ...). Accepts both
   '21.0.4' (legacy) and '21' (JEP 223+ short form)."
  []
  (some-> (System/getProperty "java.version")
          (str/split #"[\.\-]")
          first
          Integer/parseInt))

(defn driver-jvm-opts
  "JVM opts for driver processes. -XX:+EnableDynamicAgentLoading is
   only meaningful on JDK 22+; on JDK 21 it is silently ignored, but
   we omit it explicitly so the intent is visible and the flag surface
   matches the running host."
  []
  (cond-> base-driver-opts
    (>= (java-major-version) 22) (into dynamic-agent-loading-opts)))

(defn- basis-classpath
  [basis]
  (str/join java.io.File/pathSeparator (map str (:classpath-roots basis))))

(defn- ensure-success!
  [label result]
  (when-not (zero? (:exit result))
    (throw (ex-info (str label " failed") result)))
  result)

(defn clean
  [_]
  (b/delete {:path "target"})
  nil)

(defn- reset-derived-dir!
  "Deletes `dir` and recreates it empty.

   Both AOT output directories are pure derived state, yet `java-command!`
   puts them AHEAD of the source directories on the runner classpath. A
   leftover .class therefore wins over the source it was generated from:
   an edited namespace that was never recompiled, or a WIP revision that
   got reverted, keeps running. A stale
   `nihilite.test.indy_driver$weave_BANG_` from an uncommitted revision made
   `.write` land on the byte[] receiver and the driver reported a bogus
   ClassCastException plus DRIVER_FAIL; `clean` alone made it pass.

   Wiping before regenerating removes the whole class of fabricated result.
   Narrowing the classpath instead would hide it while still testing
   whatever happened to be in target/, and skipping the wipe would keep
   every single-driver run dependent on the previous run's leftovers."
  [dir]
  (let [f (io/file dir)]
    (when (.exists f)
      (b/delete {:path dir}))
    (.mkdirs f)))

(defn- compile-script-for
  "Returns a Clojure -e script that AOT-compiles the given kernel namespaces.
   Each namespace is `require`d first because clojure.core/compile demands
   the source on the classpath (it re-resolves the var to recover its body,
   which an unknown symbol cannot satisfy)."
  [names]
  (let [header "(binding [*compile-path* \"target/classes\" *compile-files* true]"
        body (str " (doseq [n '" (pr-str names) "] (require n) (compile n)))")]
    (str header body)))

(defn compile-clj
  [_]
  (reset-derived-dir! class-dir)
  (ensure-success!
    "Clojure compilation"
    (b/process {:command-args ["clojure" "-Sdeps"
                               "{:paths [\"src/main/clojure\" \"target/classes\"]}"
                               "-M" "-e"
                               (compile-script-for '[nihilite.kernel.exceptions
                                                     nihilite.kernel.bucket
                                                     ;; advice is AOT'd as a side effect of
                                                     ;; installer but was never declared, so
                                                     ;; class-dir shipped whatever bytes were
                                                     ;; left in it. Declare it: class-dir comes
                                                     ;; before src/main/clojure on the runner
                                                     ;; classpath, so an undeclared ns silently
                                                     ;; wins over the edited source.
                                                     nihilite.kernel.advice
                                                     nihilite.kernel.indy
                                                     nihilite.kernel.installer
                                                     nihilite.kernel.agent])]}))
  nil)

(defn- compile-test-driver-script []
  (str "(binding [*compile-path* \"target/test-classes\"]"
       " (doseq [n '[nihilite.test.retransform-driver"
       "               nihilite.test.redefine-instance-driver"
       "               nihilite.test.indy-driver"
       "               nihilite.test.prod-bootstrap-driver"
       "               nihilite.test.javaagent-classpath-driver"
       "               nihilite.test.eval-attach-driver]]"
       "   (require n) (compile n)))"))

(defn- compile-test-drivers!
  "AOT-compile the test driver namespaces (gen-class :main) into the
   test class directory so build.clj's java-command! can invoke them as
   `java nihilite.test.retransformDriver` etc."
  []
  (reset-derived-dir! test-class-dir)
  (doseq [d ["nihilite/test"]]
    (.mkdirs (io/file test-class-dir d)))
  (ensure-success!
   "Test driver Clojure compilation"
   (b/process {:command-args
               ["clojure" "-Sdeps"
                "{:paths [\"src/main/clojure\" \"src/test/clojure\" \"target/classes\"]}"
                "-M"
                "-e"
                (compile-test-driver-script)]}))
  nil)

(defn- copy-main-resources!
  []
  (b/copy-dir {:src-dirs ["src/main/clojure"]
               :target-dir class-dir})
  nil)

(defn uberjar
  [_]
  (clean nil)
  (compile-clj nil)
  (copy-main-resources!)
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :manifest manifest
           :exclude [#"META-INF/.*\\.(?i:SF|DSA|RSA)$"]})
  (println "Built" uber-file)
  nil)

(defn agent-jar
  [opts]
  (uberjar opts))

(defn- forbidden-entry?
  [entry]
  (some (fn [pattern]
          (or (str/starts-with? entry pattern)
              (str/includes? entry (str "/" pattern))
              (= entry pattern)))
        forbidden-entries))

(defn verify-jar
  [_]
  (when-not (.exists (io/file uber-file))
    (uberjar nil))
  (with-open [jar (JarFile. uber-file)]
    (let [entries (map #(.getName %) (enumeration-seq (.entries jar)))
          found (vec (filter forbidden-entry? entries))
          attributes (.getMainAttributes (.getManifest jar))
          missing (vec (for [[k v] manifest
                             :when (not= v (.getValue attributes k))]
                         [k (.getValue attributes k)]))]
      (when (seq found)
        (throw (ex-info "Forbidden entries bundled in JAR" {:entries found})))
      (when (seq missing)
        (throw (ex-info "Missing or incorrect manifest attributes"
                        {:attributes missing})))))
  (println "Verified" uber-file)
  nil)

(defn- java-command!
  [label main-class args extra-jvm-opts]
  (compile-clj nil)
  (compile-test-drivers!)
  (let [classpath (str test-class-dir java.io.File/pathSeparator
                       class-dir java.io.File/pathSeparator
                       "src/main/clojure" java.io.File/pathSeparator
                       "src/test/clojure" java.io.File/pathSeparator
                       (basis-classpath @test-basis))]
    (ensure-success!
     label
     (b/process {:command-args
                 (into ["java"]
                       (concat extra-jvm-opts
                               ["-cp" classpath main-class]
                               args))})))
  nil)

(defn clojure-contract-test
  [_]
  (java-command! "Clojure contract tests"
                 "clojure.main"
                 ["-m" "nihilite.test.runner"]
                 [])
  nil)

(defn retransform-driver
  [_]
  (java-command! "Retransform driver"
                 "nihilite.test.retransformDriver" [] (driver-jvm-opts))
  nil)

(defn javaagent-driver
  [_]
  (when-not (.exists (io/file uber-file))
    (uberjar nil))
  (java-command! "Java agent classpath driver"
                 "nihilite.test.javaagentClasspathDriver"
                  ["spawn-jar-smoke" (.getAbsolutePath (io/file uber-file))
                   "examples/jdkstdlib/init.clj"]
                 (driver-jvm-opts))
  nil)

(defn eval-attach-driver
  "Proves the eval wire protocol from another process: spawn a child with
   -javaagent, attach to it, and drive loadAgent with eval: requests."
  [_]
  (when-not (.exists (io/file uber-file))
    (uberjar nil))
  (java-command! "Eval attach driver"
                 "nihilite.test.evalAttachDriver"
                 [(.getAbsolutePath (io/file uber-file))]
                 (driver-jvm-opts))
  nil)

(defn redefine-instance-driver
  [_]
  (java-command! "Redefine instance driver"
                 "nihilite.test.redefineInstanceDriver" [] (driver-jvm-opts))
  nil)

(defn indy-driver
  [_]
  (java-command! "Indy driver"
                 "nihilite.test.indyDriver" [] (driver-jvm-opts))
  nil)

(defn prod-bootstrap-driver
  [_]
  (java-command! "Production bootstrap driver"
                 "nihilite.test.prodBootstrapDriver" [] (driver-jvm-opts))
  nil)

(defn probe
  "Runs a test driver and reports its real exit code instead of swallowing it.
   ensure-success! raises on any non-zero status and clojure -T then exits 1,
   which makes a System/exit probe indistinguishable from a plain failure — a
   diagnostic that cannot tell two outcomes apart is not a diagnostic.

   The main class comes from the PROBE_CLASS environment variable."
  [_]
  (compile-clj nil)
  (compile-test-drivers!)
  (let [result (b/process {:command-args
                           (into ["java"]
                                 (concat ["-cp" (str test-class-dir
                                                     java.io.File/pathSeparator
                                                     class-dir
                                                     java.io.File/pathSeparator
                                                     "src/main/clojure"
                                                     java.io.File/pathSeparator
                                                     "src/test/clojure"
                                                     java.io.File/pathSeparator
                                                     (basis-classpath @test-basis))]
                                         [(System/getenv "PROBE_CLASS")]
                                         (driver-jvm-opts)))})
        code (:exit result)]
    (println "PROBE-EXIT" (System/getenv "PROBE_CLASS") code)
    (flush)
    code))

(defn check
  [_]
  (uberjar nil)
  (verify-jar nil)
  (clojure-contract-test nil)
  (retransform-driver nil)
  (javaagent-driver nil)
  (eval-attach-driver nil)
  (redefine-instance-driver nil)
  (indy-driver nil)
  (prod-bootstrap-driver nil)
  (println "All tools.build checks passed")
  nil)
