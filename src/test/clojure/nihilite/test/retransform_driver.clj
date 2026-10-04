(ns nihilite.test.retransform-driver
  "Replaces src/test/java/nihilite/test/retransformDriver.java.

   Validates the full ByteBuddy advice + redefine path against a
   generated `nihilite.test.retransform_driver$DummyTarget` class. Each
   hook phase targets a different method because MethodDelegation and
   Advice on the same method clobber each other.

   Invoked from build.clj as `java nihilite.test.retransformDriver`.
   Driver class itself uses plain ns-form `:gen-class` (no annotations
   or array params); only the inner DummyTarget needs the reflection
   `generate-class` path because it ships alongside the driver.

   Run via gen-class to keep the driver protocol stable across builds
   without a Java source file."
  (:require [nihilite.registry :as reg]
            [nihilite.registry.stats :as stats]
            [nihilite.registry.dispatch]
            [nihilite.api :as api])
  (:import [net.bytebuddy.agent ByteBuddyAgent])
(:gen-class
    :name nihilite.test.retransformDriver
    :prefix "td-"
    :methods
    [[probe [long] String]
     [probeReturn [] String]
     [probeRedef [] String]
     [probeCancel [] String]
     [probeThrow [] String]
     [throwObserved [] int]
     [bodyExecutedAfterCancel [] boolean]
     [redefineBodyExecuted [] boolean]]
    :main true))

;; Driver state (matches the volatile fields of the old Java driver).

(def ^:private entered (atom 0))
(def ^:private return-mutated (atom 0))
(def ^:private redefined (atom 0))

;; DummyTarget class options, generated alongside this driver.

(def ^:private dummy-target-class
  "nihilite.test.retransform_driver.DummyTarget")

(def ^:private dummy-target-internal
  "nihilite/test/retransform_driver/DummyTarget")

(defn- generate-class-bytes! [options]
  (let [opts (merge {:load-impl-ns true} options)
        generate-class (Class/forName "clojure.core$generate_class")
        invoke-static (.getDeclaredMethod generate-class "invokeStatic"
                                         (into-array Class [Object]))]
    (.setAccessible invoke-static true)
    (let [[cname bytecode] (.invoke invoke-static nil (object-array [opts]))]
      (clojure.lang.Compiler/writeClassFile cname bytecode)
      cname)))

(defn- generate-dummy-target! []
  (let [string-cls (Class/forName "java.lang.String")
        int-cls Integer/TYPE
        boolean-cls Boolean/TYPE
        mk (fn [mname pclasses rclass]
             (with-meta (vector mname pclasses rclass) {:static true}))]
    (generate-class-bytes!
     {:name dummy-target-class
      :prefix "dt-"
      :impl-ns "nihilite.test.retransform-driver"
      :main false
      :methods
      [(mk (with-meta (symbol "probe") {}) [int-cls] string-cls)
       (mk (with-meta (symbol "probeReturn") {}) [] string-cls)
       (mk (with-meta (symbol "probeRedef") {}) [] string-cls)
       (mk (with-meta (symbol "probeCancel") {}) [] string-cls)
       (mk (with-meta (symbol "probeThrow") {}) [] string-cls)
        (mk (with-meta (symbol "throwObserved") {}) [] int-cls)
        (mk (with-meta (symbol "bodyExecutedAfterCancel") {}) [] boolean-cls)
        (mk (with-meta (symbol "redefineBodyExecuted") {}) [] boolean-cls)]})))

(defn gen-all!
  "Generates nihilite.test.retransform_driver.DummyTarget into *compile-path*."
  []
  (generate-dummy-target!)
  nil)

;; DummyTarget stub bodies (gen-class forwards static methods to these vars).

(defn dt-probe [x] (str "original-" x))
(defn dt-probeReturn [] "untouched-return")
(defn dt-probeRedef []
  ;; Records that the ORIGINAL body ran. A :redefine hook WRAPS the method,
  ;; so the original body must never execute; returning a distinct string
  ;; alone cannot prove that (the bridge could have produced the same
  ;; value), hence the explicit flag.
  (reset! stats/driver-redefine-body-executed? true)
  "ORIGINAL-BODY-RAN")
