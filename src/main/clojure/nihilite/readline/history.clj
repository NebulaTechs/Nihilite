(ns nihilite.readline.history
  "Server-wide shared command-history deque for the raw-branch readline.

   Storage: a single `(atom (clojure.lang.PersistentQueue/EMPTY))`.
   Every connected raw-branch socket reads and writes the same atom,
   so `(+ 1 2)` typed in one session shows up in `Up`-arrow recall
   in another. `swap!` provides all the concurrency safety needed.

   Cap: 1000 entries (FIFO — oldest dropped when exceeded). Dedup:
   a new entry equal to the most-recent entry is a no-op (avoids
   `Up`-arrow spam when the user re-submits the same form).

   Persistence: NONE. Restart = empty history. No `~/.nihilite-history`
   file is read or written — deliberate, so secret-bearing forms do
   not leak across server restarts."
  (:import [clojure.lang PersistentQueue]))

(def ^:const ^:private history-cap 1000)

(defonce ^{:private true} history
  (atom PersistentQueue/EMPTY))

(defn history-add!
  "Append entry to shared history. No-op if equals most-recent. Cap-exceeded: pop ∘ conj."
  [^String entry]
  (let [e (str entry)]
    (swap! history
           (fn [q]
             (let [newest (when (seq q) (last q))]
               (cond
                 (and newest (= newest e))
                 q

                 (< (count q) history-cap)
                 (conj q e)

                 :else
                 (pop (conj q e))))))))

(defn history-reset!
  "Clear all history. Test-only — never call from production code."
  []
  (reset! history PersistentQueue/EMPTY)
  nil)

(defn history-entries
  "Return a `vec` snapshot of all history entries in oldest-first
   order. The returned vector is a fresh, decoupled copy."
  []
  (let [q @history]
    (into [] q)))

(defn history-find-prefix
  "Return a `vec` of history entries whose name starts with `q`,
   case-sensitive, oldest-first. An empty prefix returns every entry.
   Used by `nihilite.readline/loop`'s C-r reverse-incremental search."
  [^String q]
  (let [prefix (str q)
        prefix-len (count prefix)]
    (loop [remaining @history
           out (transient [])]
      (if (empty? remaining)
        (persistent! out)
        (let [head (peek remaining)
              tail (pop remaining)]
          (recur tail
                 (if (and (>= (count head) prefix-len)
                          (= (.substring ^String head 0 prefix-len) prefix))
                   (conj! out head)
                   out)))))))