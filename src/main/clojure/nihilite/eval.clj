(ns nihilite.eval
  "Out-of-process eval for a JVM Nihilite is attached to.

   Nihilite opens no port. A process that wants to evaluate code in an
   attached JVM uses this: it starts a session, sends code into it, and
   polls for the result. Sessions are the unit, not single calls, because a
   REPL needs somewhere to keep its namespace and its last value.

   Four design constraints hold this open for true streaming later, without
   having to change how evaluation works:

   1. Output goes through one function, `emit!`. When streaming arrives it
      becomes another sink inside `emit!`, and nothing else moves.
   2. Every chunk lands in ONE ordered log tagged with :stream, so the
      interleaving of out and err is recorded rather than reconstructed.
      Two separate buffers would lose the order permanently.
   3. Each chunk carries a monotonic :seq, so `snapshot` can take a cursor
      and a consumer can read incrementally. Adding :since later is additive.
      The counter behind :seq counts output events and nothing else -- eval ids
      come off their own counter -- so the sequence stays dense and a cursor
      is a real index rather than a set of holes.
   4. A session keeps the Thread running its eval. That reference is the
      only reason `interrupt` can exist, and it cannot be recovered once
      dropped -- so it is kept from the start.

   What streaming cannot become: push rather than poll needs a channel the
   target can write to, which means the attaching side listens on loopback
   and the agent connects back. Nihilite itself still listens on nothing."
  (:import [java.io Writer]
           [java.util.concurrent.atomic AtomicLong]))

;;; ---------------------------------------------------------------- sessions

(defrecord Session [id ns-obj eval-ids counter events value error running])

(defonce ^:private sessions (atom {}))

(def ^:private id-counter (AtomicLong. 0))

(defn- render
  "Prints v to a string without ever throwing, and derefs a var first: a REPL
   shows the value `(def a 10)` produced, not `#'user/a`. The value came from
   user code, so a broken printmethod must not take the session down."
  ^String [v]
  (try
    (binding [*print-length* 512
              *print-level* 8]
      (pr-str (if (var? v) (deref v) v)))
    (catch Throwable _ "<unprintable>")))

(defn- emit!
  "The single output choke point for a session. Every chunk from every eval
   goes through here, which is what keeps the log ordered and leaves one
   place to attach a streaming sink to."
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
      ([x] (emit! s stream (cond (nil? x) nil (string? x) x :else (str x))))
      ([x off len] (emit! s stream (part->string x off len))))
    (flush [] nil)
    (close [] nil)))

;;; -------------------------------------------------------------------- API

(defn- session-ns
  "A fresh namespace with clojure.core referred into it.

   create-ns on its own gives an empty namespace where even `+` is
   unresolvable, which is not a REPL, it is a sandpit."
  ^clojure.lang.Namespace [sid]
  (let [n (create-ns (symbol (str "nihilite.eval.session." sid)))]
    (binding [*ns* n]
      (clojure.core/refer-clojure))
    n))

(defn open-session
  "Opens a session and returns its id. Each session evaluates in its own
   namespace, so `(def x 1)` in one session is not visible in another."
  ^String []
  (let [sid (str "s" (.incrementAndGet id-counter))
        s   (->Session sid
                       (session-ns sid)
                       (AtomicLong. 0)
                       (AtomicLong. 0)
                       (atom [])
                       (atom nil)
                       (atom nil)
                       (atom nil))]
    (swap! sessions assoc sid s)
    sid))

(defn session? [sid] (contains? @sessions sid))

(defn- run-eval!
  "The body of one eval, on its own thread. Split out of eval-in so the
   try/catch/finally nesting does not have to share a binding vector with the
   thread plumbing."
  [^Session s ^String code]
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
      (reset! (:error s) (render t)))
    (finally
      (reset! (:running s) nil))))

(defn eval-in
  "Starts evaluating code in the session's namespace and returns immediately
   with an eval id. The code runs on its own thread, so a form that never
   terminates cannot wedge the caller -- which matters because the usual
   caller is an attacher holding a loadAgent call open.

   Returns nil when the session id is unknown."
  ^String [^String sid ^String code]
  (when-let [^Session s (get @sessions sid)]
    (let [eid (.incrementAndGet ^AtomicLong (:eval-ids s))
          t   (doto (Thread. ^Runnable #(run-eval! s code)
                          (str "nihilite-eval-" sid "-" eid))
                (.setDaemon true))]
      (reset! (:running s) t)
      (.start t)
      (str sid "/" eid))))

(defn snapshot
  "Reads the session's accumulated output and state.

   `:since` is a cursor: pass the highest :seq you already have and only
   newer chunks come back. Omit it for everything, which is what a caller
   that has not been polling wants."
  ([sid] (snapshot sid nil))
  ([^String sid since]
   (if-let [^Session s (get @sessions sid)]
     (let [all    @(:events s)
           lower  (long (or since 0))
           events (if (zero? lower)
                    all
                    (filterv #(> (:seq %) lower) all))]
       {:session sid
        :ns (str (ns-name (:ns-obj s)))
        :events events
        :cursor (long (:seq (peek events) 0))
        :value @(:value s)
        :error @(:error s)
        :running? (some? @(:running s))})
     {:session sid :error "unknown session" :running? false})))

(defn interrupt
  "Asks the session's running eval to stop.

   This is Thread.interrupt, so it unblocks a thread waiting on I/O, sleep or
   a monitor, and throws InterruptedException into it. It does NOT stop a
   tight `(loop [] (recur))`: Clojure recur never checks the interrupt flag,
   and Thread.stop, which would, was removed in JDK 20. A CPU-bound eval that
   must be killed has to cooperate -- poll `snapshot` and check :running? from
   the code being evaluated."
  [^String sid]
  (if-let [^Session s (get @sessions sid)]
    (if-let [^Thread t @(:running s)]
      (do (.interrupt t) {:session sid :interrupted? true})
      {:session sid :interrupted? false :reason :not-running})
    {:session sid :interrupted? false :reason :unknown-session}))

(defn close-session
  "Drops a session and interrupts anything still running in it."
  [^String sid]
  (if (contains? @sessions sid)
    (do (interrupt sid)
        (swap! sessions dissoc sid)
        true)
    false))

(defn session-ids [] (vec (keys @sessions)))
