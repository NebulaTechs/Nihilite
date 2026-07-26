(ns nihilite.test.readline-history
  "Unit tests for `nihilite.readline.history` — shared server-wide
   history deque.

   Each `deftest` resets the shared atom via `history-reset!` in its
   `:before` clause so tests are order-independent. A `:once`
   fixture would also work but `:each` keeps a crashed earlier test
   from poisoning later ones."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.readline.history :as h]))

;; ---------------------------------------------------------------------------
;; fixtures
;; ---------------------------------------------------------------------------

(defn- reset-history!
  []
  (h/history-reset!))

;; `use-fixtures` is a clojure.test multimethod; each fixture is a
;; `(fn [test-fn] ... (test-fn) ...)` wrapper. A bare `(fn [] ...)`
;; fixture won't work — the wrapper must invoke `test-fn` to actually
;; run the test under it.
(use-fixtures :each
  (fn [run-tests]
    (reset-history!)
    (run-tests)))

;; ---------------------------------------------------------------------------
;; basic add / read
;; ---------------------------------------------------------------------------

(deftest add-one-entry-then-read-entries
  (h/history-add! "first")
  (is (= ["first"] (h/history-entries))
      "1 entry added → 1-element vec snapshot"))

(deftest add-five-distinct-entries-are-oldest-first
  (doseq [e ["a" "b" "c" "d" "e"]]
    (h/history-add! e))
  (is (= ["a" "b" "c" "d" "e"] (h/history-entries))
      "5 distinct entries → oldest-first vec, all 5 present"))

;; ---------------------------------------------------------------------------
;; consecutive dedup
;; ---------------------------------------------------------------------------

(deftest consecutive-duplicate-is-noop
  (h/history-add! "foo")
  (h/history-add! "foo")
  (is (= ["foo"] (h/history-entries))
      "consecutive dedup: second add of same entry → single element"))

(deftest non-consecutive-duplicate-is-kept
  ;; Sanity check: dedup is strictly CONSECUTIVE, not global. Insert
  ;; foo, bar, foo — the two foo's are separated by bar, so both
  ;; survive. Guards against a buggy implementation that drops all
  ;; duplicates.
  (h/history-add! "foo")
  (h/history-add! "bar")
  (h/history-add! "foo")
  (is (= ["foo" "bar" "foo"] (h/history-entries))
      "non-consecutive duplicates both retained"))

;; ---------------------------------------------------------------------------
;; cap-at-1000 (FIFO drop oldest)
;; ---------------------------------------------------------------------------

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
      ;; Oldest-first ordering check: first entry must be entry-1
      ;; (the second one we added), last must be entry-1000.
      (is (= "entry-1" (first es)))
      (is (= "entry-1000" (last es))))))

;; ---------------------------------------------------------------------------
;; find-prefix
;; ---------------------------------------------------------------------------

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
  ;; prefix-match (substring-from-zero), not exact-match: 'foo'
  ;; matches BOTH 'foo' and 'foobar'. Guards against a buggy
  ;; impl that does exact-match only.
  (is (= ["foo" "foobar"] (h/history-find-prefix "foo"))
      "prefix-match (substring-from-0): 'foo' also matches 'foobar'"))

;; ---------------------------------------------------------------------------
;; concurrency
;; ---------------------------------------------------------------------------

(deftest concurrent-add-stays-within-cap-and-loses-no-fresh-entries
  ;; 10 threads × 100 entries = 1000 attempted adds. Each thread
  ;; adds its own unique slice ["t0-e0" .. "t0-e99"] etc., so
  ;; consecutive-dedup never fires within a thread (each entry is
  ;; unique within its thread). Across threads, identical entries
  ;; are possible but the cap and dedup rules apply.
  ;;
  ;; Asserts:
  ;;   1. final count ≤ 1000 (cap respected under contention)
  ;;   2. exactly 1000 distinct entries attempted; since the cap is
  ;;      exactly 1000 and no entries are dedup'd (all 1000 are
  ;;      unique across threads), the surviving set must contain
  ;;      all 1000 distinct strings.
  (let [n-threads 10
        per-thread 100
        total (* n-threads per-thread)
        ;; compute expected entries: thread t adds entries
        ;; "tN-eM" for M in [0,99]. Each is globally unique so no
        ;; dedup fires.
        expected (set (for [t (range n-threads)
                            m (range per-thread)]
                        (str "t" t "-e" m)))
        ;; Latches so all threads start hammering the atom at the
        ;; same instant — maximizes contention.
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

;; ---------------------------------------------------------------------------
;; reset
;; ---------------------------------------------------------------------------

(deftest reset-clears-history
  (h/history-add! "x")
  (h/history-add! "y")
  (h/history-reset!)
  (is (= [] (h/history-entries))
      "after reset, history is empty")
  (is (= [] (h/history-find-prefix "x"))
      "find-prefix after reset returns empty"))

;; ---------------------------------------------------------------------------
;; string-type discipline
;; ---------------------------------------------------------------------------

(deftest non-string-entry-is-coerced-via-str
  ;; The fn is typed ^String in the arglist, but Clojure is
  ;; dynamic — verify the internal `(str entry)` coercion path
  ;; surfaces non-string values without crashing.
  (h/history-add! 42)
  (h/history-add! :keyword)
  (is (= ["42" ":keyword"] (h/history-entries))
      "non-string entries are coerced via str (defensive)"))