(ns nihilite.registry.dispatch.invoke
  "Invoke-phase bucket walk (`dispatch-invoke-for-spec`).

   Per D8.1 the FULL per-callsite behavior requires a
   ClassVisitor pass — this fn is the dispatch contract; the
   byte-code side currently fires once per host method (see
   InvokeAdvice). The per-callsite behavior lands in P2.2b."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [nihilite.registry.dispatch :as d]
            [nihilite.registry.dispatch.util :as du]
            [nihilite.registry.event :as ev]
            [nihilite.registry.stats :as stats]))

(defn dispatch-invoke-for-spec
  "P2.2 (plan v2.1 §4.5) `:invoke-*` dispatch helper. Resolves
   the spec, builds a HookEvent tagged with the appropriate
   phase (`:invoke-before` / `:invoke-return` / `:invoke-throw`),
   and fans out across the bucket.

   `:invoke-throw` re-throws the original throwable after
   dispatch returns (D2.4). All other actions are no-ops for
   `:invoke-*` (`:modify`/`:cancel` rejected at install!)."
  [spec-id position self args return-value throwable]
  (try
    (when-let [spec (d/lookup spec-id)]
      (let [phase  (if (keyword? position) position
                    (keyword (str/replace (name position) ":" "")))
            bucket (d/spec-bucket spec)
            ev     (assoc (ev/->hook-event spec self args return-value)
                         :phase phase
                         :throwable throwable)
            cancelled? ((:cancelled? ev))]
        (when bucket
          (loop [remaining bucket]
            (when (and (seq remaining) (not cancelled?))
              (let [s      (first remaining)
                    action (or (:action s) :observe)
                    f      (du/safe-bridge s)]
                (ev/dispatch-one! f ev)
                (stats/bump-fired! (:id s))
                (when (= action :subscriber)
                  (when-let [cb (:cancel! ev)]
                    (cb true))))
              (recur (next remaining)))))))
    (catch Throwable t
      (try (log/error t "registry dispatch-invoke-for-spec failed (id=" spec-id ")")
           (catch Throwable _)))))