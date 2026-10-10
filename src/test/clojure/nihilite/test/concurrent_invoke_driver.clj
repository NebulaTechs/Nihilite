(ns nihilite.test.concurrent-invoke-driver
  "Characterisation driver: N threads invoking the SAME hooked method.

   Nothing else in this project covers the WOVEN-METHOD side of concurrency.
   nihilite.test.registry-atomic-install-test hammers install!/uninstall! from
   32 threads, but every existing driver calls its target method from one
   thread, so no test has ever had two threads inside one advice body at the
   same time.

   THE COLLISION SURFACE. The woven advice holds a reentrancy guard that is a
   plain java.lang.ThreadLocal (nihilite.trainer.advice/in-advice,
   read by advice/reentrancy-guard). It suppresses SAME-THREAD
   re-entry and nothing else. Two threads entering the same woven method both
   read a nil ThreadLocal, both set it, and both run the full advice body.
   There is no cross-thread mutual exclusion anywhere in the dispatch path --
   dispatch/dispatch-for-spec takes no lock, and
   dispatch/walk-bucket calls the bridge directly.

   THAT IS NOT A DEFECT. The guard exists to break a class-loading cycle, not
   to serialise callers, and a per-thread guard is the only thing that can
   break a per-thread cycle. This driver puts numbers on what follows from it,
   so the boundary is documented rather than re-discovered.

   THE MEASUREMENT. One hook is installed on one method of a generated target
   class, through the production registry/install! path with a real
   Instrumentation. Eight threads each call the method 1000 times, released
   together by a CountDownLatch, so they genuinely contend. The single bridge
   does, on every fire:

     - (swap! bridge-calls inc)                  ATOMIC   (Clojure atom)
     - (.put observed-sequences (:sequence ctx)) ATOMIC   (ConcurrentHashMap)
     - (aset-int racy-int-slot  inc (aget ...))  RACY     (plain int[])
     - (aset-long racy-long-slot inc (aget ...)) RACY     (plain long[])

   The racy pair is a non-atomic read-modify-write, wrapped in a try/finally.
   The finally is the whole synchronisation story and provides no mutual
   exclusion whatsoever -- which is the point being measured.

   THE FINDING, and the reason the two shapes sit in one bridge: under
   identical load, Nihilite's own runtime state is exact and the USER's bridge
   state is not. The :fired counters are per-spec Clojure atoms bumped with
   swap! (stats/bump-fired!) and the event sequence is one AtomicLong
   (registry/next-sequence) -- both atomic, so neither loses an update. A user
   bridge that mutates its own shared state without synchronisation WILL lose
   updates, and the reentrancy guard does not protect it, because the guard is
   per-thread: it is invisible to every thread but its own. README.md says
   nothing about what a bridge may share; this driver is the only place the
   boundary is measured.

   MEASURED on JDK 27, 8 threads x 1000 iterations = 8000 invocations,
   through the production install! path:

     :fired delta              8000 / 8000   exact, 0 lost, 0 double-counted
     distinct :sequence values 8000 / 8000   exact, none duplicated
     racy int[]                ~7460 / 8000   ~540 lost (~6.8%)
     racy long[]               ~7560 / 8000   ~445 lost (~5.6%)

   The first two are exact every run because both are atomic. The last two are
   LOSS MAGNITUDES and move between runs -- what is fixed is that they are
   strictly below 8000, because eight threads in a loop cannot avoid an
   overlapping read-modify-write pair. The driver asserts the bound, prints
   the number, and does not gate on it.

   WHAT IS ASSERTED, AND WHY IT IS STABLE. The driver asserts the SHAPE of
   the result, never a specific loss count:

     - :fired delta == invocation count, exactly. A lost or double-counted
       increment here means Nihilite's own counter is wrong.
     - the observed :sequence set has no duplicates and its size equals the
       invocation count. A non-atomic event counter shows up here and nowhere
       else, because :fired is a swap! loop and would paper over it.
     - BOTH racy slots finish STRICTLY BELOW the invocation count.

   Driver shape follows nihilite.test.retransform-driver: ByteBuddyAgent for a
   real Instrumentation, reflective nihilite.trainer.Agent/premain, the
   production registry/install! path, assertions on a REAL FIRING rather than on
   a woven count, fail! + System/exit with a distinct non-zero code per
   assertion family, and println+flush after every measurement so a crash still
   leaves the numbers in the log."
  (:require [nihilite.builder.registry :as reg]
            [nihilite.builder.registry.dispatch])
  (:import [net.bytebuddy.agent ByteBuddyAgent]
            [java.util.concurrent ConcurrentHashMap ConcurrentLinkedQueue
                                 CountDownLatch])
  (:gen-class
    :name nihilite.test.concurrentInvokeDriver
    :prefix "cid-"
    :main true))

