(ns nihilite.registry.dispatch.util
  "Helpers shared by the phase-specific dispatch sub-namespaces
   (`dispatch.entry` / `dispatch.return` / `dispatch.throw` /
   `dispatch.invoke`). No load-time dependencies on the
   dispatch namespaces — breaks the load cycle that would
   otherwise form when each phase requires the dispatch facade
   for these helpers.

   `call-cancel!` and `run-hook` are shared across all four
   phase dispatch files, providing a single helper for the
   cancel! invocation + the per-observer try/catch isolation."
  (:require [clojure.tools.logging :as log]
            [nihilite.registry.stats :as stats])
  (:import (nihilite.registry.spec HookEvent)))

(defn cancelled?-closure
  "Return a 0-arg fn that reads the HookEvent's `:cancelled?`
   field. Used by phase walks to short-circuit the bucket
   between observers."
  [^HookEvent event]
  (let [c (.-cancelled? event)]
    (if (fn? c) c (constantly c))))

(defn safe-bridge
  "Return the spec's bridge IFn if it is a real IFn, else nil.
   Bucket walks skip a spec whose bridge is missing."
  [spec]
  (when (instance? clojure.lang.IFn (:bridge spec))
    ^clojure.lang.IFn (:bridge spec)))

(defn call-cancel!
  "Invoke the HookEvent's `:cancel!` field if present.

   The field is a closure over `AtomicBoolean` (see
   `nihilite.registry.event`); calling it with `true` flips the
   per-event cancel cell so subsequent observers in the same
   fan-out short-circuit.

   History (HC5 fix):
     - entry.clj:38 used `.-cancel!` reflection on a CLOSURE field,
       which is illegal (closures are not Java fields). It compiled
       only because `^HookEvent` type-hint resolved the field at
       compile time without actually invoking reflective dispatch.
     - return.clj:51 used `((:cancel! event))` — a 0-arg call to
       a 1-arg fn, which throws `ArityException` on dispatch.
     - throw.clj:40 and invoke.clj:43 used the correct
       `(when-let [cb (:cancel! ev)] (cb true))` pattern.

   This helper unifies the cancel! invocation across all four
   phase dispatch files (D12). It is nil-safe — events without
   `:cancel!` (e.g. synthetic test events) just no-op."
  [ev]
  (when-let [cb (:cancel! ev)]
    (cb true)))

(defn run-hook
  "Invoke a single observer bridge with the given event, with
   per-observer try/catch isolation. Returns the IFn's return
   value (or `::no-return` if the IFn threw or was absent).

   The dispatcher wiring is `(du/run-hook f event)` which then
   routes the actual IFn call. This indirection lets future
   versions add cross-cutting concerns (e.g. metrics) without
   editing every phase dispatch file.

   The exception-bump behavior was previously in
   `nihilite.registry.event/dispatch-one!`; this fn keeps the
   same shape so the bump wiring stays effective."
  [f ev]
  (if (nil? f)
    ::no-return
    (try
      (f ev)
      (catch Throwable t
        (try (log/error t "observer threw (id=" (:spec-id ev) ")")
             (catch Throwable _))
        (stats/bump-exception! (:spec-id ev))
        ::no-return))))