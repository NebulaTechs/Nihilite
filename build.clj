(ns build
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b])
  (:import [java.util.jar JarFile]))

(def lib 'nihilite/nihilite)
(def version "0.1")
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

(def driver-jvm-opts
  ["-Djdk.attach.allowAttachSelf=true" "-XX:+EnableDynamicAgentLoading"])

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

(defn javac-java
  "Stub kept for tools.build task compatibility. There is no longer any
   Java source under src/main/java (or src/test/java) -- the build is
   pure Clojure + gen-class-driven AOT. This task is a no-op."
  [_]
  (.mkdirs (io/file class-dir))
  nil)

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
  (.mkdirs (io/file class-dir))
  (ensure-success!
   "Clojure compilation"
   (b/process {:command-args
               ["clojure" "-Sdeps" (str "{:deps {net.bytebuddy/byte-buddy {:mvn/version \"1.18.13\"}}"
                                        " :paths [\"src/main/clojure\"]}")
                "-M"
                "-e"
                (compile-script-for '[nihilite.kernel.exceptions
                                     nihilite.kernel.advice
                                     nihilite.kernel.dispatcher
                                     nihilite.kernel.bucket
                                     nihilite.kernel.transformer
                                     nihilite.kernel.installer
                                     nihilite.kernel.agent])]}))
  nil)

(defn- compile-test-driver-script []
  (str "(binding [*compile-path* \"target/test-classes\"]"
       " (doseq [n '[nihilite.test.retransform-driver"
       "               nihilite.test.redefine-instance-driver"
       "               nihilite.test.compiler-loader-hint-driver"
       "               nihilite.javaagent-classpath-driver]]"
       "   (require n) (compile n)))"))

(defn- compile-test-drivers!
  "AOT-compile the test driver namespaces (gen-class :main) into the
   test class directory so build.clj's java-command! can invoke them as
   `java nihilite.test.retransformDriver` etc."
  []
  (.mkdirs (io/file test-class-dir))
  (ensure-success!
   "Test driver Clojure compilation"
   (b/process {:command-args
               ["clojure" "-Sdeps" (str "{:deps {net.bytebuddy/byte-buddy {:mvn/version \"1.18.13\"}}"
                                        " :paths [\"src/main/clojure\" \"src/test/clojure\" \"target/classes\"]}")
                "-M"
                "-e"
                (compile-test-driver-script)]}))
  nil)

(defn- copy-main-resources!
  []
  (b/copy-dir {:src-dirs ["src/main/clojure" "resources"]
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
  (compile-test-drivers!)
  (let [classpath (str test-class-dir java.io.File/pathSeparator
                       class-dir java.io.File/pathSeparator
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
                 ["-Dnrepl.disable.tools=true"])
  nil)

(defn retransform-driver
  [_]
  (java-command! "Retransform driver"
                 "nihilite.test.retransformDriver" [] driver-jvm-opts)
  nil)

(defn compiler-loader-driver
  [_]
  (java-command! "Compiler loader driver"
                 "nihilite.test.compilerLoaderHintDriver" [] driver-jvm-opts)
  nil)

(defn javaagent-driver
  [_]
  (when-not (.exists (io/file uber-file))
    (uberjar nil))
  (java-command! "Java agent classpath driver"
                 "nihilite.javaagentClasspathDriver"
                 ["spawn-jar-smoke" (.getAbsolutePath (io/file uber-file))
                  "examples/jdkstdlib/init.clj"]
                 (conj driver-jvm-opts "-Dnihilite.compiler-loader-hint="))
  nil)

(defn redefine-instance-driver
  [_]
  (java-command! "Redefine instance driver"
                 "nihilite.test.redefineInstanceDriver" [] driver-jvm-opts)
  nil)

(defn check
  [_]
  (uberjar nil)
  (verify-jar nil)
  (clojure-contract-test nil)
  (retransform-driver nil)
  (compiler-loader-driver nil)
  (javaagent-driver nil)
  (redefine-instance-driver nil)
  (println "All tools.build checks passed")
  nil)
