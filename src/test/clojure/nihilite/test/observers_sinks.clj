(ns nihilite.test.observers-sinks
  "Sink tests. Per D4.1–D4.4:
   - Sinks are 1-arg IFns registered by name
   - 4 built-in sinks: :println, :ring-buffer, :aggregate, :fn-sink
   - Sink errors caught and counted inside subscriber bridge"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nihilite.observers.sinks :as sinks])
  (:import [java.util.concurrent.atomic AtomicInteger]))

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

;; --- drain-events API + concurrency test ---

(deftest drain-events-empty-when-empty
  (testing "drain-events on an empty buffer returns []"
    (let [buf (sinks/ring-buffer)
          drained (sinks/drain-events buf 100)]
      (is (= [] drained)))))

(deftest drain-events-fifo-order
  (testing "drain-events returns events in FIFO order"
    (let [buf (sinks/ring-buffer)]
      (sinks/dispatch! :ring-buffer {:id "a"})
      (sinks/dispatch! :ring-buffer {:id "b"})
      (sinks/dispatch! :ring-buffer {:id "c"})
      (is (= [{:id "a"} {:id "b"} {:id "c"}]
             (sinks/drain-events buf 10))))))

(deftest drain-events-zero-or-negative
  (testing "drain-events with n<=0 returns empty WITHOUT touching state"
    (let [buf (sinks/ring-buffer)]
      (sinks/dispatch! :ring-buffer {:id "x"})
      (sinks/dispatch! :ring-buffer {:id "y"})
      (is (= [] (sinks/drain-events buf 0)))
      (is (= [] (sinks/drain-events buf -1)))
      ;; events still queued (state untouched)
      (is (= 2 (count (sinks/drain-events buf 10)))))))

(deftest drain-events-concurrent-no-loss
  (testing "concurrent emit (swap! conj) + drain (swap-vals!) never
            loses or double-emits events under a small contention
            bound (10 emits × 10 drains with n=2)."
    ;; Sequential first to confirm semantics work in isolation.
    (let [buf (sinks/ring-buffer)
          _   (dotimes [n 10] (sinks/dispatch! :ring-buffer {:id (str n)}))
          seq-test (count (sinks/drain-events buf 10000))]
      (is (= 10 seq-test)
          "sequential emit + drain returns exactly the emit count"))

    ;; Now concurrent — small contention to bound timing noise.
    (sinks/clear!)
    (let [buf         (sinks/ring-buffer)
          emit-count  (AtomicInteger.)
          drain-count (AtomicInteger.)
          emitter     (future
                       (dotimes [_ 10]
                         (sinks/dispatch! :ring-buffer
                                           {:id (str (.incrementAndGet emit-count))})))
          drainer     (future
                       (dotimes [_ 10]
                         (let [drained (sinks/drain-events buf 2)]
                           (.addAndGet drain-count (count drained)))))
          _           (.get emitter 30000 java.util.concurrent.TimeUnit/MILLISECONDS)
          _           (.get drainer 30000 java.util.concurrent.TimeUnit/MILLISECONDS)
          tail        (sinks/drain-events buf 10000)]
      (.addAndGet drain-count (count tail))
      (is (= 10 (.get emit-count))
          "emitter must emit exactly 10 events")
      (is (= 10 (.get drain-count))
          "drainer must drain exactly 10 events (no loss, no double-drain)"))))
