(ns nihilite.observers.trace
  "Lightweight per-thread call trace.

   `(trace! opts)` installs hidden subscribers (one for `:entry`,
   one for `:return`+`:throw`) via `subscribe!`. Each bridge
   pushes/pops a per-thread in-flight stack and emits a flat
   `:trace-node` map; the consumer reconstructs the tree via
   `:parent-id`. `:invoke-*` events are not part of this
   surface."
  (:require [clojure.tools.logging :as log]
            [nihilite.observers.subscriber :as sub]
            [nihilite.observers.sinks :as sinks])
  (:import [java.util UUID]))

(defonce ^:private traces (atom {}))

(defn- build-tree-id [spec-id thread-name]
  (str spec-id "|" thread-name "|" (UUID/randomUUID)))

(defn- trace-event
  [{:keys [spec-id thread-name phase args return-value throwable
           start-ns end-ns parent-id depth]}]
  {:kind :trace-node
   :spec-id spec-id
   :thread-name thread-name
   :depth depth
   :parent-id parent-id
   :children '()
   :status phase
   :args args
   :return-value return-value
   :throwable throwable
   :start-ns start-ns
   :end-ns end-ns})

(defn- emit-trace-node! [handler sink ev]
  (try (handler ev)
       (catch Throwable t
         (log/warn t "trace handler threw (sink=" sink ")")))
  (try (sinks/dispatch! sink ev)
       (catch Throwable t
         (log/warn t "trace sink threw (sink=" sink ")"))))

(defn- entry-bridge [trace]
  (let [{:keys [in-flight handler sink]} trace]
    (fn [ev]
      (try
        (let [spec-id     (:spec-id ev)
              thread-name (:thread-name ev)
              in-f        (get @in-flight thread-name)
              parent-id   (when-let [top (peek in-f)]
                            (:node-id top))
              depth       (count in-f)
              node-id     (build-tree-id spec-id thread-name)
              node        {:node-id  node-id
                           :spec-id  spec-id
                           :start-ns (System/nanoTime)}]
          (swap! in-flight update thread-name (fnil conj []) node)
          (emit-trace-node! handler sink
                            (trace-event
                              {:spec-id      spec-id
                               :thread-name  thread-name
                               :phase        :entry
                               :args         (vec (or (:args ev) []))
                               :return-value nil
                               :throwable    nil
                               :start-ns     (:start-ns node)
                               :end-ns       (System/nanoTime)
                               :parent-id    parent-id
                               :depth        depth})))
        (catch Throwable t
          (log/warn t "trace entry-bridge threw"))))))

(defn- exit-bridge [trace]
  (let [{:keys [in-flight handler sink]} trace]
    (fn [ev]
      (try
        (let [spec-id     (:spec-id ev)
              thread-name (:thread-name ev)
              in-f        (get @in-flight thread-name)
              top         (peek in-f)
              parent-id   (when top (:node-id (peek (pop in-f))))
              depth       (max 0 (dec (count in-f)))
              start-ns    (or (some-> top :start-ns) (System/nanoTime))]
          (swap! in-flight update thread-name
                 (fn [stack]
                   (if (and stack (= spec-id (:spec-id (peek stack))))
                     (pop stack)
                     stack)))
          (emit-trace-node! handler sink
                            (trace-event
                              {:spec-id      spec-id
                               :thread-name  thread-name
                               :phase        (:phase ev)
                               :args         (vec (or (:args ev) []))
                               :return-value (:return-value ev)
                               :throwable    (:throwable ev)
                               :start-ns     start-ns
                               :end-ns       (System/nanoTime)
                               :parent-id    parent-id
                               :depth        depth})))
        (catch Throwable t
          (log/warn t "trace exit-bridge threw"))))))

(defn trace!
  "Create lightweight call trace. Options: :selector :sink :handler :max-nodes (1000, max 10000)."
  [{:keys [selector sink handler max-nodes]
    :or {selector {} sink :println handler identity max-nodes 1000}}]
  (when (> max-nodes 10000)
    (throw (ex-info ":max-nodes > 10000"
                    {:nihilite/kind :nihilite/trace-cap-exceeded
                     :max-nodes max-nodes})))
  (let [trace-id (str "trace-" (UUID/randomUUID))
        trace    {:id        trace-id
                  :in-flight (atom {})
                  :handler   handler
                  :sink      sink
                  :max-nodes max-nodes}
        entry-handler (entry-bridge trace)
        exit-handler  (exit-bridge trace)]
    (let [entry-sub (sub/subscribe! entry-handler
                                    {:selector                  selector
                                     :positions                 #{:entry}
                                     :sink                      sink
                                     :silence-match-all-warning true})]
      (let [exit-sub (sub/subscribe! exit-handler
                                     {:selector                  selector
                                      :positions                 #{:return :throw}
                                      :sink                      sink
                                      :silence-match-all-warning true})]
        (swap! traces assoc trace-id
               (assoc trace :entry-sub entry-sub :exit-sub exit-sub))
        trace))))

(defn list-traces []
  (vec (keys @traces)))

(defn stop-trace!
  "Stop a trace by id. Returns true if stopped, false if missing."
  [id]
  (if-let [trace (get @traces id)]
    (do
      (when-let [entry-sub (:entry-sub trace)]
        (try (sub/unsubscribe! (:id entry-sub))
             (catch Throwable _)))
      (when-let [exit-sub (:exit-sub trace)]
        (try (sub/unsubscribe! (:id exit-sub))
             (catch Throwable _)))
      (swap! traces dissoc id)
      true)
    false))