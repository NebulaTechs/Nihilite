(ns nihilite.trainer.worker
  "Background worker thread that brings up Clojure-side state after the
   JVM has invoked premain/agentmain.

   Equivalent to the deleted nihilite.agent.Worker Java class. Runs on
   a dedicated thread started by nihilite.trainer.Agent.startWorkerOnce
   so that the premain call can return to the JVM immediately without
   blocking on Clojure runtime init.

   Note: avoids `(:require [clojure.java.api :as api])` because that
   namespace ships only as `Clojure.class` (no .clj source) in clojure
   1.12.x; requiring the namespace directly fails to find the source
   file at runtime when the project is bundled into a fat jar."
  (:import [java.util.logging Logger]
           [clojure.lang DynamicClassLoader Compiler])
  (:require [clojure.tools.logging :as log]
            [clojure.tools.logging.impl :as logimpl]
            [nihilite.crafter.jul :as jul]))

(def ^:private worker-log
  "JUL Logger backing the worker lifecycle messages. Obtained via
   tools.logging's factory so the message routes to the user's logging
   backend (JUL/SLF4J/Log4j2) without pulling in clojure.java.api."
  (let [^Logger l (logimpl/get-logger log/*logger-factory* "nihilite.worker")]
    l))

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

(def ^:private dispatch-ready-timeout-ms
  "How long init-clojure waits for a var that another thread is still loading."
  5000)

(defn- await-var
  "Waits for a var to become bound, then returns it.

   require-ns is not a synchronization point. clojure.core/load-one evaluates
   the whole file first and only then records the lib, so a thread requiring a
   namespace another thread is already loading sees it as not loaded, calls
   load-one again, and RT/var can then land while that second load is still
   evaluating -- handing back a var whose root is Var$Unbound, which throws
   IllegalStateException on invoke.

   The transformer needs this namespace for spec lookup and runs on whichever
   thread is loading a class, so it really does load concurrently with the
   worker. Waiting is not a guess: the other thread's load will finish and bind
   the var. Bounded, then a throw -- an agent with no redefine dispatcher cannot
   redefine anything, and a :redefine method body that cannot reach its bridge
   returns the stub default instead, silently."
  ^clojure.lang.Var [^String ns-name ^String var-name]
  (let [v (clojure-var ns-name var-name)
        deadline (+ (System/currentTimeMillis) dispatch-ready-timeout-ms)]
    (loop []
      (cond
        (.isBound v) v
        (< (System/currentTimeMillis) deadline)
        (do (Thread/sleep 20) (recur))
        :else
        (throw (ex-info (str "var never became bound: " ns-name "/" var-name)
                        {:ns ns-name
                         :var var-name
                         :timeout-ms dispatch-ready-timeout-ms}))))))

(defn- init-clojure
  "Loads the core Clojure namespaces and installs the redefine dispatcher
   bridge.

   Nothing here is caught. This runs before the dispatcher exists, so a
   swallowed failure leaves every :redefine hook wired to a method body that
   cannot reach its bridge: the method returns the stub default and nothing
   reports it. That is the failure mode this namespace used to have."
  []
  (require-ns 'clojure.core)
  (require-ns 'nihilite.builder.registry)
  (require-ns 'nihilite.builder.registry.dispatch)
  (let [install-redisp (await-var "nihilite.builder.registry.dispatch"
                                   "install-redefine-dispatcher!")
        result (.invoke ^clojure.lang.IFn install-redisp)]
    (jul/info worker-log (str "[Nihilite] redefine dispatcher installed: " result))))

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
    (jul/info worker-log (str "[Nihilite] Compiler/LOADER bound:" (.getName (class loader))))))

(defn init-and-bind
  "Brings Clojure-side state online. Called from the agent worker thread.
   Binds *ns* to this namespace so log calls resolve the logger name to
   `nihilite.worker` instead of a stack-frame class name.

   Not caught. The caller counts the worker-ready latch down in a finally, so a
   throw here surfaces as a stack trace without wedging premain -- which is the
   point: a worker that failed leaves the agent half installed, and swallowing
   that is how the redefine dispatcher went missing for weeks."
  []
  (binding [*ns* (find-ns 'nihilite.trainer.worker)]
    (init-clojure)
    (bind-compiler-loader)))
