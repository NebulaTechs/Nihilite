(ns nihilite.registry.dispatch.entry
  "Entry-phase bucket walk (`dispatch-for-spec`)."
  (:require [clojure.tools.logging :as log]
            [nihilite.registry.dispatch :as d]
            [nihilite.registry.dispatch.util :as du]
            [nihilite.registry.event :as ev]
            [nihilite.registry.stats :as stats]))

(defn dispatch-for-spec
  "ByteBuddy Advice entry-point helper. Given a resolved spec-id,
   look up the spec and any sibling specs sharing the same
   `:method-key`, build a single HookEvent, and fan out across
   the bucket in registration order. Per-observer try/catch
   isolation; observer throwables never escape. Honours
   `:cancelled?` — once any observer sets it, remaining
   observers in the bucket are skipped. Returns nil.

   P1 (D2.2): `:action :subscriber` specs short-circuit the
   bucket after their bridge returns — the xform IS the
   dispatch logic, not one observer in a multi-observer chain.

   Wave-1 T7 (HC14 / HC15): `(.-cancel! ^HookEvent event)` was
   a misleading reflection — `:cancel!` is a closure over an
   `AtomicBoolean`, not a Java field. The `^HookEvent` type hint
   just resolved the field at compile time without actually
   invoking reflective dispatch, so the bug shipped as
   functionally-correct only because no one exercised this path
   on a non-cancelled event. Replaced with `(du/call-cancel!
   event)` (D12 helper)."
  [spec-id self args]
  (try
    (when-let [spec (d/lookup spec-id)]
      (let [bucket         (d/spec-bucket spec)
            event          (ev/->hook-event spec self args nil)
            cancelled?-fn  (du/cancelled?-closure event)]
        (when bucket
          (loop [remaining bucket]
            (when (and (seq remaining)
                       (not (cancelled?-fn)))
              (let [s      (first remaining)
                    action (or (:action s) :observe)
                    f      (du/safe-bridge s)]
                (ev/dispatch-one! f event)
                (stats/bump-fired! (:id s))
                (when (= action :subscriber)
                  (du/call-cancel! event)))
              (recur (next remaining)))))))
    (catch Throwable t
      (try (log/error t "registry dispatch-for-spec failed (id=" spec-id ")")
           (catch Throwable _)))))