(ns nihilite.registry.dispatch.return
  "Return-phase bucket walk (`dispatch-return-for-spec`).
   Aggregation rule:
     :observe / :cancel / :subscriber  return values are ignored
     :modify                            the first non-nil return
                                        value wins; subsequent
                                        :modify returns ignored."
  (:require [clojure.tools.logging :as log]
            [nihilite.registry.dispatch :as d]
            [nihilite.registry.dispatch.util :as du]
            [nihilite.registry.event :as ev]
            [nihilite.registry.stats :as stats]))

(defn dispatch-return-for-spec
  "ByteBuddy Advice OnMethodExit helper. Builds a HookEvent
   tagged :return with `current-return` already populated, then
   fans out across the bucket. Returns the value the host
   method should produce (typed per the host method's declared
   return type; mismatches throw ClassCastException at JVM level
   via the DynamicAssigner + verifier, NOT inside the advice).

    P1 (D2.2): `:subscriber` short-circuits the bucket after
    the bridge returns. `stats/bump-fired!` is recorded for
    every spec whose bridge runs.

    Wave-1 T2b (P1.S2a.2): `stats/bump-modified!` is recorded for
    each `:modify` spec whose non-nil return value is accepted
    as the new winner. Counts here match the actual observable
    modify-wins rate. A `:modify` whose bridge returns nil
    (no-op) is NOT counted, per the
    `(and (= action :modify) (nil? rv)) nil` cond branch.

    Wave-1 T7 (HC5 fix): `((:cancel! event))` was a 0-arg call to
    a 1-arg fn — ArityException at runtime. Replaced with
    `(du/call-cancel! event)` (D12 helper).

   P2.4 (plan v2.1 §6): honours `(:cancelled? event)` between
   observers in the bucket — once any observer flips it,
   remaining observers are skipped."
  [spec-id self args current-return]
  (try
    (if-let [spec (d/lookup spec-id)]
      (let [bucket   (d/spec-bucket spec)
            event    (ev/->hook-event spec self args current-return)
            decided? (atom false)
            winner   (atom current-return)]
        (if (seq bucket)
          (do
            (loop [remaining bucket]
              (when (and (seq remaining)
                         (not ((:cancelled? event))))
                (let [s      (first remaining)
                      f      (du/safe-bridge s)
                      action (or (:action s) :observe)
                      rv     (ev/dispatch-one! f event)]
                  (stats/bump-fired! (:id s))
                  (cond
                    (or @decided? (= action :observe) (= action :cancel))
                    nil

(= action :subscriber)
                     (du/call-cancel! event)

                    (and (= action :modify) (nil? rv))
                    nil

                     :else
                     (do (reset! winner rv)
                         (reset! decided? true)
                         (stats/bump-modified! (:id s))))
                  (recur (next remaining)))))
            @winner)
          current-return))
      current-return)
    (catch Throwable t
      (try (log/error t "registry dispatch-return-for-spec failed (id=" spec-id ")")
           (catch Throwable _))
      current-return)))