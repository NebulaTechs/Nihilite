(ns nihilite.eval
  "Out-of-process eval for a JVM Nihilite is attached to.

   Nihilite opens no port. A process that wants to evaluate code in an
   attached JVM uses this: it opens a session, sends code into it, and polls
   for the result.

   Sessions are the unit, not single calls, because a REPL needs somewhere to
   keep its namespace and its last value between forms. The machinery behind
   one -- its output log, its cursor, the thread an interrupt needs -- is in
   nihilite.eval.session, along with the four invariants that keep streaming a
   later addition rather than a redesign.

   What streaming cannot become: push rather than poll needs a channel the
   target can write to, which means the attaching side listens on loopback and
   the agent connects back. Nihilite itself still listens on nothing."
  (:require [nihilite.eval.session :as session]))

(defn open-session
  "Opens a session and returns its id. Each session evaluates in its own
   namespace, so `(def x 1)` in one session is not visible in another."
  ^String []
  (session/register!))

(defn session? [sid]
  (session/registered? sid))

(defn eval-in
  "Starts evaluating code in the session's namespace and returns immediately
   with an eval id. The code runs on its own thread, so a form that never
   terminates cannot wedge the caller -- which matters because the usual
   caller is an attacher holding a loadAgent call open.

   Returns nil when the session id is unknown."
  ^String [^String sid ^String code]
  (when-let [s (session/lookup sid)]
    (session/start-eval! s code)))

(defn snapshot
  "Reads the session's accumulated output and state.

   `:since` is a cursor: pass the highest :seq you already have and only newer
   chunks come back. Omit it for everything, which is what a caller that has
   not been polling wants.

   The returned `:cursor` is monotonic -- see nihilite.eval.session/read-state for
   why deriving it from the page alone would be wrong."
  ([sid] (snapshot sid nil))
  ([sid since]
   (if-let [s (session/lookup sid)]
     (session/read-state s since)
     {:session sid :error "unknown session" :running? false})))

(defn interrupt
  "Asks the session's running eval to stop.

   This is Thread.interrupt, so it unblocks a thread waiting on I/O, sleep or a
   monitor, and throws InterruptedException into it. It does NOT stop a tight
   `(loop [] (recur))`: Clojure recur never checks the interrupt flag, and
   Thread.stop, which would, was removed in JDK 20. A CPU-bound eval that must
   be killed has to cooperate -- poll `snapshot` and check :running? from the
   code being evaluated."
  [^String sid]
  (if-let [s (session/lookup sid)]
    (if-let [^Thread t (session/running-thread s)]
      (do (.interrupt t) {:session sid :interrupted? true})
      {:session sid :interrupted? false :reason :not-running})
    {:session sid :interrupted? false :reason :unknown-session}))

(defn close-session
  "Drops a session and interrupts anything still running in it."
  [^String sid]
  (if (session/registered? sid)
    (do (interrupt sid)
        (session/drop! sid)
        true)
    false))

(defn session-ids []
  (session/all-ids))