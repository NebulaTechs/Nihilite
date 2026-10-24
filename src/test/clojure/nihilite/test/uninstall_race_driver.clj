(ns nihilite.test.uninstall-race-driver
  "Characterisation driver: MEASURING the race between uninstall! and an
   in-flight hook invocation.

   WHY THIS DRIVER EXISTS. The registry removes a spec from by-id BEFORE it
   retransforms the target class, and it removes the spec's counter record
   (stats/remove-stats) before the retransform as well. The comment in
   registry.clj says the removal must come first -- hook-type-matches? reads
   the registry as its input, so leaving the spec in place would re-weave it
   and uninstall would do nothing.

   That ordering is correct, and it creates a window. Between the removal and
   the retransform, a call that is ALREADY inside the advice will still run
   the old bridge: the bridge was resolved from the bucket before the
   removal, and the woven call site is still in the bytecode until the
   retransform lands.

   THE THREE OBSERVABLES.

     R1 -- STALE BRIDGE STILL RUNS. A call that entered the advice before
          the uninstall completes still executes the old bridge afterwards.
          The question is not whether that happens (it must -- the bytecode
          was already executing) but whether anything in the registry's
          observable state agrees that it happened.

     R2 -- SILENT COUNTER LOSS. stats/remove-stats drops the StatsRecord
          while a call may still be in flight. stats/bump-fired! is
          (when-let [r (get-stats spec-id)] (swap! (:fired r) inc)) -- with
          the record gone, the increment is a NO-OP. So a bridge that ran
          after the uninstall leaves NO trace: the fire is invisible to
          :fired, :modified, :cancelled and :exceptions alike.

     R3 -- STATUS SELF-CONSISTENCY. install-status! after the uninstall
          reports :registered? false and :fired 0. Is that a lie? The
          bridge DID run. The status is self-consistent with the registry
          (the spec is gone) but not with the history (a fire happened and
          was not counted).

   WHAT IS ASSERTED, AND WHY IT IS STABLE. The driver asserts the SHAPE of
   the outcome, never a race-dependent count:

     - the stale bridge DOES run after uninstall! returns (the in-flight
       call was already past the lookup). This is a JVM property, not a
       race: the bytecode was already executing.
     - :fired after the uninstall is 0 EVEN THOUGH the bridge ran, because
       the counter record was removed while the call was in flight.
     - :registered? is false and lookup returns nil.

   The R1 count of stale fires is a MAGNITUDE and moves between runs; it is
   printed, not gated on. The R2/R3 facts are gated on because they are
   deterministic: the counter record is removed before the retransform, and
   the in-flight call is held across it by a latch.

   The driver therefore measures a real, reproducible property of the
   current design rather than a scheduling accident.

   Driver shape follows nihilite.test.concurrent-invoke-driver: ByteBuddyAgent
   for a real Instrumentation, reflective nihilite.trainer.Agent/premain, the
   production registry/install! path, a REAL FIRING rather than a woven
   count, fail! + System/exit with a distinct non-zero code per assertion
   family, and println+flush after every measurement so a crash still leaves
   the numbers in the log."
  (:require [nihilite.builder.registry :as reg]
            [nihilite.builder.registry.dispatch])
  (:import [net.bytebuddy.agent ByteBuddyAgent]
           [java.util.concurrent CountDownLatch TimeUnit])
  (:gen-class
    :name nihilite.test.uninstallRaceDriver
    :prefix "urd-"
    :main true))

(def ^:private target-class "nihilite.test.uninstall_race_driver.Target")
(def ^:private target-internal "nihilite/test/uninstall_race_driver/Target")
(def ^:private spec-id "urd-race")

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
      :prefix "ut-"
      :impl-ns "nihilite.test.uninstall-race-driver"
      :main false
      :methods [(mk (with-meta (symbol "probe") {}))]})))

(defn gen-all!
  "Generates the woven target class into *compile-path*."
  []
  (generate-target-class!)
  nil)

(defn ut-probe []
  (str "original-" (System/nanoTime)))

;; The instrumented call. The bridge parks on `entry-gate` before it does
;; anything else. The parking is what turns the race into something
;; deterministic: the caller thread is held INSIDE the advice, past the point
;; where the spec was resolved, while the main thread performs the uninstall.

