(ns nihilite.trainer.agent
  "Java agent entry points, generated from Clojure.

   nihilite.trainer.Agent replaces the original nihilite.agent.Agent + Worker
   pair. It exposes the static entry points the JVM resolves via the JAR
   manifest:
     premain(String, Instrumentation)    Premain-Class, the -javaagent path
     agentmain(String, Instrumentation)  Agent-Class, the attach path
     main(String[])                      Main-Class, which only prints how to
                                         mount the jar -- see agent-main
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
            [nihilite.builder.registry :as registry]
            [nihilite.crafter.classgen :as cg]
            [nihilite.crafter.jul :as jul]))

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

   Also hands the winner to registry/register-instrumentation!. This function
   is the only place an Instrumentation is ever accepted -- premain, agentmain,
   and a driver calling install directly with a ByteBuddyAgent-obtained
   Instrumentation all route through it -- so publishing from here is what makes
   the same Instrumentation visible to registry/retransform-loaded-matching! and
   the advice classes. Without it, install! could never report a non-zero
   :woven-count on the driver path, and hooks installed through the public
   api would silently skip retransforming already-loaded classes."
  [^Instrumentation inst]
  (boolean
   (and inst
        (let [won? (.compareAndSet ^java.util.concurrent.atomic.AtomicReference
                                    registered-on nil inst)]
          (registry/register-instrumentation!
           (.get ^java.util.concurrent.atomic.AtomicReference registered-on))
          won?))))

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
      (let [agent-cls (Class/forName "nihilite.trainer.Agent")
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
                (jul/info agent-log (str "[Nihilite Agent] appended"
                               " " (.getName jar)
                               " to system classloader search")))))))
      (catch Exception _
        (jul/warn agent-log "[Nihilite Agent] appendToSystemClassLoaderSearch failed")))))

(defn- start-worker-once
  "Spawns the worker thread the first time it is called; subsequent
   premain/agentmain calls are no-ops. The thread body binds *ns* so
   tools.logging reports the right logger name in the worker code."
  []
  (when (agent-claimWorker)
    (let [proxy-fn (proxy [Runnable] []
                      (run []
                        (binding [*ns* (find-ns 'nihilite.trainer.agent)]
                          (try
                            (require (quote nihilite.trainer.worker))
                            ((resolve (quote nihilite.trainer.worker/init-and-bind)))
                            ;; No catch. The worker thread is non-daemon, so a
                            ;; throw here prints a stack trace and kills only
                            ;; this thread, and the latch still counts down so
                            ;; premain is not wedged. This was the third place a
                            ;; real startup failure disappeared into.
                            (finally
                              (agent-signalWorkerReady))))))
          worker (Thread. ^Runnable proxy-fn "nihilite-agent-worker")]
      (.setDaemon worker false)
      (.setContextClassLoader worker (ClassLoader/getSystemClassLoader))
      (.start worker))))

(defn- run-startup!
  "Hands the agent args to nihilite.builder.boot, which runs the init script and
   serves an eval request if the args carry one.

   Resolved through RT/var rather than required: the worker thread brings
   Clojure-side state up on its own schedule, and this must not race it."
  [args]
  ;; The worker deliberately does not require boot: boot is startup, not
  ;; worker state. RT/var resolves a var but does not load its namespace,
  ;; so the require has to happen here or the var comes back unbound.
  (require (quote nihilite.builder.boot))
  (let [v (clojure.lang.RT/var "nihilite.builder.boot" "run-startup!")]
    (when-not (.isBound v)
      (jul/error agent-log "[Nihilite] nihilite.builder.boot/run-startup! is not present; abort"))
    (when (.isBound v)
      ;; Pass the value, do not pack it. IFn.invoke(Object) is the
      ;; single-argument call, so handing it an Object[] would deliver the
      ;; array itself as the argument -- eval-request? then saw a non-String
      ;; and dropped the request without a word. And RT/vector is no help
      ;; either: it is varargs, so interop hands a nil agent-args straight
      ;; through as the array and the call dies on array length.
      (.invoke ^clojure.lang.IFn v args))))

(defn- run-startup-async!
  "Runs the init script on its own thread.

   premain has to return promptly: on the -javaagent path the JVM is holding
   the application's main thread, and a slow init script would be charged to
   application startup. Running it here rather than on the premain thread is
   also what makes -Dnihilite.init reachable at all on this path -- it used to
   be evaluated only from the Main-Class entry, so -javaagent: silently skipped
   it."
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
      (require (quote nihilite.trainer.installer))
      ((resolve (quote nihilite.trainer.installer/install)) inst)
      (jul/info agent-log (str "[Nihilite Agent] " label
                    " armed HookInstaller (ByteBuddy AgentBuilder)")))
    (start-worker-once)
    fresh?))

