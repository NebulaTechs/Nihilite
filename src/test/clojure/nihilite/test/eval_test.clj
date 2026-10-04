(ns nihilite.test.eval-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [nihilite.eval :as ev]
            [nihilite.eval.session :as session]))

(defn- settle
  "Polls a session until it stops running. eval-in is asynchronous, so a
   test that reads :value straight after calling it would be racing the
   thread it just started."
  ([sid] (settle sid 2000))
  ([sid timeout-ms]
   (let [deadline (+ (System/nanoTime)
                     (.toNanos java.util.concurrent.TimeUnit/MILLISECONDS
                               timeout-ms))]
     (loop []
       (let [s (ev/snapshot sid)]
         (cond
           (not (:running? s)) s
           (> (System/nanoTime) deadline) s
           :else (do (Thread/sleep 5) (recur))))))))

(defn- text-of [snapshot]
  (apply str (map :text (:events snapshot))))

(deftest running-stays-true-while-a-later-eval-runs
  ;; A second eval submitted while the first was executing used to make
  ;; :running? report false for the whole of the second. The thread cleared
  ;; :running when the first finished, and by then the second was already
  ;; off the queue, so both of running?'s signals -- the atom and the queue
  ;; size -- read empty. A caller waiting on it was told the work was done
  ;; while it was still running.
  (let [sid (ev/open-session)]
    (ev/eval-in sid "(+ 1 1)")
    (ev/eval-in sid "(Thread/sleep 500)")
    (Thread/sleep 150)
    (is (:running? (ev/snapshot sid))
        "the first finished, the second is still sleeping")
    (is (= 2 @(:running (session/lookup sid)))
        "and :running names the eval that is actually executing")
    (let [s (settle sid 4000)]
      (is (not (:running? s)) "both are done")
      (is (= "2" (:value s)) "the first result; the second returned nil"))
    (ev/close-session sid)))

(deftest a-caller-waiting-on-a-later-eval-is-not-released-early
  ;; The same defect seen through the thing a caller actually does: poll
  ;; until :running? is false. It returned on the first poll, before the
  ;; sleeping eval had produced anything.
  (let [sid (ev/open-session)]
    (ev/eval-in sid "(+ 1 1)")
    (ev/eval-in sid "(Thread/sleep 400)")
    (let [s (settle sid 3000)]
      (is (not (:running? s)))
      (is (str/includes? (text-of s) "2")
          "both evals ran; the sleeping one was not abandoned"))
    (ev/close-session sid)))

(deftest open-session-returns-unique-ids
  (let [a (ev/open-session)
        b (ev/open-session)]
    (is (string? a))
    (is (not= a b))
    (is (ev/session? a))
    (is (ev/session? b))
    (ev/close-session a)
    (ev/close-session b)
    (is (not (ev/session? a)))))

(deftest eval-produces-value
  (let [sid (ev/open-session)]
    (ev/eval-in sid "(+ 1 2)")
    (let [s (settle sid)]
      (is (false? (:running? s)))
      (is (= "3" (:value s)))
      (is (nil? (:error s)))
      (is (str/includes? (text-of s) "3")))
    (ev/close-session sid)))

(deftest eval-multiple-forms-in-one-call
  (let [sid (ev/open-session)]
    (ev/eval-in sid "(def a 10) (* a 4)")
    (let [s (settle sid)]
      (is (= "40" (:value s)))
      (is (nil? (:error s))))
    (ev/close-session sid)))

(deftest session-keeps-its-own-namespace
  (let [sid (ev/open-session)]
    (ev/eval-in sid "(def only-here 42)")
    (settle sid)
    (is (str/includes? (:ns (settle sid)) "nihilite.eval.session."))
    (ev/close-session sid)))

(deftest sessions-are-isolated
  (let [a (ev/open-session)
        b (ev/open-session)]
    (ev/eval-in a "(def shared-name 1)")
    (settle a)
    (ev/eval-in b "shared-name")
    (let [s (settle b)]
      (is (some? (:error s)) "b must not see a's def")
      ;; the summary line the :err chunk carries is the compiler exception;
      ;; the unresolved symbol is in the cause, so assert on the rendered
      ;; exception rather than the one-line summary
      (is (str/includes? (:error s) "Unable to resolve symbol"))
      (is (nil? (:value s))))
    (ev/close-session a)
    (ev/close-session b)))