(def ^:private entry-gate
  "Released by the main thread once the uninstall has been performed."
  (CountDownLatch. 1))

(def ^:private inside-gate
  "Counted down by the bridge as it enters, so the main thread knows the
   call is parked inside the advice and not merely about to enter it."
  (CountDownLatch. 1))

(def ^:private bridge-runs
  "How many times the bridge body actually ran. Tracked OUTSIDE the
   registry's own counters on purpose: the point of the driver is to
   compare what the bridge did against what the registry recorded."
  (atom 0))

(def ^:private park?
  "Whether the bridge should park. The control phase runs the same bridge
   with parking off, so the two phases differ only in whether the call was
   held across the uninstall."
  (atom true))

(defn- race-bridge
  "Parks inside the advice when `park?` is set, then counts. The count is
   incremented AFTER the gate, so a bridge that ran after the uninstall is
   counted here but cannot be counted by the registry (its counter record
   is gone)."
  [_ctx]
  (when @park?
    (.countDown inside-gate)
    (.await entry-gate 10 TimeUnit/SECONDS))
  (swap! bridge-runs inc)
  nil)

(defn- fail! [why code]
  (println "DRIVER_FAIL uninstall-race:" why)
  (flush)
  (System/exit code))

(defn- fired-count []
  (:fired (reg/install-status! spec-id)))

(defn- install-hook! []
  (reg/install! {:id spec-id
                 :target-internal target-internal
                 :method-name "probe"
                 :position :entry
                 :arity 0
                 :descriptor "()Ljava/lang/String;"
                 :bridge race-bridge
                 :note "uninstall-race driver: park inside the advice"}))

(defn- target-class-resolve []
  (Class/forName target-class))

