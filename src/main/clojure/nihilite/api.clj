(ns nihilite.api
  "Public 4-verb facade over nihilite.registry."
  (:require [nihilite.registry :as reg]))

(defn install! [spec]
  (reg/install! spec))

(defn uninstall! [id]
  (reg/uninstall! id))

(defn lookup [id]
  (reg/lookup id))

(defn list-specs []
  (vec (sort (keys (reg/stats-snapshot)))))

(defn swap-bridge!
  [id new-impl]
  (when-let [old (reg/lookup id)]
    (reg/uninstall! id)
    (reg/install! (assoc old :bridge new-impl))))