(deftest output-and-error-share-one-ordered-log
  (let [sid (ev/open-session)]
    (ev/eval-in sid "(println \"to-out\") (binding [*out* *err*] (println \"to-err\")) :done")
    (let [s (settle sid)
          events (:events s)]
      (is (some #(= :out (:stream %)) events))
      (is (some #(= :err (:stream %)) events))
      ;; one log, so the seq numbers interleave rather than restart per stream
      (is (= (range 1 (inc (count events)))
             (map :seq events)))
      (is (= "to-err" (->> events
                           (filter #(= :err (:stream %)))
                           first
                           :text
                           str/trim))))
    (ev/close-session sid)))

(deftest since-cursor-returns-only-newer-chunks
  (let [sid (ev/open-session)]
    (ev/eval-in sid "(println \"first\")")
    (let [a (settle sid)
          cursor (:cursor a)
          total (count (:events a))]
      (is (pos? total))
      (ev/eval-in sid "(println \"second\")")
      (let [b (settle sid)]
        (is (> (count (:events b)) total))
        (let [newer (ev/snapshot sid cursor)]
          (is (every? #(> (:seq %) cursor) (:events newer)))
          (is (str/includes? (text-of newer) "second"))
          (is (not (str/includes? (text-of newer) "first")))))
      ;; the cursor form and the full form agree on the total
      (is (= (count (:events (ev/snapshot sid))) (count (:events (settle sid))))))
    (ev/close-session sid)))

(deftest thrown-error-is-captured-not-propagated
  (let [sid (ev/open-session)]
    (ev/eval-in sid "(throw (ex-info \"boom\" {:a 1}))")
    (let [s (settle sid)]
      (is (nil? (:value s)))
      (is (some? (:error s)))
      (is (str/includes? (:error s) "boom"))
      (is (str/includes? (text-of s) "boom")))
    (ev/close-session sid)))

(deftest a-successful-eval-clears-the-previous-failure
  ;; A session outlives one eval -- that is what makes it a REPL. If a
  ;; successful eval left :error alone, a caller polling it could not tell
  ;; "this failed" from "this failed last time", and a driver checking
  ;; :error before a step would see a failure that had already passed.
  (let [sid (ev/open-session)]
    (ev/eval-in sid "(/ 1 0)")
    (let [s (settle sid)]
      (is (some? (:error s)) "the divide by zero is reported"))
    (ev/eval-in sid "(* 6 7)")
    (let [s (settle sid)]
      (is (nil? (:error s))
          "the next successful eval reports no error, not the old one")
      (is (= "42" (:value s))))
    (ev/close-session sid)))

(deftest the-error-checks-out-and-exception-records-still-accumulate
  ;; Clearing :error must not touch the runtime counters, which are
  ;; cumulative across the session rather than per-eval.
  (let [sid (ev/open-session)]
    (ev/eval-in sid "(throw (ex-info \"boom\" {}))")
    (settle sid)
    (ev/eval-in sid "(+ 1 1)")
    (let [s (settle sid)]
      (is (nil? (:error s)))
      (is (str/includes? (text-of s) "boom")
          "the earlier failure is still in the ordered output log"))
    (ev/close-session sid)))

(deftest unknown-session-is-reported-not-thrown
  (is (nil? (ev/eval-in "no-such-session" "(+ 1 1)")))
  (is (= "unknown session" (:error (ev/snapshot "no-such-session"))))
  (is (= :unknown-session (:reason (ev/interrupt "no-such-session"))))
  (is (false? (ev/close-session "no-such-session"))))

(deftest interrupt-stops-a-blocked-eval
  (let [sid (ev/open-session)]
    ;; Thread/sleep is interruptible, so this is the case interrupt covers.
    (ev/eval-in sid "(do (Thread/sleep 60000) :finished)")
    (Thread/sleep 100)
    (is (true? (:running? (ev/snapshot sid))))
    (let [r (ev/interrupt sid)]
      (is (:interrupted? r)))
    (let [s (settle sid 3000)]
      (is (false? (:running? s)) "the sleeping eval must have been interrupted"))
    (ev/close-session sid)))

(deftest interrupt-on-idle-session-reports-not-running
  (let [sid (ev/open-session)]
    (is (= :not-running (:reason (ev/interrupt sid))))
    (ev/close-session sid)))

(deftest close-session-interrupt-then-forgets
  (let [sid (ev/open-session)]
    (ev/eval-in sid "(Thread/sleep 60000)")
    (Thread/sleep 100)
    (is (true? (ev/close-session sid)))
    (is (not (ev/session? sid)))
    (is (not-any? #(= sid %) (ev/session-ids)))))

(deftest printing-several-arguments-keeps-the-spaces
  (let [sid (ev/open-session)]
    (ev/eval-in sid "(println \"a\" 1) (println \"sp\" 2) (println \"one\")")
    (let [printed (->> (:events (settle sid))
                       (filter #(= :out (:stream %)))
                       (map :text)
                       (apply str))]
      (is (str/includes? printed "a 1\n")
          (str "println with two args must separate them with a space: " (pr-str printed)))
      (is (str/includes? printed "sp 2\n"))
      (is (str/includes? printed "one\n")))
    (ev/close-session sid)))
(deftest two-evals-in-one-session-run-in-order
  ;; One thread per session, not per eval. Two evals in the same session
  ;; share its namespace, so running them at once raced on *ns* -- and the
  ;; second thread's binding vector replaced the first's, so the first eval
  ;; either read the wrong namespace or wrote its output through the wrong
  ;; writer. Serialising is what makes a session a session.
  (let [sid (ev/open-session)]
    (ev/eval-in sid "(def order (atom []))")
    (settle sid)
    (ev/eval-in sid "(swap! order conj :first)")
    (ev/eval-in sid "(swap! order conj :second)")
    (ev/eval-in sid "(swap! order conj :third)")
    (let [s (settle sid)]
      (is (nil? (:error s)) "no eval in the batch failed")
      (is (= "[:first :second :third]" (:value s))
          "three concurrent submissions ran in submission order"))
    (ev/close-session sid)))

(deftest running-is-true-from-enqueue-not-just-from-take
  ;; :running? is what a caller polls to know an eval finished. If it went
  ;; true only when the thread picked the task up, a poll landing between the
  ;; enqueue and the take would see false and read the session too early.
  (let [sid (ev/open-session)]
    (ev/eval-in sid "(Thread/sleep 150)")
    (Thread/sleep 20)
    (is (:running? (ev/snapshot sid))
        "a submitted eval reads as running straight away")
    (settle sid)
    (is (not (:running? (ev/snapshot sid)))
        "and stops once it is done")
    (ev/close-session sid)))