(defn urd-main [& _args]
  (try
    (let [inst (ByteBuddyAgent/install)
          Agent (Class/forName "nihilite.trainer.Agent")
          premain (.getDeclaredMethod Agent "premain"
                                      (into-array Class [String
                                                         java.lang.instrument.Instrumentation]))]
      (.setAccessible premain true)
      (.invoke premain nil (object-array [nil inst])))
    (catch Throwable t
      (fail! (str "agent premain failed: " (.getMessage t)) 10)))
  (try
    ;; Pre-load the target BEFORE registering the spec: install! retransforms
    ;; already-loaded matching classes, so the target has to be loaded for the
    ;; weave to exist at all.
    (Class/forName target-class)
    (install-hook!)
    (catch Throwable t
      (fail! (str "install failed: " (.getMessage t)) 11)))
  (let [status (reg/install-status! spec-id)]
    (when-not (= 1 (:woven-count status))
      (fail! (str "install-status! :woven-count=" (:woven-count status)
                  " expected 1 (target is loaded and modifiable)") 12)))
  (println "DRIVER_SETUP target=" target-class
           " spec=" spec-id
           " :woven-count=1 (bytes rewritten, not yet a firing)")
  (flush)
  (let [target (target-class-resolve)
        probe  (.getDeclaredMethod target "probe" (into-array Class []))]
    ;; Control phase. The call is NOT held across anything: it runs to
    ;; completion while the spec is still registered. Its purpose is to
    ;; rule out the alternative explanation for R2 -- that bump-fired! is
    ;; simply broken and never counts anything. A fire that happens while
    ;; the spec is live MUST be counted; if this phase shows :fired 1,
    ;; then the R2 loss is caused by the uninstall, not by the counter.
    (reset! park? false)
    (.invoke probe nil (object-array []))
    (let [control-fired (fired-count)]
      (println "CONTROL fire while the spec was live: :fired =" control-fired)
      (flush)
      (when-not (= 1 control-fired)
        (fail! (str "control phase expected :fired 1 after one completed"
                    " call, got " control-fired
                    ". The counter itself is broken, which would make the"
                    " race measurement meaningless.") 13)))
    (reset! bridge-runs 0)
    ;; Race phase. The call is parked inside the advice, so the uninstall
    ;; lands while a call is in flight.
    (reset! park? true)
    (let [caller (Thread. (fn []
                             (try (.invoke probe nil (object-array []))
                                  (catch Throwable _ nil))))]
      (.start caller)
      ;; Wait until the bridge is parked INSIDE the advice. Without this the
      ;; uninstall could land before the call resolved the spec, and there
      ;; would be no in-flight call to race with.
      (let [parked? (.await inside-gate 10 TimeUnit/SECONDS)]
        (when-not parked?
          (fail! "bridge never entered the advice" 20))))
    (println "call is parked inside the advice")
    (flush)
    ;; Uninstall while the call is in flight. The elapsed time is the
    ;; WINDOW: how long the spec is absent from the registry while the
    ;; bytecode still carries the advice.
    (let [t0 (System/nanoTime)
          uninstalled (reg/uninstall! spec-id)
            window-us (quot (- (System/nanoTime) t0) 1000)
            status-after-uninstall (reg/install-status! spec-id)
            fired-after-uninstall (fired-count)]
        (println "uninstall! returned" (pr-str uninstalled))
        (println "  window (registry absent, bytecode still woven) =" window-us "us")
        (println "  :registered? after uninstall =" (:registered? status-after-uninstall))
        (println "  :fired after uninstall      =" fired-after-uninstall)
        (println "  lookup after uninstall      =" (pr-str (reg/lookup spec-id)))
        (flush)
        ;; A call that ARRIVES during the window finds no spec at all: the
        ;; advice re-reads the registry on every fire, and the spec is gone,
        ;; so the advice skips it and the target method runs untouched. That
        ;; is the correct behaviour for an unloaded hook -- no fire happened,
        ;; so there is nothing to count -- and it is what makes the in-flight
        ;; case the only one worth measuring. Verify it: the call must
        ;; succeed, the bridge must not run, and the method must return its
        ;; normal value.
        (let [arrival-result (.invoke probe nil (object-array []))
              runs-after-arrival @bridge-runs]
          (println "ARRIVAL a call that arrived after the uninstall returned"
                   (pr-str arrival-result))
          (println "  bridge runs after the arrival =" runs-after-arrival)
          (flush)
          (when-not (string? arrival-result)
            (fail! (str "the call that arrived after the uninstall returned "
                        (pr-str arrival-result) " instead of the target"
                        " method's own value") 34))
          (when-not (zero? runs-after-arrival)
            (fail! (str "the bridge ran for a call that arrived after the"
                        " uninstall: " runs-after-arrival " run(s). The advice"
                        " is supposed to find no spec and skip.") 35)))
        ;; Release the parked call.
        (.countDown entry-gate)
        (Thread/sleep 500)
        (let [runs @bridge-runs
              status-final (reg/install-status! spec-id)
              fired-final (fired-count)]
          (println "stale bridge runs after uninstall =" runs)
        (println "  :fired after the stale fire  =" fired-final)
        (println "  :registered? final          =" (:registered? status-final))
        (flush)
        ;; R1: the stale bridge MUST have run. The call was already past the
        ;; spec lookup when the uninstall landed, so the bridge body runs.
        ;; This is a JVM property, not a scheduling accident: the woven call
        ;; site was already executing.
        (when (zero? runs)
          (fail! "the in-flight call did not run the stale bridge" 30))
        ;; R2: the fire is INVISIBLE to the registry. The counter record was
        ;; removed by the uninstall while the call was in flight, so
        ;; bump-fired! found no record and did nothing. The bridge ran;
        ;; :fired says 0. The control phase above proved the counter works.
        (when-not (zero? fired-final)
          (fail! (str "expected :fired to be 0 after the stale fire, got " fired-final) 31))
        ;; R3: the status agrees with the registry, not with history.
        (when (:registered? status-final)
          (fail! "expected :registered? false after uninstall" 32))
        (when (some? (reg/lookup spec-id))
          (fail! "expected lookup to return nil after uninstall" 33))
        (println "MEASURED: the in-flight bridge ran" runs
                 "time(s) after uninstall! returned, and :fired reports"
                 fired-final "-- the fire left no trace in the registry.")
        (println "MEASURED: the window in which the spec is absent from the"
                 " registry while the bytecode still carries the advice was"
                 window-us "us on this run.")
        (flush)
        (println "DRIVER_PASS uninstall-race — the in-flight bridge ran" runs
                 "time(s) after uninstall! returned and :fired reported"
                 fired-final "-- the fire left no trace in the registry;"
                 " a call that arrived after the uninstall found no spec and"
                 " was skipped correctly.")
        (flush)
        (System/exit 0)))))

(when *compile-files*
  (gen-all!))