(def ^:private thread-count
  "N workers. Registry-atomic-install-test uses 32; here the requirement is
   only that more than one thread is in the advice at once, which 8
   comfortably is on every box this project runs on."
  8)

(def ^:private iterations-per-thread
  "1000 x 8 = 8000 invocations, comfortably inside int range for the racy
   int[] slot and large enough for a non-atomic read-modify-write to lose a
   number worth reporting."
  1000)

(def ^:private target-class "nihilite.test.concurrent_invoke_driver.Target")
(def ^:private target-internal "nihilite/test/concurrent_invoke_driver/Target")
(def ^:private spec-id "cid-concurrent")

(defn- generate-class-bytes! [options]
  (let [opts (merge {:load-impl-ns true} options)
        generate-class (Class/forName "clojure.core$generate_class")
        invoke-static (.getDeclaredMethod generate-class "invokeStatic"
                                         (into-array Class [Object]))]
    (.setAccessible invoke-static true)
    (let [[cname bytecode] (.invoke invoke-static nil (object-array [opts]))]
      (clojure.lang.Compiler/writeClassFile cname bytecode)
      cname)))

(defn- generate-target-class! []
  (let [string-cls (Class/forName "java.lang.String")
        mk (fn [mname]
             (with-meta (vector mname [] string-cls) {:static true}))]
    (generate-class-bytes!
     {:name target-class
      :prefix "ct-"
      :impl-ns "nihilite.test.concurrent-invoke-driver"
      :main false
      :methods [(mk (with-meta (symbol "probe") {}))]})))

(defn gen-all!
  "Generates the woven target class into *compile-path*."
  []
  (generate-target-class!)
  nil)

(defn ct-probe []
  (str "original-" (System/nanoTime)))

(def ^:private bridge-calls
  "How many times the bridge body ran. Tracked separately from the :fired
   counter so a lost increment in one can be told apart from a lost bridge
   invocation in the other."
  (atom 0))

(def ^:private observed-sequences
  "Set of (:sequence ctx) values the bridge saw, as a ConcurrentHashMap key
   set. Deliberately not a Clojure atom holding a set: that would serialise
   every worker on one CAS and hide the contention being measured."
  (ConcurrentHashMap.))

(def ^:private racy-int-slot
  "Deliberately unsynchronized read-modify-write target: a plain int[1] read
   with aget and written with aset. No volatile, no lock, no CAS. Two threads
   that read the same element and write it back lose one of the two
   increments."
  (int-array 1))

(def ^:private racy-long-slot
  "Same shape at a different width, so the two racy measurements do not share
   one failure mode: a plain long[1]."
  (long-array 1))

(defn- concurrent-bridge
  "Runs once per fire of the hooked method, on whatever thread called it.

   The four mutations are the measurement: two atomic (the Clojure atom, the
   ConcurrentHashMap) and two not (the int[] and long[] read-modify-writes).
   Same fire, same thread, same instant -- so the difference between the two
   pairs of numbers is attributable to the mutation, not to the schedule."
  [ctx]
  (swap! bridge-calls inc)
  (.put observed-sequences (:sequence ctx) Boolean/TRUE)
  (try
    (aset-int racy-int-slot 0 (inc (aget racy-int-slot 0)))
    (finally
      (aset-long racy-long-slot 0 (inc (aget racy-long-slot 0)))))
  nil)