(defn agent-premain
  "Forwarded by nihilite.trainer.Agent.premain (JVM instrument entry).
   Arms the installer, starts the worker, and runs the init script in the
   background so the application's startup is not charged for it.

   Without an Instrumentation (driver path or no-attach smoke test) the call
   still returns cleanly and starts the worker, so a driver-side
   `awaitWorkerReady` resolves."
  [^String args ^Instrumentation inst]
  (binding [*ns* (find-ns 'nihilite.trainer.agent)]
    (let [t0     (System/nanoTime)
          fresh? (arm-agent! "premain" inst)]
      (run-startup-async! args)
      (when-not fresh?
        (jul/info agent-log (format "[Nihilite Agent] premain no-op (HookInstaller already registered for %s)"
                          (str (.get registered-on)))))
      (let [elapsed-ms (/ (- (System/nanoTime) t0) 1000000.0)]
        (jul/info agent-log (format "[Nihilite Agent] premain returned in %.0f ms" elapsed-ms))))))

(defn agent-agentmain
  "Forwarded by nihilite.trainer.Agent.agentmain (JVM dynamic-attach entry).

   Runs the init script and serves an eval request SYNCHRONOUSLY, unlike
   premain. The caller is a VirtualMachine.loadAgent that is blocked until
   this returns; replying from a background thread would let loadAgent return
   before the reply was written, and the attacher would read nothing."
  [^String args ^Instrumentation inst]
  (binding [*ns* (find-ns 'nihilite.trainer.agent)]
    (let [t0     (System/nanoTime)
          fresh? (arm-agent! "agentmain" inst)]
      (when-not fresh?
        (jul/info agent-log (format "[Nihilite Agent] agentmain no-op (HookInstaller already registered for %s)"
                          (str (.get registered-on)))))
      (agent-awaitWorkerReady)
      (run-startup! args)
      (let [elapsed-ms (/ (- (System/nanoTime) t0) 1000000.0)]
        (jul/info agent-log (format "[Nihilite Agent] agentmain returned in %.0f ms" elapsed-ms))))))

(def ^:const usage-banner
  "What `java -jar nihilite.jar` prints.

   The jar is an agent, not an application, and this entry point says so and
   stops. It used to evaluate -Dnihilite.init here, which looked like it
   worked and could not weave anything: Main-Class is handed a nil
   Instrumentation, so every hook stayed at :pending? true with woven-count 0
   and fired 0. Only premain and agentmain receive a real Instrumentation.

   Kept to three lines on purpose. Everything else -- how to mount it, what an
   init script can do, how to drive an attached JVM -- changes with the
   project, and a message baked into a shipped jar cannot be updated the way
   the repository can. So it points at the repository instead of restating it."
  (str "Nihilite -- bytecode hook agent for the JVM\n"
       "\n"
       "This is a stub: there is nothing to run here.\n"
       "\n"
       "For more information, see https://github.com/NebulaTechs/Nihilite\n"))

(defn agent-main
  "The JVM-lookup main(String[]) entry point -- the jar's Main-Class.

   Prints the usage banner and returns. It does not arm the installer, does
   not start the worker, and does not evaluate -Dnihilite.init: all three are
   reachable only from premain, where a real Instrumentation exists. gen-class
   :main true looks up `(str prefix main)`, which is this var, and forwards
   argv as ISeq<String>."
  [& _args]
  (binding [*ns* (find-ns 'nihilite.trainer.agent)]
    (println usage-banner)
    (flush)
    nil))(defn- generate-class!
  "Generate nihilite.trainer.Agent with its static entry points: premain and
   agentmain for the JVM, main for the jar's Main-Class, plus the worker's
   handshake methods. All are forwarded to Clojure vars with prefix agent-."
  []
  (let [string-cls (Class/forName "java.lang.String")
        instrumentation-cls (Class/forName "java.lang.instrument.Instrumentation")
        void-sym (symbol "void")
        object-cls (Class/forName "java.lang.Object")
        boolean-cls (Class/forName "java.lang.Boolean")]
    (cg/generate-class-bytes!
     {:name "nihilite.trainer.Agent"
      :prefix "agent-"
      :impl-ns "nihilite.trainer.agent"
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
                   {:static true})]})))

(defn gen-all!
  "Generates nihilite.trainer.Agent. Intended to run during AOT
   compilation of this namespace; a no-op outside of a compile because
   writeClassFile only writes when *compile-files* is set."
  []
  (generate-class!)
  nil)

(when *compile-files*
  (gen-all!))
