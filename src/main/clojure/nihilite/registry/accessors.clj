(ns nihilite.registry.accessors
  "P0 dual-shape context accessors.

   User bridge IFns may receive either a legacy HookContext
   (the Bridge.fire worker shape from before P0) or a HookEvent
   (the unified P0 shape). These accessors accept both:
   HookContext fields map 1:1 onto the legacy IFn contract;
   HookEvent carries the same field names plus extras
   (`:source`, `:thread-name`, `:timestamp-ns`, `:sequence`,
   `:cancel!`, `:throwable`). We re-project the overlapping
   fields into a fresh HookContext for legacy IFns that call
   `ctx-cancel!` so that continue to work."
  (:require [nihilite.registry.spec :as rs]
            [nihilite.registry.spec :refer [map->HookContext]])
  (:import (nihilite.registry.spec HookContext HookEvent)))

(defn- ->ctx
  "Coerce a user-supplied object into a HookContext by
   field-name mapping. HookEvent has the same field names as
   HookContext plus extras; we re-project the overlapping
   fields into a fresh HookContext."
  [x]
  (cond
    (instance? HookContext x) x
    (instance? HookEvent x)
    (map->HookContext
      {:hookId      (.-spec-id ^HookEvent x)
       :self        (.-self ^HookEvent x)
       :args        (.-args ^HookEvent x)
       :phase       (.-phase ^HookEvent x)
       :returnValue (.-return-value ^HookEvent x)
       :cancelled   ((.-cancelled? ^HookEvent x))})
    :else nil))

(defn ctx-self
  "The receiver of the instrumented call, or nil."
  [x]
  (when-let [c (->ctx x)]
    (:self c)))

(defn ctx-arg
  "The `n`-th argument passed to the instrumented method, or nil
   if out of range."
  [x n]
  (when-let [c (->ctx x)]
    (let [args (.-args ^HookContext c)]
      (when (and args (>= n 0) (< n (alength args)))
        (aget args (int n))))))

(defn ctx-argc
  "Number of arguments captured in ctx. Always ≥ 0."
  [x]
  (when-let [c (->ctx x)]
    (let [args (.-args ^HookContext c)]
      (if args (alength args) 0))))

(defn ctx-return
  "The return value (populated only at :return phase; nil at
   :entry)."
  [x]
  (when-let [c (->ctx x)]
    (.-returnValue ^HookContext c)))

(defn ctx-phase
  "The phase keyword."
  [x]
  (when-let [c (->ctx x)]
    (.-phase ^HookContext c)))

(defn ctx-cancel!
  "Mark ctx as cancelled (P0 observer-level veto). Accepts both
   HookContext and HookEvent shapes; for HookEvent the
   per-event `:cancelled?` is mutated through the same
   `cancel!` closure that the event carries."
  [x value]
  (cond
    (instance? HookContext x)
    (set! (.-cancelled ^HookContext x) (boolean value))

    (instance? HookEvent x)
    (let [ev ^HookEvent x]
      (when-let [c (.-cancel! ev)]
        (c (boolean value))))))

(defn ctx-cancelled?
  "True if ctx has been cancelled."
  [x]
  (cond
    (instance? HookContext x) (.-cancelled ^HookContext x)
    (instance? HookEvent x)
    (let [c (.-cancelled? ^HookEvent x)]
      (if (fn? c) (boolean (c)) (boolean c)))
    :else false))

(defn position
  "Resolve the `:position` value of a HookSpec / Subscriber /
   HookContext / HookEvent, accepting both keyword and string
   access keys at the call site.

   Caller code is sometimes written as (sm :position) and
   sometimes as (sm 'position'). This accessor abstracts
   that.

   This dual-mode accessor is shipped as the 30-day default if no
   operator signoff arrives — backward-compat with mixed-access
   callers without forcing a uniform choice.

   Returns the position keyword (one of :entry, :return, :throw,
   :subscriber, :invoke-before, etc.), or nil if no :position
   field is present."
  [m]
  (or (and (instance? HookEvent m) (.-phase ^HookEvent m))
      (and (map? m)
           (or (get m "position") (:position m)))
      nil))
