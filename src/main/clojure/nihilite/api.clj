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
  (reg/replace-bridge! id new-impl))

(defn register-action!
  [action-key]
  (reg/register-action! action-key))
