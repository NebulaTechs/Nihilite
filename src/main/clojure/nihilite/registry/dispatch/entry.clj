(ns nihilite.registry.dispatch.entry
  "Entry-phase bucket walk (`dispatch-for-spec`)."
  (:require [clojure.tools.logging :as log]
            [nihilite.registry.dispatch :as d]
            [nihilite.registry.dispatch.util :as du]
            [nihilite.registry.event :as ev]
            [nihilite.registry.stats :as stats]))

(defn dispatch-for-spec
  "ByteBuddy Advice entry. Fan out across bucket in registration order. Honours :cancelled?."
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