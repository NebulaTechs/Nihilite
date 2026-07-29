(ns nihilite.registry.dispatch.throw
  "Throw-phase bucket walk (`dispatch-throw-for-spec`)."
  (:require [clojure.tools.logging :as log]
            [nihilite.registry.dispatch :as d]
            [nihilite.registry.dispatch.util :as du]
            [nihilite.registry.event :as ev]
            [nihilite.registry.stats :as stats]))

(defn dispatch-throw-for-spec
  "ByteBuddy OnMethodExit-onThrowable. Fan out across bucket. Honours :cancelled?."
  [spec-id self args throwable]
  (try
    (when-let [spec (d/lookup spec-id)]
      (let [bucket (d/spec-bucket spec)
            event  (assoc (ev/->hook-event spec self args nil)
                          :throwable throwable)
            cancelled? ((:cancelled? event))]
        (when bucket
          (loop [remaining bucket]
            (when (and (seq remaining) (not cancelled?))
              (let [s      (first remaining)
                    action (or (:action s) :observe)
                    f      (du/safe-bridge s)]
                (ev/dispatch-one! f event)
                (stats/bump-fired! (:id s))
                (when (= action :subscriber)
                  (when-let [cb (:cancel! event)]
                    (cb true))))
              (recur (next remaining)))))))
    (catch Throwable t
      (try (log/error t "registry dispatch-throw-for-spec failed (id=" spec-id ")")
           (catch Throwable _)))))