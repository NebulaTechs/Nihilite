(ns nihilite.test.agent-worker-once-test
  "Agent.claimWorker is a one-shot CAS: first true, rest false.
   Do not call premain/agentmain here — they bind 7888."
  (:require [clojure.test :refer [deftest is]])
  (:import [nihilite.agent Agent]))

(defn- invoke-static
  [^String method-name]
  (let [m (.getDeclaredMethod Agent method-name (into-array Class []))]
    (.setAccessible m true)
    (.invoke m nil (into-array Object []))))

(defn- claim-worker
  []
  (boolean (invoke-static "claimWorker")))

(defn- start-worker-once
  []
  (invoke-static "startWorkerOnce"))

(defn- agent-worker-threads
  []
  (filterv #(= "nihilite-agent-worker" (.getName ^Thread %))
           (.keySet (Thread/getAllStackTraces))))

(deftest claim-worker-first-true-second-false
  (is (true? (claim-worker))
      "first Agent.claimWorker() wins the CAS")
  (is (false? (claim-worker))
      "second Agent.claimWorker() loses the CAS")
  (let [before (agent-worker-threads)]
    (start-worker-once)
    (is (= before (agent-worker-threads))
        "startWorkerOnce is a no-op after claimWorker already won")))
