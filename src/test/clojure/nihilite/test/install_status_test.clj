(ns nihilite.test.install-status-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.api :as api]
            [nihilite.registry :as reg]
            [nihilite.registry.dispatch :as dispatch]
            [nihilite.test.fixtures :as fx]))

(defn- entry-spec [id]
  {:id id
   :target-internal "java/lang/String"
   :method-name "length"
   :descriptor "()I"
   :position :entry
   :action :observe
   :bridge (fn [_] nil)})

(use-fixtures :each fx/reg-cleanup)

(deftest status-unknown-id
  (let [s (reg/install-status! "never-installed")]
    (is (= "never-installed" (:spec-id s)))
    (is (false? (:registered? s)))
    (is (zero? (:woven-count s)))
    (is (false? (:pending? s)))
    (is (nil? (:last-error s)))))

(deftest status-after-install-without-agent
  (api/install! (entry-spec "status-test"))
  (let [s (reg/install-status! "status-test")]
    (is (true? (:registered? s)))
    (is (zero? (:woven-count s)))
    (is (true? (:pending? s)))
    (is (nil? (:last-error s)))))

(deftest status-after-uninstall-without-agent
  (api/install! (entry-spec "status-uninst"))
  (api/uninstall! "status-uninst")
  (let [s (reg/install-status! "status-uninst")]
    (is (false? (:registered? s)))
    (is (zero? (:woven-count s)))
    (is (false? (:pending? s)))
    (is (nil? (:last-error s)))))

(deftest status-after-replace
  (api/install! (entry-spec "status-replace"))
  (api/install! (assoc (entry-spec "status-replace") :note "second"))
  (let [s (reg/install-status! "status-replace")]
    (is (true? (:registered? s)))
    (is (zero? (:woven-count s)))))

(deftest status-reports-runtime-counters
  (api/install! (entry-spec "status-runtime"))
  (let [s (reg/install-status! "status-runtime")]
    (is (contains? s :fired) "install-status! must report :fired, not just install-side state")
    (is (contains? s :modified))
    (is (contains? s :cancelled))
    (is (contains? s :exceptions))
    (is (zero? (:fired s)))))

(deftest status-unknown-id-reports-zero-counters
  (let [s (reg/install-status! "never-installed")]
    (is (zero? (:fired s)))
    (is (zero? (:modified s)))
    (is (zero? (:cancelled s)))
    (is (zero? (:exceptions s)))))

(deftest status-fired-tracks-real-dispatch
  (api/install! (entry-spec "status-fired"))
  (is (zero? (:fired (reg/install-status! "status-fired"))))
  (dispatch/dispatch-for-spec "status-fired" nil (object-array 0))
  (dispatch/dispatch-for-spec "status-fired" nil (object-array 0))
  (is (= 2 (:fired (reg/install-status! "status-fired")))))

(defn- cancelling-spec [id]
  (assoc (entry-spec id)
         :action :cancel
         :bridge (fn [ctx] ((:cancel! ctx) true) nil)))

(deftest status-cancelled-tracks-real-cancels
  ;; :cancelled was reported by install-status! and documented as "how many
  ;; times the advice short-circuited", but nothing ever incremented it: no
  ;; bump-cancelled! existed, so the field was structurally 0. The two tests
  ;; above could not catch that -- one asserts the key is present, the other
  ;; that it is zero for an id that was never installed, and a counter pinned
  ;; at zero passes both. A cancel that leaves :cancelled at 0 is also
  ;; indistinguishable from a :cancel hook that never ran.
  (api/install! (cancelling-spec "status-cancelled"))
  (is (zero? (:cancelled (reg/install-status! "status-cancelled"))))
  (dispatch/dispatch-for-spec "status-cancelled" nil (object-array 0))
  (is (= 1 (:cancelled (reg/install-status! "status-cancelled")))
      "a :cancel hook that fired must move :cancelled off zero")
  (dispatch/dispatch-for-spec "status-cancelled" nil (object-array 0))
  (is (= 2 (:cancelled (reg/install-status! "status-cancelled")))))

