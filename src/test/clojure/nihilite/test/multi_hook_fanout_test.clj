(ns nihilite.test.multi-hook-fanout-test
  "Several hooks on ONE method, at the SAME position: every one of them runs.

   The advice does not carry a spec id per call site. It looks one up
   (dispatch/lookup-spec-for-call takes the first match) and then hands the
   whole method bucket to the walk (dispatch-for-spec -> reg/spec-bucket ->
   walk-bucket), and the walk visits every spec in it. So lookup picks who
   triggers the lookup, not who gets called: N hooks on one method all fire,
   in registration order.

   That is load-bearing and was unpinned. Every other multi-spec test in this
   project either uses two different positions (redefine-advice-composition,
   dispatch-common) or different methods, and return-type-check-test says in a
   comment that two specs on one method \"are indistinguishable to
   lookup-spec-for-call\" -- true of the lookup, but it left the fan-out itself
   unasserted, so an optimisation of spec-bucket down to its first element
   would have passed every test here and silently dropped every hook but one.

   No JVM: these go straight through dispatch-for-spec, which is what the
   advice itself calls. The woven path is proven separately by
   nihilite.test.multi-hook-fanout-driver."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.builder.registry :as reg]
            [nihilite.builder.registry.dispatch :as dispatch]
            [nihilite.test.fixtures :as fx]))

(def ^:private target "java/lang/String")

(defn- entry
  "An :entry observe hook on String.length that records itself in `log`."
  [id log]
  {:id id
   :target-internal target
   :method-name "length"
   :descriptor "()I"
   :position :entry
   :action :observe
   :bridge (fn [_] (swap! log conj id) nil)})

(defn- fired
  [id]
  (:fired (reg/install-status! id)))

(use-fixtures :each fx/reg-cleanup)

(deftest every-hook-on-the-method-runs
  (let [log (atom [])
        ids ["fan-a" "fan-b" "fan-c"]]
    (doseq [id ids] (reg/install! (entry id log)))
    (dispatch/dispatch-for-spec "fan-a" nil (object-array 0))
    (is (= ids @log)
        "the walk visits every spec in the method bucket, so all three ran")
    (doseq [id ids]
      (is (= 1 (fired id)) (str id " counted one fire")))))

(deftest a-hook-installed-last-still-runs
  (let [log (atom [])]
    (reg/install! (entry "last-first" log))
    (reg/install! (entry "last-second" log))
    ;; Dispatch through the SECOND spec, not the first.
    (dispatch/dispatch-for-spec "last-second" nil (object-array 0))
    (is (= ["last-first" "last-second"] @log)
        "which spec the advice looked up does not decide which bridges run")))

(deftest lookup-picks-the-first-but-the-walk-is-not-limited-to-it
  (let [log (atom [])]
    (reg/install! (entry "lookup-first" log))
    (reg/install! (entry "lookup-second" log))
    (is (= "lookup-first"
           (dispatch/lookup-spec-for-call target "length" 0 "()I" "entry"))
        "lookup-spec-for-call is first-match-wins")
    (is (= ["lookup-first" "lookup-second"] (mapv :id (reg/spec-bucket (reg/lookup "lookup-first"))))
        "the bucket it hands the walk holds both, which is why both run")))

(deftest positions-still-do-not-leak-into-each-other
  ;; fan-out is within one position; the position filter is what keeps a
  ;; :redefine bridge from being handed a 1-arg ctx
  (let [log (atom [])
        seen (atom [])]
    (reg/install! (assoc (entry "pos-entry" log) :position :entry))
    (reg/install! (assoc (entry "pos-throw" log)
                         :position :throw
                         :bridge (fn [_] (swap! seen conj :throw))))
    (reg/install! (assoc (entry "pos-return" log)
                         :position :return
                         :bridge (fn [_] (swap! seen conj :return) nil)))
    (dispatch/dispatch-for-spec "pos-entry" nil (object-array 0))
    (is (= ["pos-entry"] @log) "the :entry walk saw only the :entry spec")
    (is (= [] @seen) "no other position's bridge was called")))

(defn- entry-logging
  "An :entry hook that records its id in `log`, whatever its action. Kept
   separate from `entry` so an action-specific bridge cannot quietly stop
   recording -- the log is the measurement, not the side effect."
  [id log action]
  (assoc (entry id log)
         :action action
         :bridge (fn [ctx]
                   (swap! log conj id)
                   (when (#{:cancel :subscriber} action) ((:cancel! ctx) true))
                   nil)))

(deftest cancel-stops-the-rest-of-the-bucket
  ;; a :cancel short-circuits the remaining specs, so a hook installed after it
  ;; does not run -- the one place fan-out is NOT total
  (let [log (atom [])]
    (reg/install! (entry "cancel-first" log))
    (reg/install! (entry-logging "cancel-second" log :cancel))
    (reg/install! (entry "cancel-third" log))
    (is (= :nihilite/short-circuit
           (dispatch/dispatch-for-spec "cancel-first" nil (object-array 0))))
    (is (= ["cancel-first" "cancel-second"] @log)
        "the walk stopped at the cancel; nothing after it ran")
    (is (= 0 (fired "cancel-third"))
        "and the third hook's own counter stayed put, so this is observable")))

(deftest a-subscriber-does-NOT-stop-the-rest-of-the-bucket
  ;; :cancel returns the short-circuit sentinel, which ends the walk.
  ;; :subscriber cancels the EVENT (the host method's own result is
  ;; discarded) but the walk continues, so hooks after it still run. The two
  ;; look alike in the spec map and behave differently; nothing said so.
  (let [log (atom [])]
    (reg/install! (entry "sub-first" log))
    (reg/install! (entry-logging "sub-second" log :subscriber))
    (reg/install! (entry "sub-third" log))
    (is (nil? (dispatch/dispatch-for-spec "sub-first" nil (object-array 0)))
        "a subscriber produces no short-circuit, so the dispatch result is nil")
    (is (= ["sub-first" "sub-second" "sub-third"] @log)
        "the walk ran to the end of the bucket")
    (is (= 1 (fired "sub-third"))
        "the hook after the subscriber fired, unlike the hook after a cancel")))

(deftest uninstalling-one-leaves-the-others-running
  (let [log (atom [])]
    (reg/install! (entry "drop-one" log))
    (reg/install! (entry "drop-two" log))
    (is (true? (reg/uninstall! "drop-one")))
    (dispatch/dispatch-for-spec "drop-two" nil (object-array 0))
    (is (= ["drop-two"] @log)
        "the removed spec left the bucket, so only the survivor ran")
    (is (nil? (reg/lookup "drop-one")))))

(deftest a-modify-decides-the-return-value-and-the-rest-are-still-called
  ;; the first :modify that returns a value decides; specs after it in the
  ;; bucket do not run. Same short-circuit shape as :cancel, but decided by
  ;; :decided? rather than by a sentinel.
  (let [log (atom [])]
    (reg/install! (assoc (entry "mod-a" log)
                         :position :return
                         :action :modify
                         :bridge (fn [_] (swap! log conj "mod-a") 1)))
    (reg/install! (assoc (entry "mod-b" log)
                         :position :return
                         :action :modify
                         :bridge (fn [_] (swap! log conj "mod-b") 2)))
    (is (= 1 (dispatch/dispatch-return-for-spec "mod-a" nil (object-array 0) 99))
        "the first modify's value won")
    (is (= ["mod-a"] @log) "the second modify never ran")))
