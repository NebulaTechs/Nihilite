(ns nihilite.observers.sinks
  "Named sinks for the subscriber surface.

   A sink is a 1-arg IFn `(fn [event] …)`. Sink errors are
   caught INSIDE the subscriber bridge — never propagate to the
   dispatcher."
  (:require [clojure.tools.logging :as log]))

(defonce ^:private sinks
  (atom {}))

(defn register-sink!
  "Register (or replace) a sink under name. Sink is 1-arg IFn. Returns registered IFn."
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
  "DEPRECATED. Races with writer; use drain-events. Retained for backward compat in tests."
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

(defn drain-events
  "Atomically drain up to n events from ring-buffer. Uses swap! with pure swap-fn (no race)."
  ^java.util.List [^clojure.lang.IAtom buf ^long n]
  (if (<= n 0)
    []
    (let [taken  (atom [])]
      (swap! buf
             (fn [q]
               (let [c      (count q)
                     take-n (min n c)
                     head   (vec (take take-n q))
                     tail   (drop take-n q)]
                 (reset! taken head)
                 tail)))
      (or @taken []))))

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
  "Route event to named sink. Returns nil on success; throws on unknown sink name."
  [sink-name event]
  (if-let [s (lookup sink-name)]
    (s event)
    (throw (ex-info (str "no sink registered: " sink-name)
                    {:nihilite/kind :nihilite/no-sink
                     :sink-name sink-name}))))

(register-builtins!)
