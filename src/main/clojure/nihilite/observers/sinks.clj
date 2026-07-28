(ns nihilite.observers.sinks
  "Named sinks for the P1 subscriber surface.

   A sink is a 1-arg IFn `(fn [event] …)`. Sink errors are
   caught INSIDE the subscriber bridge (B1 fix) — never propagate
   to the dispatcher."
  (:require [clojure.tools.logging :as log]))

(defonce ^:private sinks
  (atom {}))

(defn register-sink!
  "Register (or replace) a sink under `name`. The sink is a 1-arg
   IFn. Returns the registered IFn."
  [name sink-fn]
  (swap! sinks assoc name sink-fn)
  sink-fn)

(defn unregister-sink!
  "Remove a sink by name. Returns true if removed, false if missing."
  [name]
  (let [hit (swap! sinks dissoc name)]
    (contains? hit name)))

(defn lookup
  "Return the sink fn registered under `name`, or nil."
  [name]
  (@sinks name))

(defn known?
  "True if a sink is registered under `name`."
  [name]
  (contains? @sinks name))

(defonce ^:private ring-buffer-storage
  (atom clojure.lang.PersistentQueue/EMPTY))

(defonce aggregate-sink
  (atom {:by-class-method {} :by-phase {}}))

(declare register-builtins!)

(defn clear!
  "Reset the sink registry to the built-in sinks only, AND
   drain the `:ring-buffer` / `:aggregate` storage atoms."
  []
  (reset! sinks {})
  (reset! ring-buffer-storage clojure.lang.PersistentQueue/EMPTY)
  (reset! aggregate-sink {:by-class-method {} :by-phase {}})
  (register-builtins!))

(defn- println-sink [ev]
  (try (println ev)
       (catch Throwable t
         (log/warn t "println-sink threw (id=" (:id ev) ")"))))

(defn ring-buffer
  "Return the underlying ring-buffer atom. Operators may `take-events`
   on it to drain."
  [] ring-buffer-storage)

(defn- ring-buffer-sink [ev]
  (swap! ring-buffer-storage conj ev))

(defn take-events
  "Drain up to `n` events from `buf` (a ring-buffer atom) and
   return them as a vector. The buffer atom is updated in place."
  [buf n]
  (loop [remaining n
         taken (transient [])]
    (if (zero? remaining)
      (do (reset! buf clojure.lang.PersistentQueue/EMPTY)
          (persistent! taken))
      (let [cur @buf]
        (if (empty? cur)
          (do (reset! buf clojure.lang.PersistentQueue/EMPTY)
              (persistent! taken))
          (let [head (peek cur)
                tail (pop cur)]
            (reset! buf tail)
            (recur (dec remaining) (conj! taken head))))))))

(defn aggregate
  "Return the underlying aggregate-sink atom."
  [] aggregate-sink)

(defn- aggregate-sink-fn [ev]
  (let [{:keys [source phase]} ev
        class (:class source)
        method (:method source)
        cm-key (str class "/" method)
        cm-cur (get-in @aggregate-sink [:by-class-method cm-key] {})
        cm-new (update cm-cur phase (fnil inc 0))
        phase-new (update (:by-phase @aggregate-sink)
                          phase (fnil inc 0))]
    (reset! aggregate-sink
            {:by-class-method (assoc (:by-class-method @aggregate-sink)
                                     cm-key cm-new)
             :by-phase        phase-new})))

(defn register-builtins!
  "Register the P1 built-in sinks (:println / :ring-buffer /
   :aggregate). Idempotent."
  []
  (register-sink! :println println-sink)
  (register-sink! :ring-buffer ring-buffer-sink)
  (register-sink! :aggregate aggregate-sink-fn))

(defn register-fn-sink!
  "Convenience: register `sink-fn` under `:fn-sink`. Returns it."
  [sink-fn]
  (register-sink! :fn-sink sink-fn))

(defn dispatch!
  "Route `event` to the named sink. Returns nil on success, throws
   only on an unknown sink name (programmer error, NOT a runtime
   event error). Per-sink try/catch is INSIDE each sink's fn."
  [sink-name event]
  (if-let [s (lookup sink-name)]
    (s event)
    (throw (ex-info (str "no sink registered: " sink-name)
                    {:nihilite/kind :nihilite/no-sink
                     :sink-name sink-name}))))

(register-builtins!)
