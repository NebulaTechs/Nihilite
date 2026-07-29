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
  "ByteBuddy OnMethodExit. Fan out across bucket. Returns host method's return value."
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