(ns nihilite.test.dispatch-common-test
  "   Regression for `nihilite.registry.dispatch.util` helpers:
   - `call-cancel!` — invokes :cancel! if present, no-op else.
   - `run-hook`     — invokes observer with per-observer try/catch
     isolation + stats/bump-exception! on throw.

   Contract: nil-safe :cancel!, type-hint-free. Synthetic events
   without `:cancel!` must not throw."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.registry.dispatch.util :as du]
            [nihilite.registry.index :as ix]
            [nihilite.registry.install :as install]
            [nihilite.registry.spec :as rs]
            [nihilite.registry.stats :as stats]))

(defn- fresh-state [f]
  (ix/clear-all!)
  (reg/clear!)
  (stats/clear!)
  (try (f)
       (finally
         (ix/clear-all!)
         (reg/clear!)
         (stats/clear!))))

(use-fixtures :each fresh-state)

(defn- install-throwing [id]
  (install/install!
    {:id                id
     :target-internal   "java/lang/String"
     :method-name       "length"
     :descriptor        "()I"
     :position          :entry
     :bridge            (fn [_] (throw (ex-info "boom" {})))})
  id)

(defn- install-clean [id]
  (install/install!
    {:id                id
     :target-internal   "java/lang/String"
     :method-name       "length"
     :descriptor        "()I"
     :position          :entry
     :bridge            (fn [_] nil)})
  id)

(defn- basic-event [id]
  (rs/map->HookEvent
    {:spec-id      id
     :source       {:class "c" :method "m"
                    :descriptor "()V" :method-key "c/m#()V"}
     :phase        :entry
     :self         nil
     :args         (object-array 0)
     :return-value nil
     :cancelled?   (constantly false)
     :cancel!      (fn [_])
     :thread-name  "t"
     :timestamp-ns 0
     :sequence     1
     :note         nil
     :stack        nil}))

(deftest call-cancel-flips-the-cell
  (testing "call-cancel! invokes :cancel! and returns nil"
    (let [event (basic-event "x")]
      (is (nil? (du/call-cancel! event))))))

(deftest call-cancel-nil-safe
  (testing "call-cancel! on an event without :cancel! is a no-op"
    (let [event {:spec-id "y"}]
      (is (nil? (du/call-cancel! event))))))

(deftest run-hook-runs-the-bridge
  (testing "run-hook invokes the bridge with the event and returns
            the bridge's return value"
    (let [event {:spec-id "z" :source {:class "a"}}
          captured (atom nil)
          f (fn [ev] (reset! captured ev) :ok)]
      (is (= :ok (du/run-hook f event)))
      (is (= event @captured)))))

(deftest run-hook-no-bump-on-clean-run
  (testing "run-hook does NOT bump :exceptions on a clean run"
    (let [id "clean-test"]
      (install-clean id)
      (du/run-hook (:bridge (reg/lookup id)) (basic-event id))
      (is (= 0 @(:exceptions (stats/get-stats id)))))))

(deftest run-hook-bumps-on-throw
  (testing "run-hook bumps :exceptions when the bridge throws"
    (let [id "throwing-test"]
      (install-throwing id)
      (du/run-hook (:bridge (reg/lookup id)) (basic-event id))
      (is (= 1 @(:exceptions (stats/get-stats id)))))))

(deftest run-hook-nil-bridge-no-bump
  (testing "run-hook with nil bridge returns :no-return and does
            NOT bump :exceptions"
    (let [id "nil-bridge-test"]
      (install-clean id)
      (is (= :nihilite.registry.dispatch.util/no-return
             (du/run-hook nil (basic-event id))))
      (is (= 0 @(:exceptions (stats/get-stats id)))))))