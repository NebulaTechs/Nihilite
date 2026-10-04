(ns nihilite.test.multi-hook-fanout-driver
  "Several hooks on ONE woven method, through the real ByteBuddy path.

   nihilite.test.multi-hook-fanout-test proves the fan-out by calling
   dispatch-for-spec directly, which is what the advice itself calls but skips
   everything around it: the reentrancy guard, the class-loading cycle it
   exists for, and the retransform that puts the advice on the method in the
   first place. This driver runs the same shape through a woven method, so a
   fan-out that works in a unit test and not in the JVM cannot pass.

   The question it answers is narrow and was open: ONE advice call site
   reaches N bridges. The advice looks a single spec id up and then walks the
   whole method bucket, so the woven path should fan out identically -- but
   the reentrancy guard wraps the entire walk, not each bridge, and it is the
   one thing the unit test does not exercise.

   The last two are the pair that look identical in a spec map and are not.
   :cancel ends the walk AND skips the host body, by throwing
   HookCancelledException out of the advice. :subscriber ends neither: the
   walk runs to the end of the bucket and the host body returns normally,
   the event simply arrives with :cancelled? already true. Neither the
   contract test nor any spec field says so.

   MEASURED, one woven method, three :entry hooks, JDK 27:

     3 hooks, 1 call               all 3 bridges ran, in install order
     :fired per hook               1 / 1 / 1
     original body                 intact -- fan-out does not replace it
     after uninstalling one        2 of 3 ran, removed one did not
     :cancel then 2 observers      observers did NOT run, call threw
                                   HookCancelledException, body skipped
     :subscriber then 2 observers  all 3 ran, body returned normally

   Driver shape follows nihilite.test.retransform-driver: ByteBuddyAgent for a
   real Instrumentation, reflective Agent/premain, the production
   registry/install! path, assertions on a REAL FIRING rather than on a woven
   count, fail! + System/exit with a distinct code per assertion family, and
   println+flush after every phase so a crash still leaves the numbers."
  (:require [nihilite.registry :as reg]
            [nihilite.registry.stats :as stats]
            [nihilite.registry.dispatch]
            [nihilite.api :as api])
  (:import [net.bytebuddy.agent ByteBuddyAgent])
  (:gen-class
    :name nihilite.test.multiHookFanoutDriver
    :prefix "mhfd-"
    :methods
    [[probe [] String]
     [probeCancel [] String]]
    :main true))

(def ^:private target-class "nihilite.test.multi_hook_fanout_driver.Target")

(def ^:private target-internal
  "nihilite/test/multi_hook_fanout_driver/Target")

(defn- generate-class-bytes!
  [options]
  (let [opts (merge {:load-impl-ns true} options)
        generate-class (Class/forName "clojure.core$generate_class")
        invoke-static (.getDeclaredMethod generate-class "invokeStatic"
                                         (into-array Class [Object]))]
    (.setAccessible invoke-static true)
    (let [[cname bytecode] (.invoke invoke-static nil (object-array [opts]))]
      (clojure.lang.Compiler/writeClassFile cname bytecode)
      cname)))

(defn- generate-target-class!
  []
  (let [string-cls (Class/forName "java.lang.String")
        mk (fn [mname]
             (with-meta (vector mname [] string-cls) {:static true}))]
    (generate-class-bytes!
     {:name target-class
      :prefix "mhft-"
      :impl-ns "nihilite.test.multi-hook-fanout-driver"
      :main false
      :methods [(mk (with-meta (symbol "probe") {}))
                (mk (with-meta (symbol "probeCancel") {}))]})))

(defn gen-all!
  []
  (generate-target-class!)
  nil)

(defn mhft-probe []
  (str "original-" (System/nanoTime)))

(defn mhft-probeCancel []
  (str "original-cancel-" (System/nanoTime)))

(def ^:private order (atom []))

(defn- note [id]
  (fn [_ctx]
    (swap! order conj id)
    nil))

(defn- fail!
  [why code]
  (println "multiHookFanoutDriver FAIL:" why)
  (flush)
  (System/exit code))

(defn- clear-observations! []
  (reset! order []))

(defn- observed []
  @order)

(defn- fired
  [id]
  (:fired (reg/install-status! id)))

(defn- spec
  [id method descriptor]
  {:id id
   :target-internal target-internal
   :method-name method
   :descriptor descriptor
   :position :entry
   :action :observe
   :bridge (note id)})

(defn- target-method
  ^java.lang.reflect.Method [name]
  (.getDeclaredMethod (Class/forName target-class) name (into-array Class [])))

(defn- invoke!
  [^java.lang.reflect.Method m]
  (.invoke m nil (object-array [])))

;; Phase 1: one advice call site reaches every bridge.

