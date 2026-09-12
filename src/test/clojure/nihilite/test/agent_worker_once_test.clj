(ns nihilite.test.agent-worker-once-test
  "Agent.claimWorker is a one-shot CAS: first true, rest false.
   Do not call premain/agentmain here — they bind 7888."
  (:require [clojure.test :refer [deftest is]])
  (:import [nihilite.kernel Agent]))

(defn- invoke-static
  [^String method-name]
  (let [m (.getDeclaredMethod Agent method-name (into-array Class []))]
    (.setAccessible m true)
    (.invoke m nil (into-array Object []))))

(defn- claim-worker
  []
  (try
    (boolean (invoke-static "claimWorker"))
    (catch Throwable _
      ;; claimWorker may not be available if the Agent class was loaded
      ;; before the AOT class generation. Fall back to the JVM-side
      ;; method on a freshly resolved class.
      (let [fresh (.getDeclaredMethod (Class/forName "nihilite.kernel.Agent") "claimWorker"
                                      (into-array Class []))]
        (.setAccessible fresh true)
        (boolean (.invoke fresh nil (into-array Object [])))))))

(defn- start-worker-once
  []
  (try (invoke-static "premain")
       (catch Throwable _
         (let [m (.getDeclaredMethod Agent "premain" (into-array Class [String java.lang.instrument.Instrumentation]))]
           (.setAccessible m true)
           (.invoke m nil (into-array Object [nil nil]))))))

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
