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
  {"Premain-Class" "nihilite.trainer.Agent"
   "Agent-Class" "nihilite.trainer.Agent"
   "Can-Redefine-Classes" "true"
   "Can-Retransform-Classes" "true"
   "Main-Class" "nihilite.trainer.Agent"
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
                               (compile-script-for '[nihilite.crafter.exceptions
                                                     nihilite.trainer.bucket
                                                     ;; advice is AOT'd as a side effect of
                                                     ;; installer but was never declared, so
                                                     ;; class-dir shipped whatever bytes were
                                                     ;; left in it. Declare it: class-dir comes
                                                     ;; before src/main/clojure on the runner
                                                     ;; classpath, so an undeclared ns silently
                                                     ;; wins over the edited source.
                                                     nihilite.trainer.advice
                                                     nihilite.trainer.indy
                                                     nihilite.trainer.installer
                                                     nihilite.trainer.agent])]}))
  nil)

(defn- compile-test-driver-script
  "The AOT list, as a Clojure form to be evaluated by a child `clojure -M`.

   Kept as a string rather than a vector spliced at run time because it IS
   code: the child has to resolve and compile each namespace, and comments
   inside the form would be parsed as code by that child. Anything to say
   about a driver's status belongs here, in this docstring.

   `nihilite.test.hostile-target-driver` is compiled but never run by
   `check`, and deliberately so: several of its targets overflow the stack
   by design, which would take the whole run down. It is the source of the
   README's Limits table, so it has to stay runnable -- see
   docs/hook-limits.md for driving one target per JVM."
  []
  (str "(binding [*compile-path* \"target/test-classes\"]"
       " (doseq [n '[nihilite.test.retransform-driver"
       "               nihilite.test.redefine-instance-driver"
       "               nihilite.test.indy-driver"
       "               nihilite.test.prod-bootstrap-driver"
       "               nihilite.test.javaagent-classpath-driver"
       "               nihilite.test.eval-attach-driver"
       "               nihilite.test.dual-loader-driver"
       "               nihilite.test.concurrent-invoke-driver"
       "               nihilite.test.multi-hook-fanout-driver"
       "               nihilite.test.hostile-target-driver]]"
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
  "Runs `main-class` in a child JVM on the AOT + source classpath.

   `args` is stringified: `clojure -T:build task -- 3` hands the task a Long,
   and ProcessBuilder rejects anything that is not a String with an
   ArrayStoreException naming the MapEntry it choked on.

   `extra-env` is a map of environment variables for the child. Drivers read
   their whole configuration from the environment because `clojure -T:build`
   forwards neither positional arguments nor -D flags to the task."
  ([label main-class args extra-jvm-opts]
   (java-command! label main-class args extra-jvm-opts nil))
  ([label main-class args extra-jvm-opts extra-env]
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
                  (mapv str
                        (concat ["java"]
                                extra-jvm-opts
                                ["-cp" classpath main-class]
                                args))
                  :env extra-env})))
   nil))

