(ns nihilite.test.observers-sinks
  "Sink tests. Per D4.1–D4.4:
   - Sinks are 1-arg IFns registered by name
   - 4 built-in sinks: :println, :ring-buffer, :aggregate, :fn-sink
   - Sink errors caught and counted inside subscriber bridge"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nihilite.observers.sinks :as sinks]))

(defn- setup [f] (sinks/clear!) (try (f) (finally (sinks/clear!))))

(use-fixtures :each (fn [t] (setup t)))

(deftest println-sink-exists
  (testing ":println sink is registered"
    (is (some? (sinks/lookup :println)))))

(deftest ring-buffer-sink-appends
  (testing ":ring-buffer sink appends to an atom queue"
    (let [buf (sinks/ring-buffer)
          ev {:id "x"}
          _ (sinks/dispatch! :ring-buffer ev)
          _ (sinks/dispatch! :ring-buffer ev)
          _ (sinks/dispatch! :ring-buffer ev)]
      (is (= 3 (count (sinks/take-events buf 10)))))))

(deftest aggregate-sink-counts
  (testing ":aggregate sink counts per (class, method, phase)"
    (sinks/dispatch! :aggregate
                     {:source {:class "Foo" :method "bar"} :phase :entry})
    (sinks/dispatch! :aggregate
                     {:source {:class "Foo" :method "bar"} :phase :entry})
    (sinks/dispatch! :aggregate
                     {:source {:class "Foo" :method "bar"} :phase :return})
    (let [agg @sinks/aggregate-sink]
      (is (= 2 (get-in agg [:by-class-method "Foo/bar" :entry])))
      (is (= 1 (get-in agg [:by-class-method "Foo/bar" :return]))))))

(deftest fn-sink-registered-by-handler
  (testing "users can register a custom sink via sinks/register-sink!"
    (let [captured (atom [])]
      (sinks/register-sink! :my-sink (fn [ev] (swap! captured conj ev)))
      (sinks/dispatch! :my-sink {:id "custom"})
      (is (= [{:id "custom"}] @captured)))))

(deftest sink-error-propagates-from-raw-dispatch
  (testing "a raw throwing sink propagates from dispatch! (the per-sink
            try/catch lives in the subscriber bridge, D4.3, not in
            dispatch!). Subscriber-level catch+count is covered by
            observers-subscriber / observers-stats."
    (sinks/register-sink! :throwing-sink
                          (fn [_] (throw (ex-info "boom" {}))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (sinks/dispatch! :throwing-sink {:id "y"})))))
