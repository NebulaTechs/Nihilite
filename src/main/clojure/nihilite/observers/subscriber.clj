(ns nihilite.observers.subscriber
  "Subscriber surface for P1. subscribe! replaces each matched
   spec's bridge IFn with our bridge and sets `:action :subscriber`.
   Per-observer try/catch inside the bridge isolates user code
   from the dispatcher (B1 fix).

   Subscription record (P3.S2): 16-field defrecord capturing identity,
   selector, handler, sink, lifecycle state, counters, cancel-cell,
   and xform runtime. Counters are individual atoms (not a nested
   `:counters` map) so subscriber-status reads them with `(:fired
   sub)` directly. The record's `pr-str` is intentionally kept
   map-literal-compatible — downstream log/eval consumers that
   pattern-match on a plain map continue to work (D9)."
  (:require [clojure.tools.logging :as log]
            [nihilite.registry :as reg]
            [nihilite.observers.selector :as sel]
            [nihilite.observers.sinks :as sinks])
  (:import [java.util UUID]
           [java.util.concurrent.atomic AtomicBoolean]))

(defrecord Subscriber [id selector handler sink name active? created-ns
                       expire-ns fire-target fired exception sink-errors
                       cancelled cancel-cell xform-state xform-out-count])

(defonce ^:private subscriptions (atom {}))

(declare unsubscribe!)

(defn subscribed? [id]
  (boolean (:active? (get @subscriptions id))))

(defn get-subscription [id]
  (get @subscriptions id))

(defn list-ids []
  (sort (filter subscribed? (keys @subscriptions))))

(defonce ^:private match-all-warned? (atom false))

(defn- maybe-warn-match-all [opts]
  (when (and (empty? (:selector opts))
             (not (:silence-match-all-warning opts))
             (not @match-all-warned?))
    (log/warn "subscribe! called without :selector; this subscriber will fire on every HookEvent in the JVM. Suppress with :silence-match-all-warning true.")
    (reset! match-all-warned? true)))

(defn- dispatch-one!
  "Bridge body: run xform + handler + sink for one event.
   Returns nil on cap / exception / no-op; nothing."
  [sub handler xform sink ev sub-id]
  (try
    (let [state         (:xform-state sub)
          history       (conj @state ev)
          _             (reset! state history)
          result        (into [] xform history)
          prev-count    @(:xform-out-count sub)
          new-count     (count result)
          new-output    (when (> new-count prev-count)
                          (nth result (dec new-count)))]
      (reset! (:xform-out-count sub) new-count)
      (when (some? new-output)
        (try (handler new-output)
             (catch Throwable t
               (swap! (:exception sub) inc)
               (log/warn t "subscriber handler threw (id=" sub-id ")")
               nil))
        (try (sinks/dispatch! sink new-output)
             (catch Throwable t
               (swap! (:sink-errors sub) inc)
               (log/warn t "sink threw (sink=" sink " id=" sub-id ")")))))
    (catch Throwable t
      (swap! (:exception sub) inc)
      (log/warn t "subscriber body threw (id=" sub-id ")")
      nil))
  (swap! (:fired sub) inc))

(defn subscribe!
  "Install a subscriber. Returns a Subscriber defrecord.

   For each spec currently matching `:selector`, replaces its
   bridge IFn with our subscriber bridge and sets `:action
   :subscriber`. By default, only specs at `:position :entry`
   are replaced; pass `:positions` to opt into other phases.
   Selector matching is snapshotted at subscribe time (D2.4).
   `:take N` / `:ttl-ms` flip the cancel-cell after the cap;
   subsequent calls return early."
  [handler {:keys [selector sink name take ttl-ms xform positions
                   silence-match-all-warning]
            :or {selector {} sink :println xform (map identity)}}]
  (maybe-warn-match-all {:selector selector
                         :silence-match-all-warning
                         silence-match-all-warning})
  (let [sub-id       (str "sub-" (UUID/randomUUID))
        cancel-cell  (AtomicBoolean.)
        fire-target  (when take (long take))
        expire-ns    (when ttl-ms
                      (+ (System/nanoTime)
                         (* (long ttl-ms) (long 1e6))))
        sub          (->Subscriber
                       sub-id
                       selector
                       handler
                       sink
                       (or name sub-id)
                       true
                       (System/nanoTime)
                       expire-ns
                       fire-target
                       (atom 0)        ; fired
                       (atom 0)        ; exception
                       (atom 0)        ; sink-errors
                       (atom 0)        ; cancelled
                       cancel-cell
                       (atom [])       ; xform-state
                       (atom 0))       ; xform-out-count
        bridge-fn    (fn [ev]
                       (if (or (.get cancel-cell)
                               (and fire-target
                                    (>= @(:fired sub) fire-target))
                               (and expire-ns
                                    (>= (System/nanoTime) expire-ns)))
                         (do
                           (.set cancel-cell true)
                           ;; Synchronous auto-unsubscribe — a (future
                           ;; …) here races with subsequent dispatch
                           ;; calls and lets `subscribed?` observe a
                           ;; stale `:active? true` after cap-hit.
                           ;; unsubscribe! does not re-enter dispatch.
                           (when (:active? sub)
                             (try (unsubscribe! sub-id)
                                  (catch Throwable t
                                    (log/warn t "auto-unsubscribe failed"))))
                           nil)
                         (dispatch-one! sub handler xform sink ev sub-id)))]
    (doseq [spec (sel/select-targets selector)
            :when (or (nil? positions)
                      (contains? positions (:position spec)))]
      (let [spec-id (:id spec)
            spec-map {:id                spec-id
                      :target-internal   (:target-internal spec)
                      :method-name       (:method-name spec)
                      :descriptor        (:source-descriptor spec)
                      :position          (:position spec)
                      :arity             (:arity spec)
                      :action            :subscriber
                      :bridge            bridge-fn
                      :tag               (str "subscriber:" sub-id)
                      :capture-stack?    (boolean (:capture-stack? spec))
                      :source-class      (:source-class spec)
                      :source-descriptor (:source-descriptor spec)
                      :note              (:note spec)}]
        ;; Wave-1 T3 (P1.S3a + S5 sync): subscriber paths choose
        ;; REPLACE (install!) for already-existing specs and
        ;; FRESH (install-fresh!) only when the id is new. This
        ;; avoids HC3 — the prior plan's putIfAbsent would have
        ;; thrown on every bulk install.
        (if (nil? (reg/lookup spec-id))
          (reg/install-fresh! spec-map)
          (reg/install! spec-map))))
    (swap! subscriptions assoc sub-id sub)
    sub))

(defn unsubscribe!
  "Remove a subscription by id. Returns true if removed, false if
   already gone."
  [id]
  (let [sub (get @subscriptions id)]
    (if (nil? sub)
      false
      (do
        (swap! subscriptions dissoc id)
        (let [tag (str "subscriber:" id)]
          (doseq [spec-id (reg/list-ids)
                  :let [s (reg/lookup spec-id)
                        t (:tag s)]
                  :when (and s (= tag t))]
            (reg/uninstall! spec-id)))
        (log/info "subscriber removed:" id)
        true))))