(def ^:private driver-specs
  "Every pass that runs a JVM, as data. def-drivers below turns each row
   into a task var, so `clojure -T:build <name>` still works and adding a
   pass is one row rather than a copied defn.

   :opts defaults to the shared driver JVM opts; [] means none, which is what
   a pass running under clojure.main rather than as a -main class wants.
   :uber? means the pass launches the packaged jar and so has to build it
   first. :args-fn is called with the jar path so that path is written once.
   :env reaches the child process, which is how HOSTILE_MODE selects the safe
   subset -- `clojure -T:build` forwards neither positional arguments nor -D
   flags to a task."
  {:clojure-contract-test
   {:label "Clojure contract tests"
    :main "clojure.main"
    :args ["-m" "nihilite.test.runner"]
    :opts []
   }
  :retransform-driver
   {:label "Retransform driver"
    :main "nihilite.test.retransformDriver"
    :args []
   }
  :javaagent-driver
   {:label "Java agent classpath driver"
    :main "nihilite.test.javaagentClasspathDriver"
    :args-fn (fn [jar] ["spawn-jar-smoke" jar "examples/jdkstdlib/init.clj"])
    :uber? true
   }
  :eval-attach-driver
   {:label "Eval attach driver"
    :main "nihilite.test.evalAttachDriver"
    :args-fn (fn [jar] [jar])
    :uber? true
    :doc "Proves the eval wire protocol from another process: spawn a child with
   -javaagent, attach to it, and drive loadAgent with eval: requests."
   }
  :redefine-instance-driver
   {:label "Redefine instance driver"
    :main "nihilite.test.redefineInstanceDriver"
    :args []
   }
  :indy-driver
   {:label "Indy driver"
    :main "nihilite.test.indyDriver"
    :args []
   }
  :prod-bootstrap-driver
   {:label "Production bootstrap driver"
    :main "nihilite.test.prodBootstrapDriver"
    :args []
   }
  :dual-loader-driver
   {:label "Dual loader driver"
    :main "nihilite.test.dualLoaderDriver"
    :args []
    :doc "Characterises one class name under two ClassLoaders: the registry has no
   ClassLoader dimension, so one spec serves both and a woven count reports
   how many loaded Classes share a name rather than how many hooks exist."
   }
  :concurrent-invoke-driver
   {:label "Concurrent invoke driver"
    :main "nihilite.test.concurrentInvokeDriver"
    :args []
    :doc "Characterises N threads inside one advice body: Nihilite's own :fired atom
   and :sequence AtomicLong are exact under contention, a user bridge's own
   unsynchronized state is not, and the per-thread reentrancy guard does not
   protect it."
   }
  :multi-hook-fanout-driver
   {:label "Multi hook fanout driver"
    :main "nihilite.test.multiHookFanoutDriver"
    :args []
    :doc "Proves that ONE woven call site reaches every hook on the method, in
   install order, and pins the one place fan-out is not total: :cancel ends
   the walk, :subscriber only cancels the event."
   }
  :hostile-target-driver
   {:label "Hostile target driver"
    :main "nihilite.test.hostileTargetDriver"
    :args []
    :doc "The characterisation pass behind the README's Limits table: walks a table
   of methods Nihilite's own machinery runs on, installs an :entry hook on
   each through the production install! path, and records what actually
   happens. It asserts nothing, because some of its targets are unsafe on
   purpose.

   Deliberately NOT part of `check`: `String.length` and friends overflow the
   stack, and a target that kills the JVM ends the run with every later
   target unmeasured. HOSTILE_TARGET_INDEX selects one target so that a
   lethal one costs you one measurement instead of the rest of the table;
   a negative index walks backwards.

   The index arrives as an environment variable, not a command-line
   argument, because `clojure -T:build` does not forward positional
   arguments to the task: `clojure -T:build hostile-target-driver 3` looks
   like a task named `3`, and even with `--` the task receives an empty
   args vector. HOSTILE_WARM_CALLS works the same way, for the same reason.

   The list order and each target's measured outcome are in
   docs/hook-limits.md. The last HOSTILE_BEGIN line in the log names whatever
   target killed the JVM."
   }
  :hostile-safe-driver
   {:label "Hostile target driver (safe subset)"
    :main "nihilite.test.hostileTargetDriver"
    :args []
    :env {"HOSTILE_MODE" "safe"}
    :doc "The part of the hostile-target table that can be a gate.

   The full table cannot be: several targets overflow the stack on purpose and
   a JVM that dies ends the run. The three targets tagged :check in
   hostile_target_driver.clj do not, and they cover the property that actually
   broke silently before -- that installing a hook on a bootstrap-loader
   class neither throws nor reports an empty woven count -- plus one target
   the project documents as working, which must really fire.

   Firing is left unconstrained for the other two. Object.hashCode and
   ZipFile.getEntry register and never fire today; asserting that would
   freeze the bug instead of measuring the boundary, so if a future change
   makes them fire, this still passes.

   One JVM for the whole subset, driven by HOSTILE_MODE=safe."
   }

  ;; `check` runs these in order. hostile-target-driver is absent on purpose: it
  ;; walks targets that overflow the stack on purpose, and a JVM that dies ends
  ;; the run with every later target unmeasured. See docs/hook-limits.md.
  :check-order [:clojure-contract-test
               :retransform-driver
               :javaagent-driver
               :eval-attach-driver
               :redefine-instance-driver
               :indy-driver
               :prod-bootstrap-driver
               :dual-loader-driver
               :concurrent-invoke-driver
               :multi-hook-fanout-driver
               :hostile-safe-driver]})

(defn- driver-fn
  "The task body every pass shares. What differs between passes is the row."
  [{:keys [label main args args-fn opts env uber?]}]
  (fn [_]
    (let [jar (when uber? (.getAbsolutePath (io/file uber-file)))]
      (when uber?
        (when-not (.exists (io/file uber-file))
          (uberjar nil)))
      (java-command! label main
                     (if args-fn (args-fn jar) args)
                     (or opts (driver-jvm-opts))
                     env))
    nil))

(defn- def-drivers!
  "Interns one task var per row of driver-specs, so `clojure -T:build <name>`
   reaches it, and returns a map of task keyword to that var.

   A function rather than a macro on purpose: a macro does not evaluate its
   arguments, so the table would arrive as a Symbol.

   intern copies no metadata onto the var it creates, so each row's :doc is
   attached with alter-meta! -- otherwise (doc retransform-driver) would answer
   nil for a task that has a page of explanation."
  [specs]
  (reduce
   (fn [acc [task spec]]
     (let [v (intern *ns* (symbol (name task)) (driver-fn spec))]
       (when (:doc spec)
         (alter-meta! v assoc :doc (:doc spec)))
       (assoc acc task v)))
   {}
   specs))


(def ^:private driver-fns
  "task keyword -> the fn def-drivers! interned for it. `check` calls through
   this rather than resolving a name: tools.build binds *ns* to the invoking
   namespace when it calls a task, so a name resolved at call time would not
   find these."
  (def-drivers! driver-specs))

(defn check
  "Builds the jar, verifies it, then runs every pass in :check-order.

   The order lives in driver-specs next to the passes themselves, so a pass
   cannot be listed in the run without also being defined, and cannot be
   defined without deciding whether it belongs in the run."
  [_]
  (uberjar nil)
  (verify-jar nil)
  (doseq [task (:check-order driver-specs)]
    ((driver-fns task) nil))
  (println "All tools.build checks passed")
  nil)
