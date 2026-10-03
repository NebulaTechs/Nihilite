(ns probe.loop-limits
  "Two questions the eval docstring only asserts:

   1. infinite RECURSION -- does it terminate, and how does the failure surface?
   2. infinite LOOP -- can interrupt actually stop it?

   The docstring says interrupt cannot stop a tight recur loop. That is a claim
   about a live thread, so it wants measuring rather than reasoning."
  (:require [nihilite.eval :as ev]))

(defn- settled
  "Polls until the session stops running, or timeout-ms elapses."
  [sid timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [s (ev/snapshot sid)]
        (cond
          (not (:running? s)) s
          (> (System/currentTimeMillis) deadline) {:timeout true :snapshot s}
          :else (do (Thread/sleep 20) (recur)))))))

(defn -main [& _]
  (println "=== 1. infinite recursion ===")
  (let [sid (ev/open-session)]
    (ev/eval-in sid "(defn boom [n] (inc (boom (inc n)))) (boom 0)")
    (let [s (settled sid 20000)]
      (println "  settled?      " (not (:running? s)))
      (println "  error present?" (some? (:error s)))
      (println "  error head    " (let [e (str (:error s))]
                                 (subs e 0 (min 110 (count e)))))
      (println "  out head      " (let [o (str (:out s))]
                                 (subs o 0 (min 60 (count o)))))))

  (println)
  (println "=== 2. infinite loop ===")
  (let [sid (ev/open-session)
        running? #(get (ev/snapshot sid) :running?)]
    (ev/eval-in sid "(loop [] (recur))")
    (Thread/sleep 1500)
    (println "  running after 1.5s?      " (running?))
    (println "  interrupt ->" (pr-str (ev/interrupt sid)))
    (Thread/sleep 1500)
    (println "  running after int+1.5s?  " (running?))
    (println "  interrupt again ->" (pr-str (ev/interrupt sid)))
    (Thread/sleep 1000)
    (println "  still running?           " (running?))
    ;; Report and abandon rather than kill: the thread is a daemon, so the
    ;; caller can stop reading, but nothing made it stop burning CPU.
    (println "  -> close-session ->" (pr-str (ev/close-session sid)))
    (Thread/sleep 500)
    (println "     running after close?  " (running?))))
