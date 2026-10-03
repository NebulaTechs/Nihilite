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
           [java.util.concurrent.atomic AtomicLong]))

(defrecord Session [id ns-obj eval-ids counter events value error running])

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
  "The body of one eval, on its own thread. Split out of start-eval! so the
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

(defn register!
  "Creates a session, stores it, and returns its id."
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
  "Starts evaluating code in the session's namespace on a fresh daemon thread
   and returns the eval id. Asynchronous on purpose: see nihilite.eval/eval-in
   for why the caller must not be made to wait."
  ^String [^Session s ^String code]
  (let [eid (.incrementAndGet ^AtomicLong (:eval-ids s))
        t   (doto (Thread. ^Runnable #(run-eval! s code)
                        (str "nihilite-eval-" (:id s) "-" eid))
              (.setDaemon true))]
    (reset! (:running s) t)
    (.start t)
    (str (:id s) "/" eid)))

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
     :running? (some? @(:running s))}))

(defn running-thread
  "The Thread currently running in this session, or nil. Held onto by the
   session rather than looked up, because a Thread cannot be recovered once
   dropped -- which is the only reason an interrupt can exist at all."
  ^Thread [^Session s]
  @(:running s))