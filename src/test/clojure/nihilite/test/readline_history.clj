(ns nihilite.test.readline-history
  "Unit tests for `nihilite.readline.history` — shared server-wide
   history deque.

   Each `deftest` resets the shared atom via `history-reset!` in its
   `:before` clause so tests are order-independent. A `:once`
   fixture would also work but `:each` keeps a crashed earlier test
   from poisoning later ones."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.readline.history :as h]))

;; fixtures

(defn- reset-history!
  []
  (h/history-reset!))

;; use-fixtures wraps each test; fixture must invoke test-fn to actually run.
(use-fixtures :each
  (fn [run-tests]
    (reset-history!)
    (run-tests)))

;; basic add / read

(deftest add-one-entry-then-read-entries
  (h/history-add! "first")
  (is (= ["first"] (h/history-entries))
      "1 entry added → 1-element vec snapshot"))

(deftest add-five-distinct-entries-are-oldest-first
  (doseq [e ["a" "b" "c" "d" "e"]]
    (h/history-add! e))
  (is (= ["a" "b" "c" "d" "e"] (h/history-entries))
      "5 distinct entries → oldest-first vec, all 5 present"))

;; consecutive dedup

(deftest consecutive-duplicate-is-noop
  (h/history-add! "foo")
  (h/history-add! "foo")
  (is (= ["foo"] (h/history-entries))
      "consecutive dedup: second add of same entry → single element"))

(deftest non-consecutive-duplicate-is-kept
  ;; Dedup is strictly CONSECUTIVE; foo/bar/foo keeps both foo's.
  (h/history-add! "foo")
  (h/history-add! "bar")
  (h/history-add! "foo")
  (is (= ["foo" "bar" "foo"] (h/history-entries))
      "non-consecutive duplicates both retained"))

;; cap-at-1000 (FIFO drop oldest)

(deftest cap-at-thousand-drops-oldest
  (let [n 1001]
    (doseq [i (range n)]
      (h/history-add! (str "entry-" i)))
    (let [es (h/history-entries)]
      (is (= 1000 (count es))
          "exactly 1000 entries after adding 1001")
      (is (not (some #(= "entry-0" %) es))
          "oldest (entry-0) was dropped")
      (is (some #(= "entry-1000" %) es)
          "newest (entry-1000) is present")
      ;; Oldest-first: first=entry-1 (second added), last=entry-1000.
      (is (= "entry-1" (first es)))
      (is (= "entry-1000" (last es))))))

;; find-prefix

(deftest find-prefix-returns-matches-in-order
  (doseq [e ["foo" "bar" "foobar" "qux"]]
    (h/history-add! e))
  (is (= ["foo" "foobar"] (h/history-find-prefix "fo"))
      "prefix 'fo' → foo + foobar, oldest-first")
  (is (= ["foo" "bar" "foobar" "qux"] (h/history-find-prefix ""))
      "empty prefix → every entry, oldest-first")
  (is (= [] (h/history-find-prefix "zzz"))
      "no match → empty vec")
  ;; case-sensitive — uppercase prefix does NOT match lowercase entries.
  (is (= [] (h/history-find-prefix "FO"))
      "case-sensitive: uppercase 'FO' matches nothing")
  ;; prefix-match (substring-from-0): 'foo' also matches 'foobar'.
  (is (= ["foo" "foobar"] (h/history-find-prefix "foo"))
      "prefix-match (substring-from-0): 'foo' also matches 'foobar'"))

;; concurrency

(deftest concurrent-add-stays-within-cap-and-loses-no-fresh-entries
  ;; 10 threads × 100 entries = 1000 adds; each thread's slice is unique.
  ;; Asserts: (1) count ≤ 1000 (cap respected), (2) all 1000 distinct strings survive.
  (let [n-threads 10
        per-thread 100
        total (* n-threads per-thread)
        expected (set (for [t (range n-threads)
                            m (range per-thread)]
                        (str "t" t "-e" m)))
        ;; Latches so all threads start hammering the atom at the same instant.
        start-gate (java.util.concurrent.CountDownLatch. 1)
        done-gate  (java.util.concurrent.CountDownLatch. n-threads)
        futures    (doall
                     (for [t (range n-threads)]
                       (future
                         (try
                           (.await start-gate)
                           (dotimes [m per-thread]
                             (h/history-add! (str "t" t "-e" m)))
                           (catch Throwable t
                             (.printStackTrace t))
                           (finally
                             (.countDown done-gate))))))]
    (.countDown start-gate)
    ;; Bounded wait — should complete in well under 10s.
    (is (.await done-gate 10 java.util.concurrent.TimeUnit/SECONDS)
        "all 10 threads completed within 10s")
    (doseq [f futures] (future-cancel f))

    (let [es (h/history-entries)]
      (is (<= (count es) 1000)
          "count ≤ history-cap even under contention")
      (is (= total (count es))
          (str "expected exactly " total
               " entries (cap = 1000, all unique, no dedup)"))
      (is (= expected (set es))
          "every distinct entry that was added is present (none lost)"))))

;; reset

(deftest reset-clears-history
  (h/history-add! "x")
  (h/history-add! "y")
  (h/history-reset!)
  (is (= [] (h/history-entries))
      "after reset, history is empty")
  (is (= [] (h/history-find-prefix "x"))
      "find-prefix after reset returns empty"))

;; string-type discipline

(deftest non-string-entry-is-coerced-via-str
  ;; ^String is a hint only; verify `(str entry)` coerces non-strings.
  (h/history-add! 42)
  (h/history-add! :keyword)
  (is (= ["42" ":keyword"] (h/history-entries))
      "non-string entries are coerced via str (defensive)"))