(defn dt-probeCancel []
  (reset! stats/driver-body-executed-after-cancel? true)
  "should-never-see")
(defn dt-probeThrow [] (throw (IllegalStateException. "driver-probe-throw")))
(defn dt-throwObserved [] (int @stats/driver-throw-observed))
(defn dt-bodyExecutedAfterCancel [] (boolean @stats/driver-body-executed-after-cancel?))
(defn dt-redefineBodyExecuted [] (boolean @stats/driver-redefine-body-executed?))

;; Spec bridge implementations.

(def ^:private reentry-depth (atom 0))
(def ^:private reentrant-fires (atom 0))
(def ^:private probe-method-ref (atom nil))
(def ^:private swapped-fires (atom 0))

(defn- entry-handler [_ctx]
  (swap! entered inc)
  ;; Re-entrancy check: call the hooked method again from inside the advice,
  ;; which is the same shape as the class loading cycle — the advice body needs
  ;; something the advice itself triggers. A working reentrancy guard suppresses
  ;; the nested run, so no bridge invocation happens at depth > 1.
  (let [d (swap! reentry-depth inc)]
    (when (and (> d 1) (< d 4))
      (swap! reentrant-fires inc))
    (when (and (= d 1) (some? @probe-method-ref))
      (try
        (.invoke ^java.lang.reflect.Method @probe-method-ref
                 nil (object-array [(long 99)]))
        (catch Throwable _ nil)))
    (swap! reentry-depth dec))
  nil)
(defn- return-handler [_ctx]
  (swap! return-mutated inc)
  "MUTATED-BY-DRIVER")

(defn- redefine-handler [_self _args _method-name]
  (swap! redefined inc)
  "REDEFINED-BY-DRIVER")

