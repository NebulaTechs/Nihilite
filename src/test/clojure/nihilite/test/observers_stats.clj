(ns nihilite.test.observers-stats
  "StatsIndex tests. Per D5.1-D5.4:
   - Per-spec stats live in parallel ConcurrentHashMap<spec-id, StatsRecord>
   - NOT on HookSpec (B3 fix)
   - hooks/status returns snapshot via defn (not live atom)
   - counters are swappable atoms"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.registry.stats :as stats]
            [nihilite.observers.status :as status]))

(defn- setup [f]
  (stats/clear!)
  (try (f) (finally (stats/clear!))))

(use-fixtures :each
  (fn [t] (setup t)))

(deftest ensure-stats-creates-record
  (testing "ensure-stats creates and returns a StatsRecord"
    (let [r (stats/ensure-stats "spec-1")]
      (is (instance? nihilite.registry.stats.StatsRecord r))
      (is (= 0 @(:fired r)))
      (is (= 0 @(:exceptions r))))))

(deftest get-stats-returns-existing
  (testing "ensure-stats is idempotent for a given id"
    (let [a (stats/ensure-stats "spec-2")
          b (stats/ensure-stats "spec-2")]
      (is (identical? a b)))))

(deftest remove-stats-on-uninstall
  (testing "remove-stats deletes the entry"
    (stats/ensure-stats "spec-3")
    (is (some? (stats/get-stats "spec-3")))
    (stats/remove-stats "spec-3")
    (is (nil? (stats/get-stats "spec-3")))))

(deftest dispatch-increments-fired
  (testing "registry/dispatch automatically bumps :fired"
    (reg/install! {:id "spec-4" :target-internal "x" :method-name "m"
                   :descriptor "()V" :position :entry :arity 0
                   :bridge (fn [_])})
    (reg/dispatch "spec-4" nil (object-array 0))
    (reg/dispatch "spec-4" nil (object-array 0))
    (let [r (stats/get-stats "spec-4")]
      (is (= 2 @(:fired r))))))

(deftest hooks-status-snapshot
  (testing "status returns a snapshot, not a live atom"
    (reg/install! {:id "spec-5" :target-internal "x" :method-name "m"
                   :descriptor "()V" :position :entry :arity 0
                   :bridge (fn [_])})
    (reg/dispatch "spec-5" nil (object-array 0))
    (let [s (status/hooks-status)
          found (some #(and (= "spec-5" (:id %))
                              (= 1 (:fired %)))
                       (:specs s))]
      (is (number? (:total-specs s)))
      (is (vector? (:specs s)))
      (is (some? found)))))
