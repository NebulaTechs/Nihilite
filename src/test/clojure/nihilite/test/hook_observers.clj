(ns nihilite.test.hook-observers
  "P0 hook-system observer-fan-out + :action + cancellation tests.

   All eight tests target a sentinel class-internal name so the
   bytecode transformer never sees them. Each test isolates
   `nihilite.registry/clear!` via a `use-fixtures :each` so
   state from a prior test cannot bleed across.

   The eight cases map directly to the plan §4.1 unit-test list:
     1. two-entry-observer-fanout-order
     2. observe-returns-nil-on-return-ignored
     3. modify-replaces-return
     4. first-modify-wins-subsequent-modify-ignored
     5. cancel-sets-flag-and-skips-remaining-observers
     6. observe-after-cancel-skipped
     7. cancel-on-return-position-rejected
     8. unknown-action-rejected"
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.registry :as reg]))

(def ^:private sentinel "nihilite.test.hook_observers_sent1n3l")
(def ^:private probe-method "p0Probe")

(use-fixtures :each
  (fn [test-fn]
    (reg/clear!)
    (try (test-fn)
      (finally (reg/clear!)))))

(defn- install-observer
  "Install a single spec at sentinel/probe-method/:entry/:return
   with the given `:action` and IFn."
  [id action ifn position]
  (reg/install!
    {:id              id
     :target-internal sentinel
     :method-name     probe-method
     :position        position
     :arity           0
     :descriptor      "()V"
     :action          action
     :bridge          ifn
     :note            (str "p0 observer test " id)}))

(defn- install-observer-no-descriptor
  "Install a single spec WITHOUT :descriptor (legacy fallback path)."
  [id action ifn position]
  (reg/install!
    {:id              id
     :target-internal sentinel
     :method-name     probe-method
     :position        position
     :arity           0
     :action          action
     :bridge          ifn
     :note            (str "legacy-path observer " id)}))

;; ---------------------------------------------------------------------------
;; 1. two-entry-observer-fanout-order
;; ---------------------------------------------------------------------------

(deftest two-entry-observer-fanout-order
  (let [order (atom [])
        fa (fn [_ev] (swap! order conj :a))
        fb (fn [_ev] (swap! order conj :b))]
    (install-observer "fanout-a" :observe fa :entry)
    (install-observer "fanout-b" :observe fb :entry)
    (reg/dispatch-for-spec "fanout-a" nil (object-array 0))
    (is (= [:a :b] @order)
        "both observers fired in registration order on one dispatch")))

;; ---------------------------------------------------------------------------
;; 2. observe-returns-nil-on-return-ignored
;; ---------------------------------------------------------------------------

