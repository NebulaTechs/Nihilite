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
  "The n-th argument passed to the instrumented method, or nil if out of range."
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
  "Return value (populated only at :return phase; nil at :entry)."
  [x]
  (when-let [c (->ctx x)]
    (.-returnValue ^HookContext c)))

(defn ctx-phase
  "The phase keyword."
  [x]
  (when-let [c (->ctx x)]
    (.-phase ^HookContext c)))

(defn ctx-cancel!
  "Mark ctx as cancelled (P0 observer-level veto). Accepts HookContext and HookEvent."
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
  "Resolve :position from HookSpec/Subscriber/HookContext/HookEvent. Accepts kw or string keys."
  [m]
  (or (and (instance? HookEvent m) (.-phase ^HookEvent m))
      (and (map? m)
           (or (get m "position") (:position m)))
      nil))
