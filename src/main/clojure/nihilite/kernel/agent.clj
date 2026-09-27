(ns nihilite.kernel.agent
  "Java agent entry points, generated from Clojure.

   nihilite.kernel.Agent replaces the original nihilite.agent.Agent + Worker
   pair. It exposes the four static entry points that JVM instrumentation
   resolves via the JAR manifest (Premain-Class, Agent-Class):
     premain(String, Instrumentation)
     agentmain(String, Instrumentation)
     main(String[])                       driver entry; awaited by tools
     awaitWorkerReady()                  blocks until Clojure runtime
                                         is up and the registry has
                                         install-redefine-dispatcher
                                         installed

   plus an accessor currentInstrumentation() that the advice classes
   use to look up the live Instrumentation when uninstalling a spec.

   The Worker thread is a plain Clojure Thread that calls initClojure()
   (require transport/boot/registry + install-redefine-dispatcher!) and
   bindCompilerLoader() before signaling Worker ready.

   Like the other kernel/* classes, this is emitted via the private
   generate-class function because the ns gen-class form spec rejects
   the java.lang.instrument.Instrumentation parameter type."
  (:import [java.lang.instrument Instrumentation]
           [java.util.concurrent.atomic AtomicBoolean AtomicReference]
           [java.util.logging Logger])
  (:require [clojure.tools.logging :as log]
            [clojure.tools.logging.impl :as logimpl]
            [nihilite.kernel.classgen :as cg]))

(def ^:private agent-log
  "Java.util.logging.Logger named `nihilite.agent`. Obtained via
   tools.logging's factory so the underlying backend follows the user's
   `clojure.tools.logging.factory` system property (JUL/SLF4J/Log4j2);
   cast to Logger because the JUL impl returns a Logger directly and
   the SLF4J/Log4j2 impls return their own types, but the kernel log
   calls use reflection on `Logger` methods — so we always go through
   the JUL adapter for the premain/agent path that has no Clojure
   source available inside the fat jar."
  (let [^Logger l (logimpl/get-logger log/*logger-factory* "nihilite.agent")]
    l))

(defn- log-info [msg]
  (.info agent-log msg))

(defn- log-warn [msg]
  (.warning agent-log msg))

(defn- log-error [msg]
  (.severe agent-log msg))

(defonce ^:private registered-on
  (AtomicReference.))

(defonce ^:private worker-started
  (AtomicBoolean. false))

(defonce ^:private system-search-extended
  (AtomicBoolean. false))

(defonce ^:private worker-ready-latch
  (java.util.concurrent.CountDownLatch. 1))

(defn agent-currentInstrumentation
  "The Instrumentation instance captured at premain/agentmain time,
   or nil when the agent is not armed."
  []
  (.get ^java.util.concurrent.atomic.AtomicReference registered-on))

(defn agent-resolveHostClassLoader
  "Returns the classloader the Clojure Compiler should use, honoring
   the `nihilite.compiler-loader-hint` system property. Exposed as a
   public Agent entry point for tools and tests that need to resolve
   the host classloader without running the full worker pipeline."
  []
  (try
    (let [worker-fn (requiring-resolve 'nihilite.kernel.worker/resolve-host-class-loader)]
      (when worker-fn (worker-fn)))
    (catch Throwable _
      (ClassLoader/getSystemClassLoader))))

(defn agent-awaitWorkerReady
  "Blocks until the Clojure-runtime worker thread has finished its
   initClojure + bindCompilerLoader steps."
  []
  (.await worker-ready-latch))

(defn agent-signalWorkerReady
  "Counts down the worker-ready latch; called by the worker thread
   in its finally clause."
  []
  (.countDown worker-ready-latch))

(defn agent-claimWorker
  "Atomically claims the right to start the worker thread. Returns
   true for the first caller, false thereafter."
  []
  (.compareAndSet worker-started false true))

(defn- extend-system-class-loader-search
  "When the agent runs from inside its own jar, append that jar to the
   system classloader search path so the rest of Nihilite (and ByteBuddy)
   can see it."
  [^Instrumentation inst]
  (when (and inst (.compareAndSet system-search-extended false true))
    (try
      (let [agent-cls (Class/forName "nihilite.kernel.Agent")
            pd (.getProtectionDomain agent-cls)
            location (when pd
                       (try (.getLocation pd)
                            (catch Exception _ nil)))
            url (when (and location
                           (= "file" (.toLowerCase (.getProtocol location))))
                  location)]
        (when url
          (let [jar (java.io.File. (java.net.URI. (.toString url)))]
            (when (.isFile jar)
              (with-open [jf (java.util.jar.JarFile. jar)]
                (.appendToSystemClassLoaderSearch inst jf)
                (log-info (str "[Nihilite Agent] appended"
                               " " (.getName jar)
                               " to system classloader search")))))))
      (catch Exception _
        (log-warn "[Nihilite Agent] appendToSystemClassLoaderSearch failed")))))

(defn- start-worker-once
  "Spawns the worker thread the first time it is called; subsequent
   premain/agentmain calls are no-ops. The thread body binds *ns* so
   tools.logging reports the right logger name in the worker code."
  []
  (when (agent-claimWorker)
    (let [proxy-fn (proxy [Runnable] []
                      (run []
                        (binding [*ns* (find-ns 'nihilite.kernel.agent)]
                          (try
                            (require (quote nihilite.kernel.worker))
                            ((resolve (quote nihilite.kernel.worker/init-and-bind)))
                            (catch Throwable t
                              (log-error (str "[Nihilite Agent] worker failed: "
                                              (.toString t))))
                            (finally
                              (agent-signalWorkerReady))))))
          worker (Thread. ^Runnable proxy-fn "nihilite-agent-worker")]
      (.setDaemon worker false)
      (.setContextClassLoader worker (ClassLoader/getSystemClassLoader))
      (.start worker))))

(defn agent-premain
  "Forwarded by nihilite.kernel.Agent.premain (JVM instrument entry).
   Without an Instrumentation (driver path or no-attach smoke test), the
   call still returns cleanly and starts the worker thread so that any
   driver-side `awaitWorkerReady` resolves."
  [^String _args ^Instrumentation inst]
  (binding [*ns* (find-ns 'nihilite.kernel.agent)]
    (let [t0 (System/nanoTime)]
      (extend-system-class-loader-search inst)
      (when (and inst (.compareAndSet registered-on nil inst))
        (try
          (require (quote nihilite.kernel.installer))
          ((resolve (quote nihilite.kernel.installer/install)) inst)
          (log-info "[Nihilite Agent] premain armed HookInstaller (ByteBuddy AgentBuilder)")
          (catch Throwable t
            (log-error (str "[Nihilite Agent] HookInstaller.install failed: "
                            (.toString t))))))
      (start-worker-once)
      (let [elapsed-ms (/ (- (System/nanoTime) t0) 1000000.0)]
        (log-info (format "[Nihilite Agent] premain returned in %.0f ms" elapsed-ms))))))

(defn agent-agentmain
  "Forwarded by nihilite.kernel.Agent.agentmain (JVM dynamic-attach entry)."
  [^String _args ^Instrumentation inst]
  (binding [*ns* (find-ns 'nihilite.kernel.agent)]
    (let [t0 (System/nanoTime)]
      (extend-system-class-loader-search inst)
      (if (and inst (.compareAndSet registered-on nil inst))
        (do
          (try
            (require (quote nihilite.kernel.installer))
            ((resolve (quote nihilite.kernel.installer/install)) inst)
            (log-info "[Nihilite Agent] agentmain armed HookInstaller (dynamic attach)")
            (catch Throwable t
              (log-error (str "[Nihilite Agent] HookInstaller.install failed: "
                              (.toString t)))))
          (start-worker-once))
        (do
          (when inst
            (log-info (format "[Nihilite Agent] agentmain no-op (HookInstaller already registered for %s)"
                              (str (.get registered-on)))))
          (start-worker-once)))
      (let [elapsed-ms (/ (- (System/nanoTime) t0) 1000000.0)]
        (log-info (format "[Nihilite Agent] agentmain returned in %.0f ms" elapsed-ms))))))

(defn agent-aMain
  "Driver entry used by nihilite.kernel.Agent.aMain. Performs the same
   work as premain then awaits the worker before handing control to
   nihilite.boot/-main. `args` is an ISeq<String> (possibly empty)."
  [& args]
  (binding [*ns* (find-ns 'nihilite.kernel.agent)]
    (agent-premain nil nil)
    (agent-awaitWorkerReady)
    (let [boot-main (clojure.lang.RT/var "nihilite.boot" "-main")]
      (when (or (nil? boot-main) (not (.isBound boot-main)))
        (log-error "[Nihilite] nihilite.boot/-main is not present; abort"))
      (when (and boot-main (.isBound boot-main))
        (.applyTo ^clojure.lang.IFn boot-main
                  (clojure.lang.RT/seq (into-array String args)))))))

(defn agent-main
  "The JVM-lookup main(String[]) entry point. gen-class :main true looks
   up `(str prefix main)` which equals `agent-main`. The gen-class
   forwarder invokes `IFn.applyTo(ISeq<String>)` on this var — variadic
   args receive each String from String[] as a separate parameter, so
   `args` here is an ISeq<String>. Forward to aMain which expects the
   same shape."
  [& args]
  (binding [*ns* (find-ns 'nihilite.kernel.agent)]
    (apply agent-aMain args)))

(defn- generate-class!
  "Generate nihilite.kernel.Agent with the standard five static entry
   points. All methods are forwarded to Clojure vars with prefix agent-."
  []
  (let [string-cls (Class/forName "java.lang.String")
        instrumentation-cls (Class/forName "java.lang.instrument.Instrumentation")
        class-cls (Class/forName "java.lang.ClassLoader")
        void-sym (symbol "void")
        object-cls (Class/forName "java.lang.Object")
        boolean-cls (Class/forName "java.lang.Boolean")
        string-array-cls (Class/forName "[Ljava.lang.String;")]
    (cg/generate-class-bytes!
     {:name "nihilite.kernel.Agent"
      :prefix "agent-"
      :impl-ns "nihilite.kernel.agent"
      :main true
      :methods
       [(with-meta (vector (symbol "currentInstrumentation") [] instrumentation-cls)
                  {:static true})
        (with-meta (vector (symbol "resolveHostClassLoader") [] class-cls)
                  {:static true})
        (with-meta (vector (symbol "awaitWorkerReady") [] object-cls) {:static true})
        (with-meta (vector (symbol "signalWorkerReady") [] object-cls) {:static true})
        (with-meta (vector (symbol "claimWorker") [] boolean-cls) {:static true})
        (with-meta (vector (symbol "premain")
                           [string-cls instrumentation-cls]
                           void-sym)
                   {:static true})
        (with-meta (vector (symbol "agentmain")
                           [string-cls instrumentation-cls]
                           void-sym)
                   {:static true})
        (with-meta (vector (symbol "aMain")
                           [string-array-cls]
                           void-sym)
                   {:static true})]})))

(defn gen-all!
  "Generates nihilite.kernel.Agent. Intended to run during AOT
   compilation of this namespace; a no-op outside of a compile because
   writeClassFile only writes when *compile-files* is set."
  []
  (generate-class!)
  nil)

(when *compile-files*
  (gen-all!))
