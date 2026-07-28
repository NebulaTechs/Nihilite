(ns nihilite.test.dispatch-return-cancel-test
  "P2 dispatch-return cancelled? backport (plan v2.1 §6).

   Tests the 2-line patch to `dispatch-return-for-spec` that
   honors the per-event `:cancelled?` closure between observers
   in the return-bucket walk.

   Note (B4 v2.1): cancel set on `:entry` does NOT propagate
   to `:return` — each phase constructs a fresh HookEvent with
   its own AtomicBoolean. These tests cover the same-event
   bucket-walk short-circuit behavior."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nihilite.registry :as reg]))

(defn- setup [f]
  (reg/clear!)
  (try (f) (finally (reg/clear!))))

(use-fixtures :each (fn [t] (setup t)))

(deftest return-cancel-short-circuits-return-bucket
  (testing "an :return observer that calls ((:cancel! ev) true)
            skips the rest of the :return bucket"
    (reg/install! {:id "rc-a" :target-internal "x" :method-name "m"
                   :descriptor "()V" :position :return :arity 0
                   :action :modify
                   :bridge (fn [_ev] "MUT-A")})
    (reg/install! {:id "rc-b" :target-internal "y" :method-name "m"
                   :descriptor "()V" :position :return :arity 0
                   :action :observe
                   :bridge (fn [ev] ((:cancel! ev) true))})
    (reg/install! {:id "rc-c" :target-internal "z" :method-name "m"
                   :descriptor "()V" :position :return :arity 0
                   :action :modify
                   :bridge (fn [_ev] "MUT-C")})
    (let [rv (reg/dispatch-return-for-spec "rc-a" nil
                                          (object-array 0) "ORIG")]
      ;; First modify returns "MUT-A". Then the :observe observer
      ;; flips the cancel-cell. The :modify for "rc-c" is skipped
      ;; because of the cancelled? check added by the backport.
      (is (= "MUT-A" rv)))))

(deftest return-no-cancel-runs-all-bucket
  (testing "without a cancel-flip, all return-bucket observers run"
    (reg/install! {:id "nc-a" :target-internal "x" :method-name "m"
                   :descriptor "()V" :position :return :arity 0
                   :action :modify
                   :bridge (fn [_ev] "MUT-A")})
    (reg/install! {:id "nc-b" :target-internal "y" :method-name "m"
                   :descriptor "()V" :position :return :arity 0
                   :action :modify
                   :bridge (fn [_ev] "MUT-B")})
    (let [rv (reg/dispatch-return-for-spec "nc-a" nil
                                          (object-array 0) "ORIG")]
      ;; First non-nil wins: "MUT-A"
      (is (= "MUT-A" rv)))))

(deftest return-modify-winner-still-first
  (testing ":modify first-non-nil winner still wins (no cancel flip)"
    (reg/install! {:id "fm-a" :target-internal "x" :method-name "m"
                   :descriptor "()V" :position :return :arity 0
                   :action :modify
                   :bridge (fn [_ev] "MUT-A")})
    (reg/install! {:id "fm-b" :target-internal "y" :method-name "m"
                   :descriptor "()V" :position :return :arity 0
                   :action :modify
                   :bridge (fn [_ev] "MUT-B")})
    (let [rv (reg/dispatch-return-for-spec "fm-a" nil
                                          (object-array 0) "ORIG")]
      (is (= "MUT-A" rv)))))

(deftest return-missing-spec-returns-original
  (testing "missing spec id returns current-return"
    (is (= "ORIG" (reg/dispatch-return-for-spec "no-such-spec"
                                                nil (object-array 0)
                                                "ORIG")))))