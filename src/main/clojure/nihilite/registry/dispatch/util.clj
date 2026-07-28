(ns nihilite.registry.dispatch.util
  "Helpers shared by the phase-specific dispatch sub-namespaces
   (`dispatch.entry` / `dispatch.return` / `dispatch.throw` /
   `dispatch.invoke`). No load-time dependencies on the
   dispatch namespaces — breaks the load cycle that would
   otherwise form when each phase requires the dispatch facade
   for these helpers."
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