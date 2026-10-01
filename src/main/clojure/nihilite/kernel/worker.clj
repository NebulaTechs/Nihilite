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
  (:import [java.util.logging Logger]
           [clojure.lang DynamicClassLoader Compiler])
  (:require [clojure.tools.logging :as log]
            [clojure.tools.logging.impl :as logimpl]))

(def ^:private worker-log
  "JUL Logger backing the worker lifecycle messages. Obtained via
   tools.logging's factory so the message routes to the user's logging
   backend (JUL/SLF4J/Log4j2) without pulling in clojure.java.api."
  (let [^Logger l (logimpl/get-logger log/*logger-factory* "nihilite.worker")]
    l))

(defn- log-info [^String msg]
  (.info worker-log msg))

(defn- log-warn [msg]
  (.warning worker-log msg))

(defn- log-error [msg]
  (.severe worker-log msg))

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
  (require-ns 'nihilite.registry.dispatch)
  (try
    (let [install-redisp (clojure-var "nihilite.registry.dispatch" "install-redefine-dispatcher!")
          result (.invoke ^clojure.lang.IFn install-redisp)]
       (log-info (str "[Nihilite] redefine dispatcher installed: " result)))
    (catch Throwable t
      (log-warn (str "[Nihilite] install-redefine-dispatcher! failed: "
                     (.toString t))))))

(defn resolve-host-class-loader
  "Returns the classloader the Clojure Compiler should use."
  []
  (ClassLoader/getSystemClassLoader))

(defn- bind-compiler-loader
  "Replaces clojure.lang.Compiler/LOADER with a DynamicClassLoader that
   wraps the host classloader. Lets `require` resolve user classes at
   runtime via the classloaders the instrumented program already has."
  []
  (let [host-cl (resolve-host-class-loader)
        loader (DynamicClassLoader. host-cl)]
    (.bindRoot Compiler/LOADER loader)
    (log-info (str "[Nihilite] Compiler/LOADER bound:" (.getName (class loader))))))

(defn init-and-bind
  "Brings Clojure-side state online. Called from the agent worker thread.
   Binds *ns* to this namespace so log calls resolve the logger name to
   `nihilite.worker` instead of a stack-frame class name."
  []
  (binding [*ns* (find-ns 'nihilite.kernel.worker)]
    (try
      (init-clojure)
      (bind-compiler-loader)
      (catch Throwable t
        (log-error (str "[Nihilite] worker failed: " (.toString t)))))))
