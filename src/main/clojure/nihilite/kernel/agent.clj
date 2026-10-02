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
   (require registry + dispatch, then install-redefine-dispatcher!) and
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

(defn agent-registerInstrumentation
  "Publishes `inst` as the live Instrumentation, first-writer-wins. Returns
   true when this call installed it, false when another Instrumentation was
   already registered.

   nil.kernel.installer/install calls this so that any path which arms the
   AgentBuilder — premain, agentmain, or a driver calling install directly
   with a ByteBuddyAgent-obtained Instrumentation — makes the same
   Instrumentation visible to registry/retransform-loaded-matching! and the
   advice classes. Without it, install! could never report a non-zero
   :woven-count on the driver path, and hooks installed through the public
   api would silently skip retransforming already-loaded classes."
  [^Instrumentation inst]
  (boolean
   (and inst
        (.compareAndSet ^java.util.concurrent.atomic.AtomicReference
                        registered-on nil inst))))

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

(defn- run-startup!
  "Hands the agent args to nihilite.boot, which runs the init script and
   serves an eval request if the args carry one.

   Resolved through RT/var rather than required: the worker thread brings
   Clojure-side state up on its own schedule, and this must not race it."
  [args]
  (try
    ;; The worker deliberately does not require boot: boot is startup, not
    ;; worker state. RT/var resolves a var but does not load its namespace,
    ;; so the require has to happen here or the var comes back unbound.
    (require (quote nihilite.boot))
    (let [v (clojure.lang.RT/var "nihilite.boot" "run-startup!")]
      (when-not (.isBound v)
        (log-error "[Nihilite] nihilite.boot/run-startup! is not present; abort"))
      (when (.isBound v)
        ;; Pass the value, do not pack it. IFn.invoke(Object) is the
        ;; single-argument call, so handing it an Object[] would deliver the
        ;; array itself as the argument -- eval-request? then saw a non-String
        ;; and dropped the request without a word. And RT/vector is no help
        ;; either: it is varargs, so interop hands a nil agent-args straight
        ;; through as the array and the call dies on array length.
        (.invoke ^clojure.lang.IFn v args)))
    (catch Throwable t
      (log-error (str "[Nihilite] startup failed: " (.toString t))))))

(defn- run-startup-async!
  "Runs the init script on its own thread.

   premain has to return promptly: on the -javaagent path the JVM is holding
   the application's main thread, and a slow init script would be charged to
   application startup. This is also the fix for init being dead on that path
   -- it used to run only under boot/-main, which Main-Class reaches and
   -javaagent does not."
  [args]
  (doto (Thread. ^Runnable #(do (agent-awaitWorkerReady)
                                (run-startup! args))
                 "nihilite-startup")
    (.setDaemon true)
    (.start)))

(defn- arm-agent!
  "Installs the ByteBuddy transformer and starts the worker thread. Shared by
   every entry point so none of them can arm twice or forget the worker.

   Returns true when the Instrumentation was newly registered."
  [^String label ^Instrumentation inst]
  (extend-system-class-loader-search inst)
  (let [fresh? (agent-registerInstrumentation inst)]
    (when fresh?
      (try
        (require (quote nihilite.kernel.installer))
        ((resolve (quote nihilite.kernel.installer/install)) inst)
        (log-info (str "[Nihilite Agent] " label
                      " armed HookInstaller (ByteBuddy AgentBuilder)"))
        (catch Throwable t
          (log-error (str "[Nihilite Agent] HookInstaller.install failed: "
                          (.toString t))))))
    (start-worker-once)
    fresh?))

(defn agent-premain
  "Forwarded by nihilite.kernel.Agent.premain (JVM instrument entry).
   Arms the installer, starts the worker, and runs the init script in the
   background so the application's startup is not charged for it.

   Without an Instrumentation (driver path or no-attach smoke test) the call
   still returns cleanly and starts the worker, so a driver-side
   `awaitWorkerReady` resolves."
  [^String args ^Instrumentation inst]
  (binding [*ns* (find-ns 'nihilite.kernel.agent)]
    (let [t0     (System/nanoTime)
          fresh? (arm-agent! "premain" inst)]
      (run-startup-async! args)
      (when-not fresh?
        (log-info (format "[Nihilite Agent] premain no-op (HookInstaller already registered for %s)"
                          (str (.get registered-on)))))
      (let [elapsed-ms (/ (- (System/nanoTime) t0) 1000000.0)]
        (log-info (format "[Nihilite Agent] premain returned in %.0f ms" elapsed-ms))))))

(defn agent-agentmain
  "Forwarded by nihilite.kernel.Agent.agentmain (JVM dynamic-attach entry).

   Runs the init script and serves an eval request SYNCHRONOUSLY, unlike
   premain. The caller is a VirtualMachine.loadAgent that is blocked until
   this returns; replying from a background thread would let loadAgent return
   before the reply was written, and the attacher would read nothing."
  [^String args ^Instrumentation inst]
  (binding [*ns* (find-ns 'nihilite.kernel.agent)]
    (let [t0     (System/nanoTime)
          fresh? (arm-agent! "agentmain" inst)]
      (when-not fresh?
        (log-info (format "[Nihilite Agent] agentmain no-op (HookInstaller already registered for %s)"
                          (str (.get registered-on)))))
      (agent-awaitWorkerReady)
      (run-startup! args)
      (let [elapsed-ms (/ (- (System/nanoTime) t0) 1000000.0)]
        (log-info (format "[Nihilite Agent] agentmain returned in %.0f ms" elapsed-ms))))))

(defn- flatten-args
  "Normalize the variadic `args` of agent-aMain so `into-array String`
   always sees a flat ISeq of String. Two calling conventions exist:

     1. Via the gen-class `:main true` forwarder (Agent.main) →
        IFn.applyTo(ISeq<String>) — each String is unpacked, so args is
        an ISeq<String>.
     2. Via the gen-class `:methods` forwarder (Agent.aMain) →
        IFn.invoke(Object) — the whole String[] arrives as a single arg,
        so args is a 1-element ISeq wrapping String[].

   If args has exactly one element that is itself a String[], unwrap
   it. Otherwise pass args through unchanged."
  [args]
  (let [a (seq args)]
    (if (and a (nil? (next a))
             (instance? (Class/forName "[Ljava.lang.String;") (first a)))
      (seq (first a))
      a)))

(defn agent-aMain
  "Driver entry used by nihilite.kernel.Agent.aMain. Performs the same
   work as premain then awaits the worker before handing control to
   nihilite.boot/-main. Accepts `args` in either calling convention
   (see flatten-args): a flat ISeq<String> from the :main forwarder, or
   a 1-element ISeq wrapping the raw String[] from the :methods
   forwarder. Always coerces to a flat ISeq<String> before building
   the boot-main argv."
  [& args]
  (binding [*ns* (find-ns 'nihilite.kernel.agent)]
    (let [t0     (System/nanoTime)
          fresh? (arm-agent! "main" nil)]
      (when-not fresh?
        (log-info "[Nihilite] main no-op (HookInstaller already registered)"))
      (agent-awaitWorkerReady)
      (run-startup! (first (flatten-args args)))
      (let [elapsed-ms (/ (- (System/nanoTime) t0) 1000000.0)]
        (log-info (format "[Nihilite] main returned in %.0f ms" elapsed-ms))))))

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
