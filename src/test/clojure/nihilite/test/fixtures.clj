(ns nihilite.test.fixtures
  "Registry cleanup fixture + free-port helper shared by test namespaces."
  (:require [nihilite.registry :as reg])
  (:import [java.net ServerSocket]))

(defn reg-cleanup
  [f]
  (reg/clear!)
  (try (f) (finally (reg/clear!))))

(def ^:const ^long connect-timeout-ms 2000)
(def ^:const ^long response-timeout-ms 5000)

(defn free-port
  ^long []
  (let [s (ServerSocket. 0)]
    (try (.getLocalPort s)
         (finally (.close s)))))