(deftest observe-returns-nil-on-return-ignored
  (install-observer "obs-return"
                    :observe
                    (fn [_ev] "WRONG")
                    :return)
  (let [r (reg/dispatch-return-for-spec
            "obs-return" nil (object-array 0) 42)]
    (is (= 42 r)
        "an :observe handler returning a non-nil value at :return
         does NOT mutate the original return; the original 42 is
         returned to the host method")))

;; ---------------------------------------------------------------------------
;; 3. modify-replaces-return
;; ---------------------------------------------------------------------------

(deftest modify-replaces-return
  (install-observer "mod-return"
                    :modify
                    (fn [_ev] "MUTATED")
                    :return)
  (let [r (reg/dispatch-return-for-spec
            "mod-return" nil (object-array 0) "orig")]
    (is (= "MUTATED" r)
        ":modify handler returning non-nil replaces the original return")))

;; ---------------------------------------------------------------------------
;; 4. first-modify-wins-subsequent-modify-ignored
;; ---------------------------------------------------------------------------

(deftest first-modify-wins-subsequent-modify-ignored
  (install-observer "mod-first"
                    :modify
                    (fn [_ev] "A")
                    :return)
  (install-observer "mod-second"
                    :modify
                    (fn [_ev] "B")
                    :return)
  (let [r (reg/dispatch-return-for-spec
            "mod-first" nil (object-array 0) "orig")]
    (is (= "A" r)
        "the first :modify spec's return wins; subsequent :modify
         returns are ignored by the dispatcher")))

;; ---------------------------------------------------------------------------
;; 5. cancel-sets-flag-and-skips-remaining-observers
;; ---------------------------------------------------------------------------

(deftest cancel-sets-flag-and-skips-remaining-observers
  (let [cancel-count (atom 0)
        observer-count (atom 0)
        captured-event (atom nil)
        cancel-fn (fn [ev]
                    (swap! cancel-count inc)
                    (reset! captured-event ev)
                    ((:cancel! ev) true))
        observer-fn (fn [_ev] (swap! observer-count inc))]
    (install-observer "cancel-me" :cancel cancel-fn :entry)
    (install-observer "after-cancel" :observe observer-fn :entry)
    (reg/dispatch-for-spec "cancel-me" nil (object-array 0))
    (is (= 1 @cancel-count)
        ":cancel observer fired once")
    (is (= 0 @observer-count)
        ":observe observer registered AFTER :cancel was skipped")
    (is (true? ((:cancelled? @captured-event)))
        "the event's :cancelled? flag is true after the cancel")))

;; ---------------------------------------------------------------------------
;; 6. observe-after-cancel-skipped (inverse ordering)
;; ---------------------------------------------------------------------------

(deftest observe-after-cancel-skipped
  (let [cancel-count (atom 0)
        observer-count (atom 0)
        cancel-fn (fn [ev]
                    (swap! cancel-count inc)
                    ((:cancel! ev) true))
        observer-fn (fn [_ev] (swap! observer-count inc))]
    (install-observer "obs-first" :observe observer-fn :entry)
    (install-observer "cancel-second" :cancel cancel-fn :entry)
    (reg/dispatch-for-spec "obs-first" nil (object-array 0))
    (is (= 1 @observer-count)
        ":observe registered BEFORE :cancel still fires; cancellation
         has no retroactive effect on earlier observers")
    (is (= 1 @cancel-count)
        ":cancel registered AFTER :observe still fires and cancels
         further dispatches in this turn")))

;; ---------------------------------------------------------------------------
;; 7. cancel-on-return-position-rejected
;; ---------------------------------------------------------------------------

(deftest cancel-on-return-position-rejected
  (let [thrown (atom nil)]
    (try
      (install-observer "bad-cancel" :cancel (fn [_ev]) :return)
      (catch Throwable t
        (reset! thrown t)))
    (is (some? @thrown)
        ":action :cancel with :position :return is rejected by install!")
    (is (= :nihilite/cancel-requires-entry
           (:nihilite/kind (ex-data @thrown)))
        "ex-info carries :nihilite/kind :nihilite/cancel-requires-entry")))

;; ---------------------------------------------------------------------------
;; 8. unknown-action-rejected
;; ---------------------------------------------------------------------------

(deftest unknown-action-rejected
  (let [thrown (atom nil)]
    (try
      (install-observer "bad-action" :nonsense (fn [_ev]) :entry)
      (catch Throwable t
        (reset! thrown t)))
    (is (some? @thrown)
        "an unknown :action value is rejected by install!")
    (is (= :nihilite/invalid-action
           (:nihilite/kind (ex-data @thrown)))
        "ex-info carries :nihilite/kind :nihilite/invalid-action")))

;; ---------------------------------------------------------------------------
;; 9. missing-descriptor-throws (commit 2 hardens the warn to throw)
;; ---------------------------------------------------------------------------

(deftest missing-descriptor-throws
  (let [thrown (atom nil)]
    (try
      (install-observer-no-descriptor
        "no-desc" :observe (fn [_ev]) :entry)
      (catch Throwable t
        (reset! thrown t)))
    (is (some? @thrown)
        "install! without :descriptor throws")
    (is (= :nihilite/missing-descriptor
           (:nihilite/kind (ex-data @thrown)))
        "ex-info carries :nihilite/kind :nihilite/missing-descriptor")))