(defn- entry-cancel-handler [ctx]
  (let [cancel (resolve 'nihilite.registry.dispatch/ctx-cancel!)]
    (cancel ctx true))
  nil)

(defn- throw-handler [_ctx]
  (stats/increment-throw-observed!)
  nil)

(defn- install-all! []
  ((requiring-resolve 'nihilite.registry.dispatch/install-redefine-dispatcher!))
  (reg/clear!)
  (reg/install! {:id "driver-entry"
                 :target-internal dummy-target-internal
                 :method-name "probe"
                 :position :entry
                 :arity 1
                 :descriptor "(I)Ljava/lang/String;"
                 :bridge entry-handler
                 :note "driver :entry"})
  (reg/install! {:id "driver-return"
                 :target-internal dummy-target-internal
                 :method-name "probeReturn"
                 :position :return
                 :arity 0
                 :descriptor "()Ljava/lang/String;"
                 :action :modify
                 :bridge return-handler
                 :note "driver :return"})
  (reg/install! {:id "driver-redefine"
                 :target-internal dummy-target-internal
                 :method-name "probeRedef"
                 :position :redefine
                 :arity 0
                 :descriptor "()Ljava/lang/String;"
                 :bridge redefine-handler
                 :note "driver :redefine"})
  (reg/install! {:id "driver-entry-cancel"
                 :target-internal dummy-target-internal
                 :method-name "probeCancel"
                 :position :entry
                 :arity 0
                 :descriptor "()Ljava/lang/String;"
                 :action :cancel
                 :bridge entry-cancel-handler
                 :note "driver :entry :cancel"})
  (reg/install! {:id "driver-throw"
                 :target-internal dummy-target-internal
                 :method-name "probeThrow"
                 :position :throw
                 :arity 0
                 :descriptor "()Ljava/lang/String;"
                 :bridge throw-handler
                 :note "driver :throw"}))

(defn- fail! [why code]
  (println "retransformDriver FAIL:" why)
  (System/exit code))

(defn- target-class []
  (Class/forName dummy-target-class))

(defn- run-once! [inst]
  (let [target (target-class)
        probe (.getDeclaredMethod target "probe" (into-array Class [Integer/TYPE]))
        probeReturn (.getDeclaredMethod target "probeReturn" (into-array Class []))
        probeRedef (.getDeclaredMethod target "probeRedef" (into-array Class []))
        probeCancel (.getDeclaredMethod target "probeCancel" (into-array Class []))
        probeThrow (.getDeclaredMethod target "probeThrow" (into-array Class []))
        redefineBodyExecuted (.getDeclaredMethod target "redefineBodyExecuted"
                                              (into-array Class []))]

    ;; probe(int) -- :entry fires
    (reset! probe-method-ref probe)
    (let [result (.invoke probe nil (object-array [(int 1)]))]
      (when (not= @entered 1) (fail! (str "ENTERED=" @entered " expected 1") 3))
      ;; The entry bridge re-enters the hooked method on purpose. A working
      ;; reentrancy guard suppresses that nested advice, so the count stays 1
      ;; and reentry-depth never gets past 2. Without the guard the nested run
      ;; dispatches too and both climb.
      (when (pos? @reentrant-fires)
        (fail! (str "reentrancy guard did not suppress the nested advice run:"
                    " " @reentrant-fires
                    " re-entrant bridge invocations, expected 0") 29))
      (when (not= "original-1" result) (fail! (str "probe was \"" result "\" expected \"original-1\"") 4)))

    ;; install-status! :woven-count must report the real retransform count
    ;; (DummyTarget is loaded and modifiable here, so every one of the 5
    ;; driver specs must report 1 and :pending? must be false).
    (doseq [id ["driver-entry" "driver-return" "driver-redefine"
                "driver-entry-cancel" "driver-throw"]
            :let [st (reg/install-status! id)]]
      (when-not (= 1 (:woven-count st))
        (fail! (str "install-status! " id " :woven-count=" (:woven-count st)
                    " expected 1 (DummyTarget is loaded + modifiable)") 26))
      (when (:pending? st)
        (fail! (str "install-status! " id " :pending? true but 1 class woven") 27)))

    ;; probeReturn() -- :return mutates
    (let [r (.invoke probeReturn nil (object-array []))]
      (when (not= @return-mutated 1) (fail! (str "RETURN_MUTATED=" @return-mutated " expected 1") 5))
      (when (not= "MUTATED-BY-DRIVER" r) (fail! (str "probeReturn was \"" r "\" expected \"MUTATED-BY-DRIVER\"") 6)))

    ;; probeRedef() -- body REPLACED: the original body must not run
    (let [r (.invoke probeRedef nil (object-array []))
          ;; .invoke on a boolean-returning method yields a java.lang.Boolean,
          ;; which Clojure treats as truthy even when FALSE, so unwrap it.
          body-ran (boolean (.invoke redefineBodyExecuted nil (object-array [])))]
      (when (not= @redefined 1) (fail! (str "REDEFINED=" @redefined " expected 1") 7))
      (when (not= "REDEFINED-BY-DRIVER" r) (fail! (str "probeRedef was \"" r "\" expected \"REDEFINED-BY-DRIVER\"") 8))
      (when body-ran
        (fail! "probeRedef: the ORIGINAL method body executed; :redefine must wrap, not instrument" 28)))
    (stats/clear-driver-state!)

    ;; probeCancel() -- :entry :cancel short-circuits
    (try
      (.invoke probeCancel nil (object-array []))
      (fail! "probeCancel returned normally; expected HookCancelledException" 12)
      (catch java.lang.reflect.InvocationTargetException ite
        (when (not (instance? nihilite.kernel.HookCancelledException (.getCause ite)))
          (fail! (str "probeCancel cause was "
                      (when-let [c (.getCause ite)] (.getName (class c)))
                      " expected nihilite.kernel.HookCancelledException") 13))))
    (stats/clear-driver-state!)
    (when @stats/driver-body-executed-after-cancel? (fail! "probeCancel host body executed; short-circuit broken" 14))

    ;; probeThrow() -- :throw observes
    (try
      (.invoke probeThrow nil (object-array []))
      (fail! "probeThrow returned normally; expected throw" 15)
      (catch java.lang.reflect.InvocationTargetException ite
        (let [c (.getCause ite)]
          (when (not (and (instance? IllegalStateException c)
                          (= "driver-probe-throw" (.getMessage ^IllegalStateException c))))
            (fail! (str "probeThrow cause was "
                        (when c (str (.getName (class c)) ":" (.getMessage c)))
                        " expected IllegalStateException:driver-probe-throw") 16)))))
    (when (not= @stats/driver-throw-observed 1)
      (fail! (str "THROW_OBSERVED=" @stats/driver-throw-observed " expected 1") 17))

    ;; retransform and re-fire all three
    (try
      (.retransformClasses inst (into-array Class [target]))
      (catch Throwable t (fail! (str "retransformClasses threw " t) 9)))

    (.invoke probe nil (object-array [(int 2)]))
    (.invoke probeReturn nil (object-array []))
    (.invoke probeRedef nil (object-array []))

    (when (not (and (= @entered 2) (= @return-mutated 2) (= @redefined 2)))
      (fail! (str "after retransform ENTERED=" @entered
                  " RETURN_MUTATED=" @return-mutated " REDEFINED=" @redefined) 10))

    ;; swap-bridge
    ;;
    ;; The two bridges must be distinguishable through something the CALL can
    ;; observe, not through a counter they both bump. An earlier version of
    ;; this section swapped in a handler that also did (swap! entered inc) and
    ;; asserted the counter went up by one -- which is exactly what the ORIGINAL
    ;; bridge would have done too, so the assertion passed whether or not the
    ;; swap reached the woven call site at all. The observable is
    ;; install-status!'s per-spec :fired counter, and each spec gets its own.
    (let [entered-before  @entered
          fired-before    (:fired (reg/install-status! "driver-entry"))
          swapped-handler (fn [_ctx]
                            (swap! entered inc)
                            (swap! swapped-fires inc)
                            nil)]
      (reset! swapped-fires 0)
      (api/swap-bridge! "driver-entry" swapped-handler)
      (.invoke probe nil (object-array [(int 3)]))
      (when (not= @entered (inc entered-before))
        (fail! (str "ENTERED after swap-bridge = " @entered " expected " (inc entered-before)) 24))
      (when (not= 1 @swapped-fires)
        (fail! (str "after swap-bridge the call ran the ORIGINAL bridge"
                    " (swapped-bridge fired " @swapped-fires " time(s), expected 1)."
                    " A dispatch reads the spec out of by-id but calls the bridge"
                    " out of the method bucket, so a swap that rewrites only"
                    " by-id leaves every real invocation on the old bridge.") 25))
      (let [looked-spec (reg/lookup "driver-entry")
            current-bridge (:bridge looked-spec)
            fired-after (:fired (reg/install-status! "driver-entry"))]
        (when (not (clojure.lang.Util/identical current-bridge swapped-handler))
          (fail! "post-swap bridge is not swappedHandler" 26))
        (when (not= (inc (long fired-before)) (long fired-after))
          (fail! (str ":fired went " fired-before " -> " fired-after
                      " across the post-swap call, expected exactly +1"
                      " (a counter that under-reports hides a dead hook)") 27))))

    ;; post-retransform cancel + throw
    (try
      (.invoke probeCancel nil (object-array []))
      (fail! "post-retransform probeCancel returned normally" 18)
      (catch java.lang.reflect.InvocationTargetException ite
        (when (not (instance? nihilite.kernel.HookCancelledException (.getCause ite)))
          (fail! (str "post-retransform cancel cause was "
                      (when-let [c (.getCause ite)] (.getName (class c)))) 19))))
    (try
      (.invoke probeThrow nil (object-array []))
      (fail! "post-retransform probeThrow returned normally" 20)
      (catch java.lang.reflect.InvocationTargetException ite
        (when (not (instance? IllegalStateException (.getCause ite)))
          (fail! (str "post-retransform throw cause was "
                      (when-let [c (.getCause ite)] (.getName (class c)))) 21))))
    (when (not= @stats/driver-throw-observed 2)
      (fail! (str "post-retransform THROW_OBSERVED=" @stats/driver-throw-observed " expected 2") 22))
    (when @stats/driver-body-executed-after-cancel?
      (fail! "post-retransform probeCancel body still executed" 23))

    (let [mixed-entry (atom 0)
          mixed-redef (fn [_self _args _mname]
                        (str "redefine-saw-entry-" @mixed-entry))]
      ;; Two :redefine specs on one method are indistinguishable, so
      ;; remove the standalone one before installing the mixed pair.
      (api/uninstall! "driver-redefine")
      (reg/install! {:id "driver-mixed-entry"
                     :target-internal dummy-target-internal
                     :method-name "probeRedef"
                     :descriptor "()Ljava/lang/String;"
                     :position :entry
                     :action :observe
                     :bridge (fn [_ctx] (swap! mixed-entry inc) nil)
                     :note "entry hook sharing the method with :redefine"})
      (reg/install! {:id "driver-mixed-redefine"
                     :target-internal dummy-target-internal
                     :method-name "probeRedef"
                     :descriptor "()Ljava/lang/String;"
                     :position :redefine
                     :action :observe
                     :bridge mixed-redef
                     :note "redefine observing the entry hook's counter"})
      (stats/clear-driver-state!)
      (let [r (.invoke probeRedef nil (object-array []))]
        (when (not= 1 @mixed-entry)
          (fail! (str "co-located :entry hook fired " @mixed-entry
                      " time(s), expected 1; the :redefine wrap discarded the"
                      " entry instrumentation") 32))
        (when (not= "redefine-saw-entry-1" r)
          (fail! (str "redefine bridge saw \"" r "\" but expected"
                      " \"redefine-saw-entry-1\"; advice was not woven onto"
                      " the replacement body") 33)))
      ;; Uninstall the redefine hook: JVM reset strips the whole class, so
      ;; the surviving :entry hook proves the AgentBuilder re-woven it,
      ;; and the original body must be back.
      (api/uninstall! "driver-mixed-redefine")
      (reset! mixed-entry 0)
      (stats/clear-driver-state!)
      (let [r2 (.invoke probeRedef nil (object-array []))
            body-ran (boolean (.invoke redefineBodyExecuted nil (object-array [])))]
        (when (not= 1 @mixed-entry)
          (fail! (str "after uninstalling :redefine, the surviving :entry hook"
                      " fired " @mixed-entry " time(s), expected 1; the class"
                      " was reset but remaining hooks were not re-woven") 29))
        (when (not= "ORIGINAL-BODY-RAN" r2)
          (fail! (str "after uninstalling :redefine, probeRedef returned \"" r2
                      "\" expected \"ORIGINAL-BODY-RAN\"; the original body was"
                      " not restored") 30))
        (when (not body-ran)
          (fail! "after uninstalling :redefine the original body is still skipped" 31)))
      (api/uninstall! "driver-mixed-entry"))))

(defn td-main [& _args]
  (let [inst (ByteBuddyAgent/install)]
    ((requiring-resolve 'nihilite.registry.dispatch/install-redefine-dispatcher!))
    ((requiring-resolve 'nihilite.kernel.installer/install) inst)
    ;; Pre-load DummyTarget BEFORE registering the specs: install!
    ;; retransforms already-loaded matching classes, so the target has to
    ;; be loaded for the weave (and therefore install-status!'s
    ;; :woven-count) to be non-zero.
    (Class/forName dummy-target-class)
    ;; Register the driver's 5 hook specs so the transformer weaves them.
    ;; install! itself retransforms DummyTarget, so no separate
    ;; retransform-loaded-matching! call is needed here.
    (install-all!)
    (when (not= 1 (:woven-count (reg/install-status! "driver-entry")))
      (fail! "install-all! did not weave DummyTarget" 28))
    (run-once! inst)
    (reg/clear!)
    (when (not (empty? (reg/list-ids)))
      (fail! "list-ids not empty after clear" 11))
    (println "DRIVER_PASS retransform + :return-mutation + :redefine-substitution + :entry-cancel + :throw-observation + swap-bridge + install-status-woven-count all proven")
    (System/exit 0)))

(when *compile-files*
  (gen-all!))