(ns nihilite.eval.session
  "One eval session: its namespace, its output log, its last value, its last
   error, and the thread currently running in it.

   A session is the unit of evaluation, not a single call, because a REPL needs
   somewhere to keep a namespace and a last value between forms.

   Four invariants hold this open for true streaming later, without having to
   change how evaluation works:

   1. Output goes through one function, `emit!`. When streaming arrives it
      becomes another sink inside `emit!`, and nothing else moves.
   2. Every chunk lands in ONE ordered log tagged with :stream, so the
      interleaving of out and err is recorded rather than reconstructed. Two
      separate buffers would lose the order permanently.
   3. Each chunk carries a monotonic :seq, so a read can take a cursor and a
      consumer can read incrementally. The counter behind :seq counts output
      events and nothing else -- eval ids come off their own counter -- so the
      sequence stays dense and a cursor is a real index, not a set of holes.
   4. A session keeps the Thread running its eval. That reference is the only
      reason an interrupt can exist, and it cannot be recovered once dropped,
      so it is kept from the start.

   Invariants 1-3 are about the output machinery, which is why they live here
   rather than in the API namespace. The API's own rationale is in
   nihilite.eval."
  (:import [java.io Writer]
           [java.util.concurrent LinkedBlockingQueue]
           [java.util.concurrent.atomic AtomicLong]))

(defrecord Session [id ns-obj eval-ids counter events value error
                    running queue thread])

(defonce ^:private sessions (atom {}))

(def ^:private id-counter (AtomicLong. 0))

(defn- render
  "Prints v to a string without ever throwing, and derefs a var first: a REPL
   shows the value `(def a 10)` produced, not #'user/a. The value came from
   user code, so a broken printmethod must not take the session down."
  ^String [v]
  (try
    (binding [*print-length* 512
              *print-level* 8]
      (pr-str (if (var? v) (deref v) v)))
    (catch Throwable _ "<unprintable>")))

(defn- emit!
  "The single output choke point for a session. Every chunk from every eval
   goes through here, which is what keeps the log ordered and leaves one place
   to attach a streaming sink to."
  [^Session s stream ^String text]
  (when (and text (seq text))
    (let [n (.incrementAndGet ^AtomicLong (:counter s))]
      (swap! (:events s) conj {:seq n :stream stream :text text}))))

(defn- part->string
  "Renders the (char[] or String, offset, length) forms of Writer/write."
  [x off len]
  (let [off (int off)
        len (int len)]
    (cond
      (nil? x) nil
      (string? x) (let [^String s x
                        n (min (count s) (+ off len))]
                    (when (pos? n) (subs s off (+ off n))))
      (instance? (Class/forName "[C") x)
      (let [^chars a x
            n (min (alength a) (+ off len))]
        (when (pos? n) (String. a off n)))
      :else (let [s (str x)
                  n (min (count s) (+ off len))]
              (when (pos? n) (subs s off (+ off n)))))))

(defn- session-writer ^Writer [^Session s stream]
  (proxy [Writer] []
    (write
      ([x]
       (emit! s stream
              (cond
                (nil? x) nil
                (string? x) x
                ;; proxy collapses write(int) and write(Object) into this one
                ;; arity, so Writer's own write(int) -- which would encode the
                ;; code point as a character -- never runs. A boxed Integer here
                ;; is a code point: println writes the space between arguments
                ;; as write(int 32), and (str x) turned that into "32".
                ;; Measured: (println "a" 1) emitted "a321".
                (instance? Integer x) (String. (Character/toChars (int x)))
                (bytes? x) (String. ^bytes x "UTF-8")
                :else (str x))))
      ([x off len] (emit! s stream (part->string x off len))))
    (flush [] nil)
    (close [] nil)))

(defn- session-ns
  "A fresh namespace with clojure.core referred into it.

   create-ns on its own gives an empty namespace where even `+` is
   unresolvable, which is not a REPL, it is a sandpit."
  ^clojure.lang.Namespace [sid]
  (let [n (create-ns (symbol (str "nihilite.eval.session." sid)))]
    (binding [*ns* n]
      (clojure.core/refer-clojure))
    n))

(defn- run-eval!
  "The body of one eval, run on the session's own thread.

   Does not touch :running -- that is the session thread's state, not this
   eval's. An eval that throws is reported to the session and the loop moves on
   to the next one, which is the whole point of having a queue."
  [^Session s ^String code]
  ;; Clear the previous failure before running, not after. A session
  ;; outlives one eval by design -- that is what makes it a REPL rather
  ;; than a function call -- so an eval that succeeds after one that threw
  ;; would otherwise keep reporting the old throwable, and a caller
  ;; polling :error could not tell "this failed" from "this failed last
  ;; time". Measured: (/ 1 0) then (* 6 7) reported the divide by zero
  ;; alongside a correct :value of 42.
  (reset! (:error s) nil)
  (try
    (binding [*ns*  (:ns-obj s)
              *out* (session-writer s :out)
              *err* (session-writer s :err)
              *e    (Exception.)]
      (doseq [f (read-string (str "[" code "]"))]
        (let [v (eval f)]
          (when (some? v)
            (reset! (:value s) (render v))
            (emit! s :out (str (render v) "\n"))))))
    (catch Throwable t
      (emit! s :err (str (type t) ": " (.getMessage ^Throwable t) "\n"))
      (reset! (:error s) (render t)))))

