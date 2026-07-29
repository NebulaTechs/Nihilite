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
  "Invoke HookEvent's :cancel! field if present. Nil-safe; no-op on synthetic events."
  [ev]
  (when-let [cb (:cancel! ev)]
    (cb true)))

(defn run-hook
  "Invoke single observer bridge with event. Per-observer try/catch. Returns ::no-return on throw."
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