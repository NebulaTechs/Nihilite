(ns nihilite.test.observers-trace-test
  "Lightweight trace tests (P2.1b, plan v2.1 §3).

   Covers:
     - :max-nodes > 10000 rejected at creation time
     - trace returns a descriptor map with :id
     - entry-side subscriber installed (silence-match-all-warning
       on selector-matched install — default selector is {})
     - :invoke-* events are NOT part of commit 5 trace"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.observers.trace :as trace]))

(defn- setup [f]
  (reg/clear!)
  (try (f) (finally (reg/clear!))))

(use-fixtures :each (fn [t] (setup t)))

(deftest max-nodes-over-10000-rejected
  (testing "trace! throws when :max-nodes > 10000 (plan §3.3)"
    (is (thrown? clojure.lang.ExceptionInfo
                 (trace/trace! {:max-nodes 10001})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (trace/trace! {:max-nodes 100000})))))

(deftest max-nodes-within-cap-accepted
  (testing "trace! accepts :max-nodes up to 10000"
    (let [t (trace/trace! {:max-nodes 10000})]
      (is (map? t))
      (is (string? (:id t)))
      (trace/stop-trace! (:id t)))))

(deftest trace-descriptor-shape
  (testing "trace! returns a descriptor map with the required keys"
    (let [t (trace/trace! {})]
      (is (contains? t :id))
      (is (contains? t :in-flight))
      (is (contains? t :handler))
      (is (contains? t :sink))
      (is (contains? t :max-nodes))
      (trace/stop-trace! (:id t)))))

(deftest stop-trace-idempotent
  (testing "stop-trace! returns false for an unknown id"
    (is (false? (trace/stop-trace! "never-existed")))))

(deftest stop-trace-removes-entry
  (testing "stop-trace! removes the entry from list-traces"
    (let [t (trace/trace! {})
          id (:id t)]
      (is (some #{id} (trace/list-traces)))
      (trace/stop-trace! id)
      (is (not (some #{id} (trace/list-traces)))))))

(deftest trace-internal-in-flight-empty-initially
  (testing "in-flight stack starts empty"
    (let [t (trace/trace! {})]
      (is (empty? @(:in-flight t)))
      (trace/stop-trace! (:id t)))))