(defn- session-loop
  "Drains the session's queue on one thread, forever.

   One thread per session rather than one per eval, which is what nREPL does
   and what the semantics need: a session's namespace belongs to the session, so
   two evals running at once in it would race on the same *ns* and on the same
   binding vector. Serialising also means :running? means 'this session has work
   left' rather than 'the most recent eval has a thread', which is a question a
   caller can actually act on.

   InterruptedException ends the thread. Nothing else does: an eval that
   ignores interrupts keeps running, and the loop stays behind it. That is
   honest -- a tight (loop [] (recur)) cannot be stopped from outside, because
   recur never checks the flag and Thread.stop was removed in JDK 20."
  [^Session s]
  (let [^LinkedBlockingQueue q (:queue s)]
    (loop []
      (let [task (.take q)]
        (when task
          (run-eval! s (:code task))
          ;; Mark the next queued eval as the running one, rather than
          ;; clearing :running and letting start-eval! set it. Clearing
          ;; was wrong the moment a second eval was submitted while the
          ;; first was executing: this thread finished the first, nil'd
          ;; the atom, and the second -- already taken off the queue, so
          ;; queue.size was 0 too -- was invisible to running?. Measured:
          ;; eval "(+ 1 1)" then "(Thread/sleep 600)" reported
          ;; :running? false for the whole 600ms, so a caller waiting on
          ;; it was told the work was done while it was still running.
          (let [next (.peek q)]
            (reset! (:running s) (some-> next :eid)))
          (recur))))))

(defn register!
  "Creates a session, starts its thread, stores it, and returns its id."
  ^String []
  (let [sid (str "s" (.incrementAndGet id-counter))
        q   (LinkedBlockingQueue.)
        s   (->Session sid
                       (session-ns sid)
                       (AtomicLong. 0)
                       (AtomicLong. 0)
                       (atom [])
                       (atom nil)
                       (atom nil)
                       ;; the eval id currently executing, or nil
                       (atom nil)
                       q
                       nil)
        ;; The thread closes over `s`, which does not yet carry the thread.
        ;; It never needs it: the loop only reads the queue and the atoms.
        t   (doto (Thread. ^Runnable #(session-loop s)
                        (str "nihilite-eval-" sid))
              (.setDaemon true))]
    (swap! sessions assoc sid (assoc s :thread t))
    (.start t)
    sid))

(defn lookup
  "The Session behind an id, or nil. Callers that own the id arithmetic --
   interrupt, close-session -- use this."
  ^Session [^String sid]
  (get @sessions sid))

(defn registered? [sid]
  (contains? @sessions sid))

(defn all-ids [] (vec (keys @sessions)))

(defn drop! [sid]
  (swap! sessions dissoc sid)
  nil)

(defn start-eval!
  "Queues code for evaluation on the session's thread and returns the eval id.

   Asynchronous on purpose: see nihilite.eval/eval-in for why the caller must
   not be made to wait. Enqueued rather than run on a thread of its own, so
   that two evals in one session cannot race on its namespace -- and so that
   interrupt has a thread to reach even when several evals are queued."
  ^String [^Session s ^String code]
  (let [eid (.incrementAndGet ^AtomicLong (:eval-ids s))]
    ;; :running is set at enqueue time, not when the thread picks the task up.
    ;; Marking it on take would leave a window where the queue is empty and
    ;; nothing is marked -- the eval is running but :running? says otherwise,
    ;; and a caller polling for completion reads the session too early.
    (reset! (:running s) eid)
    (.put ^LinkedBlockingQueue (:queue s) {:eid eid :code code})
    (str (:id s) "/" eid)))

(defn running?
  "Whether the session has an eval executing or one still queued."
  [^Session s]
  (boolean (or @(:running s)
               (pos? (.size ^LinkedBlockingQueue (:queue s))))))

(defn stop-running!
  "Interrupts the current eval and discards anything queued behind it.

   The thread itself survives: it goes back to .take and serves the next eval.
   Use stop! to end the session."
  [^Session s]
  (when-let [^Thread t (:thread s)]
    (.interrupt t))
  (when-let [^LinkedBlockingQueue q (:queue s)]
    (.clear q))
  nil)

(defn read-state
  "Reads the session's accumulated output and state.

   `since` is a cursor: pass the highest :seq you already have and only newer
   chunks come back. Pass nil for everything, which is what a caller that has
   not been polling wants.

   `:cursor` comes back monotonic -- the higher of what you passed in and the
   last chunk in this page. Deriving it from the page alone would reset it to 0
   whenever a read returned nothing new, and the caller's next read would start
   from the beginning and reprint the whole session."
  [^Session s since]
  (let [all    @(:events s)
        lower  (long (or since 0))
        events (if (zero? lower)
                 all
                 (filterv #(> (:seq %) lower) all))]
    {:session (:id s)
     :ns (str (ns-name (:ns-obj s)))
     :events events
     :cursor (long (max lower (:seq (peek events) 0)))
     :value @(:value s)
     :error @(:error s)
     :running? (running? s)}))

(defn running-thread
  "The session's thread, or nil if it is not started. One thread per session
   rather than per eval, which is what makes interrupt reach queued work.

   A Thread cannot be recovered once dropped, which is the only reason the
   session holds this reference at all."
  ^Thread [^Session s]
  (:thread s))

(defn stop!
  "Interrupts the session's thread and drops any queued work. The thread ends
   on the InterruptedException .take throws; anything already executing keeps
   running to completion, which is the cooperative limit, not a bug."
  [^Session s]
  (when-let [^Thread t (:thread s)]
    (.interrupt t))
  (when-let [^LinkedBlockingQueue q (:queue s)]
    (.clear q))
  nil)