(defn- install-hook! []
  ((requiring-resolve 'nihilite.builder.registry.dispatch/install-redefine-dispatcher!))
  (reg/clear!)
  (reg/install! {:id spec-id
                 :target-internal target-internal
                 :method-name "probe"
                 :position :entry
                 :arity 0
                 :descriptor "()Ljava/lang/String;"
                 :bridge concurrent-bridge
                 :note "concurrent-invoke driver: 8 threads on one method"}))

(defn- fail! [why code]
  (println "concurrentInvokeDriver FAIL:" why)
  (flush)
  (System/exit code))

(defn- fired-count []
  (:fired (reg/install-status! spec-id)))

(defn- fired-delta
  "How much :fired moved since `before`.

  A DELTA, not the counter: :fired is cumulative for the life of the spec and
  the single-threaded warm call already fired it once. Comparing the raw
  counter against a phase's invocation count is off by every fire that
  happened before the phase started."
  ^long [before]
  (long (- (or (fired-count) 0) before)))

(defn- reset-measurements! []
  (reset! bridge-calls 0)
  (.clear observed-sequences)
  (aset-int racy-int-slot 0 0)
  (aset-long racy-long-slot 0 0)
  nil)

(defn- run-workers!
  "Calls the hooked method `iters` times on each of `n` daemon threads, all
   released together by `start`, and returns how many invocations completed.

   Daemon threads AND an explicit join, so the JVM's exit is deterministic
   either way. A throw inside a worker is captured through an uncaught
   exception handler rather than swallowed: the handler only records, and
   run-workers! re-throws the first one so a failed phase cannot look like a
   clean one."
  [^java.lang.reflect.Method m n iters]
  (let [start (CountDownLatch. 1)
        done (atom 0)
        thread-errors (ConcurrentLinkedQueue.)
        handler (reify Thread$UncaughtExceptionHandler
                  (uncaughtException [_ _t e] (.add thread-errors e)))
        workers (mapv
                 (fn [i]
                   (doto (Thread.
                           ^Runnable
                           (fn []
                             (.await start)
                             (dotimes [_ iters]
                               (.invoke m nil (object-array []))
                               (swap! done inc))
                             nil)
                           (str "cid-worker-" i))
                     (.setDaemon true)
                     (.setUncaughtExceptionHandler handler)))
                 (range n))]
    (doseq [^Thread t workers] (.start t))
    (.countDown start)
    (doseq [^Thread t workers] (.join t 300000))
    (let [still-running (filterv #(not= Thread$State/TERMINATED (.getState ^Thread %))
                                 workers)]
      (when (seq still-running)
        (fail! (str "workers still running after a 300s join: "
                    (mapv #(.getName ^Thread %) still-running)) 30)))
    (when-let [e (.peek thread-errors)]
      (throw e))
    @done))

(defn- measure! [^java.lang.reflect.Method m]
  (let [before (fired-delta 0)
        invocations (run-workers! m thread-count iterations-per-thread)
        fired (- (fired-delta 0) before)
        r {:invocations invocations
           :bridge-calls @bridge-calls
           :fired fired
           :sequences (.size observed-sequences)
           :racy-int (aget racy-int-slot 0)
           :racy-long (aget racy-long-slot 0)}]
    (println "MEASURED invocations=" (:invocations r)
             " bridge-calls=" (:bridge-calls r)
             " :fired-delta=" (:fired r)
             " distinct-sequences=" (:sequences r)
             " racy-int[]=" (:racy-int r)
             " racy-long[]=" (:racy-long r))
    (flush)
    r))

(defn- assert-real-firing! []
  (when (zero? (fired-count))
    (fail! (str "the single-thread warm call did not fire the bridge; a woven"
                " count of 1 is not evidence the advice runs") 2))
  nil)

(defn- assert-counter-integrity! [{:keys [invocations bridge-calls fired]}]
  (when (zero? bridge-calls)
    (fail! (str "no bridge invocation was observed, so a :fired of 0 would"
                " prove nothing; the hook is not actually firing") 3))
  (when (not= invocations fired)
    (fail! (str "counter integrity lost: " invocations
                " invocations across " thread-count " threads, but :fired is "
                fired " (delta " (- invocations fired)
                "). Every fire must bump the swap! atom exactly once.") 4))
  (println "  ASSERT counter-integrity  " invocations
           " invocations == " fired " :fired  (0 lost, 0 double-counted)")
  (flush))

(defn- assert-sequence-integrity! [{:keys [invocations sequences]}]
  (when (not= invocations sequences)
    (fail! (str "sequence integrity lost: " invocations
                " invocations but the observed :sequence set holds " sequences
                " distinct values (missing " (- invocations sequences)
                "). A non-atomic event counter shows up exactly here;"
                " duplicates would mean two fires shared a sequence number.")
       5))
  (println "  ASSERT sequence-integrity " sequences
           " distinct :sequence values, none duplicated, count == invocations")
  (flush))

(defn- assert-interleaving-loss! [{:keys [invocations racy-int racy-long fired]}]
  (when (not= invocations fired)
    (fail! (str "the atom-backed :fired is " fired " for " invocations
                " invocations. The racy bridge must be the only thing losing"
                " updates; Nihilite's own counter is exact.") 6))
  (when (>= racy-int invocations)
    (fail! (str "the racy int[] slot reached " racy-int " of " invocations
                " invocations. Expected a strict loss from concurrent"
                " non-atomic read-modify-write; reaching the full count would"
                " mean the bridge calls did not interleave and this driver is"
                " no longer measuring anything.") 7))
  (when (>= racy-long invocations)
    (fail! (str "the racy long[] slot reached " racy-long " of " invocations
                " invocations; same non-atomic read-modify-write, expected a"
                " strict loss.") 8))
  (println "  ASSERT interleaving-loss   racy int[] saw " racy-int "/"
           invocations " (" (- invocations racy-int) " lost, "
           (format "%.1f%%" (* 100.0 (/ (double (- invocations racy-int))
                                        (double invocations))))
           "), racy long[] saw " racy-long "/" invocations " ("
           (- invocations racy-long) " lost, "
           (format "%.1f%%" (* 100.0 (/ (double (- invocations racy-long))
                                        (double invocations))))
           ") — Nihilite's own :fired (" fired ") lost none")
  (flush))

(defn cid-main [& _args]
  (let [inst (ByteBuddyAgent/install)
        Agent (Class/forName "nihilite.trainer.Agent")
        premain (.getDeclaredMethod Agent "premain"
                                    (into-array Class [String
                                                       java.lang.instrument.Instrumentation]))]
    (.setAccessible premain true)
    ;; premain alone. agent/arm-agent! gates the AgentBuilder install on
    ;; agent/agent-registerInstrumentation's CAS, so it arms
    ;; exactly once -- but calling installer/install on top of that bypasses
    ;; the gate and installs a SECOND AgentBuilder on the same
    ;; Instrumentation, which weaves the advice twice and doubles every
    ;; :fired. That is a driver bug, not a runtime one: measured as :fired=2
    ;; for a single-threaded call. The redefine dispatcher is still ours to
    ;; install, since no :redefine hook is used here and the worker only
    ;; installs it for real use.
    (.invoke premain nil (object-array [nil inst]))
    ((requiring-resolve 'nihilite.builder.registry.dispatch/install-redefine-dispatcher!))
    ;; Pre-load the target BEFORE registering the spec: install! retransforms
    ;; already-loaded matching classes, so the target has to be loaded for the
    ;; weave (and therefore for :fired) to be non-zero.
    (Class/forName target-class)
    (install-hook!)
    (let [status (reg/install-status! spec-id)]
      (when (not= 1 (:woven-count status))
        (fail! (str "install-status! :woven-count=" (:woven-count status)
                    " expected 1 (target is loaded and modifiable)") 2))
      (when (:pending? status)
        (fail! "install-status! :pending? true but 1 class woven" 2)))
    (println "DRIVER_SETUP target=" target-class
             " spec=" spec-id
             " :woven-count=1 (bytes rewritten, not yet a firing)")
    (flush)
    (let [probe (.getDeclaredMethod (Class/forName target-class)
                                    "probe" (into-array Class []))]
      ;; Warm the weave on the main thread first, so the concurrent phase
      ;; measures contention rather than seven workers racing one worker's
      ;; cold bytecode. This also proves the advice runs at all before the
      ;; concurrent phase, which is the real-firing requirement.
      (println "DRIVER_WARM single-thread call returned"
               (pr-str (.invoke probe nil (object-array [])))
               ":fired=" (fired-count))
      (flush)
      (assert-real-firing!)
      (reset-measurements!)
      (let [result (measure! probe)]
        (assert-counter-integrity! result)
        (assert-sequence-integrity! result)
        (assert-interleaving-loss! result)))
    (reg/clear!)
    (println (str "DRIVER_PASS concurrent-invocation characterisation complete —"
                  "Nihilite's :fired atom and :sequence AtomicLong are exact under "
                  thread-count
                  "-thread contention on one method; a user bridge's own"
                  " unsynchronized state is not, and the per-thread reentrancy"
                  " guard does not protect it"))
    (System/exit 0)))

(when *compile-files*
  (gen-all!))
