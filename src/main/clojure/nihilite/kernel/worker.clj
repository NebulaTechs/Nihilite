(ns nihilite.kernel.worker
  "Background worker thread that brings up Clojure-side state after the
   JVM has invoked premain/agentmain.

   Equivalent to the deleted nihilite.agent.Worker Java class. Runs on
   a dedicated thread started by nihilite.kernel.Agent.startWorkerOnce
   so that the premain call can return to the JVM immediately without
   blocking on Clojure runtime init.

   Note: avoids `(:require [clojure.java.api :as api])` because that
   namespace ships only as `Clojure.class` (no .clj source) in clojure
   1.12.x; requiring the namespace directly fails to find the source
   file at runtime when the project is bundled into a fat jar."
  (:require [clojure.tools.logging :as log])
  (:import [clojure.lang DynamicClassLoader Compiler]))

(defn- clojure-var
  "Returns the IFn for a (ns-name, var-name) pair, without requiring
   `clojure.java.api` at compile- or load-time. Uses clojure.lang.RT/var
   so we don't depend on resolving a Namespace Class via Class/forName
   (which fails when the worker thread's context classloader can't find
   clojure.core)."
  [^String ns-name ^String var-name]
  (clojure.lang.RT/var ns-name var-name))

(defn- require-ns [sym]
  (.invoke ^clojure.lang.IFn (clojure-var "clojure.core" "require") sym))

(defn- init-clojure
  "Loads the core Clojure namespaces and installs the redefine dispatcher
   bridge. Errors are caught and logged so the worker can still complete
   the loader binding below."
  []
  (require-ns 'clojure.core)
  (require-ns 'nihilite.transport)
  (require-ns 'nihilite.boot)
  (require-ns 'nihilite.registry)
  (try
    (let [install-redisp (clojure-var "nihilite.registry" "install-redefine-dispatcher!")
          result (.invoke ^clojure.lang.IFn install-redisp)]
      (log/info "[Nihilite Agent] redefine dispatcher installed:" result))
    (catch Throwable t
      (log/warn t "[Nihilite Agent] install-redefine-dispatcher! failed"))))

(defn- find-hinted-class-loader
  "If `nihilite.compiler-loader-hint` is set and Instrumentation is
   available, returns the classloader of the first loaded class whose
   name matches the hint; otherwise returns nil."
  [hint]
  (let [agent-currentInst (resolve 'nihilite.kernel.agent/agent-currentInstrumentation)]
    (when agent-currentInst
      (let [inst (agent-currentInst)]
        (when inst
          (let [dot-name (.replace ^String hint "/" ".")]
            (try
              (some (fn [^Class c]
                      (when (and c (.equals dot-name (.getName c))
                                 (.getClassLoader c))
                        (.getClassLoader c)))
                    (.getAllLoadedClasses ^java.lang.instrument.Instrumentation inst))
              (catch Throwable t
                (log/warn t "[Nihilite Agent] hint lookup failed")
                nil))))))))

(defn resolve-host-class-loader
  "Returns the classloader the Clojure Compiler should use, honoring the
   `nihilite.compiler-loader-hint` system property when present."
  []
  (let [hint (System/getProperty "nihilite.compiler-loader-hint" "")]
    (if-not (seq hint)
      (ClassLoader/getSystemClassLoader)
      (let [match (find-hinted-class-loader hint)]
        (if match
          (do
            (log/info "[Nihilite Agent] compiler-loader hint '" hint
                      "' resolved to" match)
            match)
          (do
            (log/warn "[Nihilite Agent] compiler-loader hint '" hint
                      "' not found among loaded classes; "
                      "falling back to system classloader")
            (ClassLoader/getSystemClassLoader)))))))

(defn- bind-compiler-loader
  "Replaces clojure.lang.Compiler/LOADER with a DynamicClassLoader that
   wraps the resolved host classloader. Lets `require` resolve user
   classes at runtime via ByteBuddy-instrumented classloaders."
  []
  (let [host-cl (resolve-host-class-loader)
        loader (DynamicClassLoader. host-cl)]
    (.bindRoot Compiler/LOADER loader)
    (log/info "[Nihilite Agent] Compiler/LOADER bound:" (.getName (class loader)))))

(defn init-and-bind
  "Brings Clojure-side state online. Called from the agent worker thread.
   Binds *ns* to this namespace so tools.logging resolves the logger
   name to `nihilite.kernel.worker` instead of `clojure.tools.logging$eval...`."
  []
  (binding [*ns* (find-ns 'nihilite.kernel.worker)]
    (try
      (init-clojure)
      (bind-compiler-loader)
      (catch Throwable t
        (log/error t "[Nihilite Agent] worker failed")))))
