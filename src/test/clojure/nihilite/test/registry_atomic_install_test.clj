(ns nihilite.test.registry-atomic-install-test
  "Race regression: install!/uninstall! triple-write must be atomic.
   Concurrent writers against the same (target, method, descriptor) bucket
   must never let an observer see a bucket holding more than one spec
   for the same id, nor a by-id/bucket drift."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.test.fixtures :as fx])
  (:import [java.util.concurrent Callable CountDownLatch ExecutorService
                                    Executors TimeUnit]))

(def ^:private thread-count 4)
(def ^:private target-internal "java/lang/String")
(def ^:private method-name "length")
(def ^:private descriptor "()I")

(use-fixtures :each fx/reg-cleanup)

(defn- race-spec
  [id seed]
  {:id              id
   :target-internal target-internal
   :source-class    "java/lang/Object"
   :method-name     method-name
   :descriptor      descriptor
   :position        :entry
   :action          :observe
   :bridge          (fn [_] seed)
   :note            (str "seed=" seed)
   :tag             (str "t-" seed)})

(defn- await-all
  "Submit all callables via submit (returns Futures, does not block),
   release the start latch so they all run in parallel, then wait for
   completion with timeout."
  [^ExecutorService pool ^CountDownLatch start tasks]
  (binding [*out* *err*]
    (println "[await-all] submitting" (count tasks) "tasks")
    (flush))
  (let [_futures (mapv (fn [^Callable c] (.submit pool c)) tasks)]
    (binding [*out* *err*]
      (println "[await-all] countDown start")
      (flush))
    (.countDown start)
    (binding [*out* *err*]
      (println "[await-all] shutdown")
      (flush))
    (.shutdown pool)
    (binding [*out* *err*]
      (println "[await-all] awaitTermination 30s")
      (flush))
    (.awaitTermination pool 30 TimeUnit/SECONDS)))

(defn- ->callable
  "Wrap a thunk in a Callable so ExecutorService.invokeAll accepts it."
  [f]
  (reify Callable (call [_] (f))))

(deftest concurrent-install-same-id-keeps-bucket-size-one
  (binding [*out* *err*]
    (println "[test-1] entering")
    (flush))
  (let [pool (Executors/newFixedThreadPool thread-count)
        start (CountDownLatch. 1)
        barrier (CountDownLatch. thread-count)
        tasks (mapv (fn [i]
                      (->callable
                        (fn []
                          (binding [*out* *err*]
                            (println "[test-1] thread" i "starting")
                            (flush))
                          (.countDown barrier)
                          (binding [*out* *err*]
                            (println "[test-1] thread" i "awaiting start")
                            (flush))
                          (.await start)
                          (binding [*out* *err*]
                            (println "[test-1] install" i)
                            (flush))
                          (reg/install! (race-spec "race-replace" i)))))
                    (range thread-count))]
    (binding [*out* *err*]
      (println "[test-1] await-all start")
      (flush))
    (await-all pool start tasks)
    (binding [*out* *err*]
      (println "[test-1] await-all done")
      (flush))
    (let [final-id (reg/lookup "race-replace")
          bucket (reg/matching target-internal)
          ours (filter #(= "race-replace" (:id %)) bucket)]
      (is (some? final-id) "by-id has the surviving spec")
      (is (= 1 (count ours)) "exactly one spec with that id in by-target bucket")
      (is (= 1 (count (reg/matching target-internal)))
          "by-target bucket holds at most one spec (other tests share target)"))))

(deftest concurrent-install-final-state-matches-a-writer
  (let [pool (Executors/newFixedThreadPool thread-count)
        start (CountDownLatch. 1)
        barrier (CountDownLatch. thread-count)
        seeds (atom #{})
        tasks (mapv (fn [i]
                      (->callable
                        (fn []
                          (.countDown barrier)
                          (.await start)
                          (swap! seeds conj i)
                          (reg/install! (race-spec "race-final" i)))))
                    (range thread-count))]
    (.await barrier)
    (await-all pool start tasks)
    (let [final-spec (reg/lookup "race-final")
          seed-in-final (some-> final-spec :tag (subs 2) Integer/parseInt)
          bucket-seeds (->> (reg/matching target-internal)
                            (filter #(= "race-final" (:id %)))
                            (map #(some-> % :tag (subs 2) Integer/parseInt))
                            set)]
      (is (contains? @seeds seed-in-final)
          "final state is exactly one of the seeds that was written")
      (is (= #{seed-in-final} bucket-seeds)
          "bucket seed set matches by-id — no drift"))))

(deftest concurrent-install-and-uninstall-bucket-invariants
  (let [pool (Executors/newFixedThreadPool (* 2 thread-count))
        start (CountDownLatch. 1)
        barrier (CountDownLatch. (* 2 thread-count))
        tasks (mapv (fn [i]
                      (->callable
                        (fn []
                          (.countDown barrier)
                          (.await start)
                          (if (zero? (mod i 2))
                            (reg/install! (race-spec "race-mix" (quot i 2)))
                            (reg/uninstall! "race-mix")))))
                    (range (* 2 thread-count)))]
    (.await barrier)
    (await-all pool start tasks)
    (let [in-id? (some? (reg/lookup "race-mix"))
          ours (filter #(= "race-mix" (:id %)) (reg/matching target-internal))]
      (is (= (if in-id? 1 0) (count ours))
          "by-id presence and by-target bucket count agree"))))

(deftest concurrent-uninstall-bucket-drains-completely
  (reg/install! (race-spec "race-uninstall" 0))
  (let [pool (Executors/newFixedThreadPool thread-count)
        start (CountDownLatch. 1)
        barrier (CountDownLatch. thread-count)
        tasks (mapv (fn [_]
                      (->callable
                        (fn []
                          (.countDown barrier)
                          (.await start)
                          (reg/uninstall! "race-uninstall"))))
                    (range thread-count))]
    (.await barrier)
    (await-all pool start tasks)
    (is (nil? (reg/lookup "race-uninstall")) "by-id empty")
    (let [ours (filter #(= "race-uninstall" (:id %)) (reg/matching target-internal))]
      (is (zero? (count ours)) "by-target bucket empty for the id"))))

(deftest observer-never-sees-bucket-with-stale-and-fresh
  (let [pool (Executors/newFixedThreadPool thread-count)
        start (CountDownLatch. 1)
        barrier (CountDownLatch. thread-count)
        max-seen (atom 0)
        tasks (mapv (fn [i]
                      (->callable
                        (fn []
                          (.countDown barrier)
                          (.await start)
                          (reg/install! (race-spec "race-observer" i)))))
                    (range thread-count))]
    (.await barrier)
    (let [watchdog (Thread.
                     ^Runnable
                     (fn []
                       (let [deadline (+ (System/nanoTime)
                                         (* 5 1000 1000 1000))]
                         (while (< (System/nanoTime) deadline)
                           (let [n (count (filter #(= "race-observer" (:id %))
                                                  (reg/matching target-internal)))]
                             (when (> n @max-seen)
                               (reset! max-seen n)))))))]
      (.start watchdog)
      (await-all pool start tasks)
      (.join watchdog 1000))
    (is (<= @max-seen 1)
        (str "observer never saw >1 spec for race-observer in target bucket (max="
             @max-seen ")"))))