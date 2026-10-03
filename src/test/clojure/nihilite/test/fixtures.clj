(ns nihilite.test.fixtures
  "Registry cleanup fixture shared by test namespaces."
  (:require [nihilite.registry :as reg]))

(defn reg-cleanup
  [f]
  (reg/clear!)
  (try (f) (finally (reg/clear!))))
