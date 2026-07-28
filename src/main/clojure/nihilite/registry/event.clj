(ns nihilite.registry.event
  "HookEvent construction + per-observer dispatch helper.

   The event record is the single unified value passed to every
   user bridge IFn. Its `:cancelled?` field is `volatile-mutable`
   (declared in `nihilite.registry.spec`); the `cancel!`
   closure built here is the only writer, called from a
   `:cancel`-action observer to flag further dispatchers in the
   same fan-out to skip the rest of the bucket."
  (:require [clojure.tools.logging :as log]
            [nihilite.registry.spec :as rs]
            [nihilite.registry.spec :refer [map->HookEvent]]
            [nihilite.registry.index :as ix])
  (:import (nihilite.registry.spec HookEvent)))

(def ^:private FRAME-SKIP 3)

(defn capture-stack!
  "Capture a stack trace (P2.4 plan §5.3). Returns nil when
   the sample-rate gate fails; otherwise returns a vector of
   `[class-name method-name line-number]` triples, capped at
   `max-depth` frames. Skips the first FRAME-SKIP frames."
  ^java.util.List [^long max-depth ^double sample-rate]
  (when (< (Math/random) (double sample-rate))
    (when-let [frames (.getStackTrace (Thread/currentThread))]
      (let [start (min FRAME-SKIP (alength frames))
            end   (min (alength frames) (+ start (int max-depth)))]
        (vec (for [^StackTraceElement f (java.util.Arrays/copyOfRange
                                          frames start end)]
               [(.getClassName f) (.getMethodName f) (.getLineNumber f)]))))))

(defn ->hook-event
  "Construct a HookEvent from a spec, self, args, and the host
   method's pending return value (or nil at :entry). Phase is
   derived from the spec's :position. The event's `:cancelled?`
   field is a zero-arg closure that reads the cancellation
   cell; `:cancel!` is a single-arg closure that writes it.
   Both are wired to the same per-event AtomicBoolean.

   P2.4: when spec's `:capture-stack?` is true AND `:position`
   is `:entry`, the event's `:stack` field is populated via
   `capture-stack!` honoring `:max-depth` and `:sample-rate`.
   `:return` and `:throw` events always carry `:stack = nil`."
  [spec self args return-value]
  (let [pos (:position spec)
        cell (java.util.concurrent.atomic.AtomicBoolean.)
        cancel-fn   (fn [v] (.set cell (boolean v)))
        cancelled-fn (fn [] (.get cell))
        stack (when (and (:capture-stack? spec) (= pos :entry))
                (capture-stack! (or (:max-depth spec) 32)
                                 (or (:sample-rate spec) 0.01)))]
    (map->HookEvent
      {:spec-id      (:id spec)
       :source       {:class         (or (:source-class spec)
                                          (:target-internal spec))
                      :internal      (:target-internal spec)
                      :method        (:method-name spec)
                      :descriptor    (:source-descriptor spec)
                      :action        (:action spec)
                      :method-key    (:method-key spec)}
       :phase        pos
       :self         self
       :args         (or args (object-array 0))
       :return-value return-value
       :throwable    nil
       :cancelled?   cancelled-fn
       :cancel!      cancel-fn
       :thread-name  (.getName (Thread/currentThread))
       :timestamp-ns (System/nanoTime)
       :sequence     (ix/next-sequence)
       :note         (:note spec)
       :stack        stack})))

(defn dispatch-one!
  "Invoke a single observer IFn with the given event. Per-observer
   try/catch isolation. Returns the IFn's return value (or
   `::no-return` if the IFn threw or was absent). Never
   propagates a throwable to the caller."
  [ifn ev]
  (if (nil? ifn)
    ::no-return
    (try
      (ifn ev)
      (catch Throwable t
        (log/error t "observer threw (id=" (:spec-id ev) ")")
        ::no-return))))