(deftest status-cancelled-counts-a-subscriber-too
  ;; A subscriber cancels the event without short-circuiting the walk, so it
  ;; is a different outcome from :cancel -- but it IS a cancellation, and the
  ;; counter is the only place that shows one happened at all.
  (api/install! (assoc (entry-spec "status-subscriber")
                       :action :subscriber
                       :bridge (fn [ctx] ((:cancel! ctx) true) nil)))
  (dispatch/dispatch-for-spec "status-subscriber" nil (object-array 0))
  (is (= 1 (:cancelled (reg/install-status! "status-subscriber")))))

(deftest status-cancelled-stays-zero-for-an-observer
  ;; The other half of the cancel tests: :fired moves but :cancelled must not,
  ;; or the counter would just be tracking fires under a second name.
  (api/install! (entry-spec "status-observer"))
  (dispatch/dispatch-for-spec "status-observer" nil (object-array 0))
  (let [s (reg/install-status! "status-observer")]
    (is (= 1 (:fired s)))
    (is (zero? (:cancelled s)))))

(defn- modifying-spec
  "A :return :modify spec on String.length, whose bridge replaces the value.
   The descriptor is ()I so the replacement has to narrow to int."
  [id]
  {:id id
   :target-internal "java/lang/String"
   :method-name "length"
   :descriptor "()I"
   :position :return
   :action :modify
   :bridge (fn [_] 42)})

(defn- throwing-spec [id]
  (assoc (entry-spec id)
         :bridge (fn [_] (throw (ex-info "bridge boom" {})))))

(deftest status-modified-tracks-real-modifies
  ;; :modified stood where :cancelled did. install-status! reported it and
  ;; registry/install-status!'s docstring counted it as the number of times a
  ;; :modify changed a return value, but this namespace only ever asserted
  ;; (contains? s :modified) -- which a counter pinned at zero passes. The
  ;; :cancelled tests above exist because that is exactly how it hid; the
  ;; same argument applies here, and :modified did not get them.
  (api/install! (modifying-spec "status-modified"))
  (is (zero? (:modified (reg/install-status! "status-modified"))))
  (is (= 42 (dispatch/dispatch-return-for-spec "status-modified"
                                              nil (object-array 0) 0))
      "the :modify value replaced the target's return value")
  (is (= 1 (:modified (reg/install-status! "status-modified"))))
  (dispatch/dispatch-return-for-spec "status-modified" nil (object-array 0) 0)
  (is (= 2 (:modified (reg/install-status! "status-modified")))))

(deftest status-modified-stays-zero-for-an-observer
  (api/install! (entry-spec "status-not-modified"))
  (dispatch/dispatch-for-spec "status-not-modified" nil (object-array 0))
  (let [s (reg/install-status! "status-not-modified")]
    (is (= 1 (:fired s)) "the bridge ran")
    (is (zero? (:modified s)) "but nothing was replaced, so nothing counted")))

(deftest status-exceptions-tracks-real-throws
  ;; (contains? s :exceptions) is satisfied by a hardcoded 0 just as easily.
  ;;
  ;; The bridge's exception does NOT reach the caller: dispatch-one! catches
  ;; it, logs at ERROR, counts it and returns ::no-return, so one broken
  ;; observer cannot take down the method being observed. That is a deliberate
  ;; choice, and this pins both halves of it -- the count moves, and the call
  ;; returns normally. An earlier version of this test asserted the exception
  ;; propagated and failed, which is what surfaced the swallowing.
  (api/install! (throwing-spec "status-throws"))
  (is (zero? (:exceptions (reg/install-status! "status-throws"))))
  (is (nil? (dispatch/dispatch-for-spec "status-throws" nil (object-array 0)))
      "a throwing observer is contained, not propagated")
  (is (= 1 (:exceptions (reg/install-status! "status-throws"))))
  (is (nil? (dispatch/dispatch-for-spec "status-throws" nil (object-array 0)))
      "still contained on the second call")
  (is (= 2 (:exceptions (reg/install-status! "status-throws")))
      "every throw counts"))

(deftest status-exceptions-stays-zero-for-a-clean-run
  (api/install! (entry-spec "status-clean"))
  (dispatch/dispatch-for-spec "status-clean" nil (object-array 0))
  (let [s (reg/install-status! "status-clean")]
    (is (= 1 (:fired s)))
    (is (zero? (:exceptions s))
        "a bridge that returns normally is not an exception")))