(defn- assert-fan-out!
  [m]
  (let [ids ["fan-1" "fan-2" "fan-3"]]
    (clear-observations!)
    (doseq [id ids] (reg/install! (spec id "probe" "()Ljava/lang/String;")))
    (when (not= 1 (:woven-count (reg/install-status! "fan-1")))
      (fail! (str "probe was not woven; :woven-count="
                  (:woven-count (reg/install-status! "fan-1"))) 2))
    (let [result (invoke! m)]
      (println "FANOUT probe returned" (pr-str result) "observed" (pr-str (observed)))
      (flush)
      (when (not= ids (observed))
        (fail! (str "one woven call reached " (count (observed)) " bridge(s), "
                    "expected all " (count ids) " in install order: " (pr-str (observed)))
               3))
      (doseq [id ids]
        (when (not= 1 (fired id))
          (fail! (str id " reported :fired " (fired id) " after one call, expected 1") 3)))
      (when-not (re-matches #"original-\d+" result)
        (fail! (str "probe returned " (pr-str result)
                    "; the original body did not survive the fan-out") 4))
      (println "  ASSERT fan-out        3 bridges from 1 call site, install order,"
               "each :fired=1, original body intact")
      (flush))
    ids))

;; Phase 2: removing one hook leaves the rest wired.

(defn- assert-uninstall-one!
  [m ids]
  (when-not (true? (api/uninstall! (first ids)))
    (fail! (str "uninstall! did not remove " (first ids)) 5))
  (clear-observations!)
  (let [result (invoke! m)]
    (println "AFTER-UNINSTALL observed" (pr-str (observed)))
    (flush)
    (when (some #{(first ids)} (observed))
      (fail! (str "the removed hook " (first ids) " still ran after uninstall!") 5))
    (when (not= (vec (rest ids)) (observed))
      (fail! (str "after removing " (first ids) " the survivors were "
                  (pr-str (observed)) ", expected " (pr-str (vec (rest ids)))) 5))
    (when-not (re-matches #"original-\d+" result)
      (fail! (str "probe returned " (pr-str result)
                  "; the original body did not come back") 6))
    (println "  ASSERT uninstall-one  " (count (rest ids)) " of " (count ids)
             " hooks still run, body intact")
    (flush))
  (doseq [id (rest ids)] (api/uninstall! id)))

(defn- clear-probe! []
  (doseq [id ["fan-1" "fan-2" "fan-3"]]
    (when (some? (reg/lookup id)) (api/uninstall! id))))

;; Phase 3 and 4: the two actions that touch the walk and the host body behave
;; differently in BOTH directions. :cancel ends the walk and throws out of the
;; advice, so the host body is skipped. :subscriber ends neither: the walk runs
;; to the end of the bucket and the host body returns normally, with the event
;; arriving already cancelled. The contract test cannot see this half -- there
;; is no host body there -- which is the reason this driver exists.

(defn- assert-action-boundary!
  [m-cancel method-cancel label action tail-expected body-skipped?]
  (let [ids (mapv #(str label "-" %) ["first" "second" "third"])]
    (clear-probe!)
    (reg/install! (spec (first ids) method-cancel "()Ljava/lang/String;"))
    (reg/install! (assoc (spec (second ids) method-cancel "()Ljava/lang/String;")
                         :action action
                         :bridge (fn [ctx]
                                   (swap! order conj (second ids))
                                   ((:cancel! ctx) true)
                                   nil)))
    (reg/install! (spec (last ids) method-cancel "()Ljava/lang/String;"))
    (clear-observations!)
    (let [outcome (try
                    {:value (invoke! m-cancel)}
                    (catch java.lang.reflect.InvocationTargetException ite
                      {:cause (.getCause ite)}))
          body-ran? (and (:value outcome)
                         (re-matches #"original-cancel-\d+" (:value outcome)))]
      (println label "outcome"
               (pr-str (if-let [v (:value outcome)] v (str (:cause outcome))))
               "observed" (pr-str (observed)))
      (flush)
      (when (not= tail-expected (observed))
        (fail! (str "after the :" action " hook the walk ran "
                    (pr-str (observed)) ", expected " (pr-str tail-expected)
                    " -- :" action " " label) 8))
      (when (not= body-skipped? (not body-ran?))
        (fail! (str "host body skipped?" (not body-ran?)
                    " under :" action ", expected " (not body-skipped?)
                    "; probeCancel "
                    (if body-ran?
                      (str "returned the original body " (pr-str (:value outcome)))
                      (str "threw " (some-> (:cause outcome) class .getSimpleName)))
                    ". :cancel throws HookCancelledException out of the advice;"
                    " :subscriber only marks the event cancelled and lets the"
                    " method return.") 7))
      (println "  ASSERT" label
               (str ": walk tail = " (pr-str tail-expected)
                    ", host body skipped = " (not body-ran?)))
      (flush))
    (doseq [id ids] (when (some? (reg/lookup id)) (api/uninstall! id)))))

(defn mhfd-main
  [& _args]
  (let [inst (ByteBuddyAgent/install)
        Agent (Class/forName "nihilite.kernel.Agent")
        premain (.getDeclaredMethod Agent "premain"
                                    (into-array Class [String
                                                       java.lang.instrument.Instrumentation]))]
    (.setAccessible premain true)
    (.invoke premain nil (object-array [nil inst]))
    ((requiring-resolve 'nihilite.registry.dispatch/install-redefine-dispatcher!))
    ;; Load the target BEFORE installing: install! retransforms already-loaded
    ;; matching classes, and a woven count is not a firing either way.
    (Class/forName target-class)
    (let [m-probe (target-method "probe")
          m-cancel (target-method "probeCancel")]
      (let [ids (assert-fan-out! m-probe)]
        (assert-uninstall-one! m-probe ids))
      (assert-action-boundary! m-cancel "probeCancel" "cancel" :cancel
                               ["cancel-first" "cancel-second"] true)
      (assert-action-boundary! m-cancel "probeCancel" "subscriber" :subscriber
                               ["subscriber-first" "subscriber-second"
                                "subscriber-third"] false)
      (reg/clear!)
      (stats/clear-driver-state!)
      (println (str "DRIVER_PASS multi-hook fan-out — one woven call site reached"
                    " every bridge in install order; :cancel ends the walk and"
                    " skips the host body, :subscriber does neither, and the"
                    " original body survived both"))
      (System/exit 0))))

(when *compile-files*
  (gen-all!))
