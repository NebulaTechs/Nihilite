(ns nihilite.test.agent-worker-once-test
  "Agent.claimWorker is a one-shot CAS: first true, rest false.

   And worker/await-var, which is what makes the redefine dispatcher install
   survive another thread loading the same namespace.

   Do not call premain/agentmain here -- they arm the real ByteBuddy installer."
  (:require [clojure.test :refer [deftest is]]
            ;; loaded, not just resolved: await-var is private, so ns-resolve
            ;; returns nil unless the namespace is actually on the classpath.
            [nihilite.kernel.worker])
  (:import [nihilite.kernel Agent]))

(defn- await-var-fn []
  @(ns-resolve 'nihilite.kernel.worker 'await-var))

(defn- timeout-ms-var []
  (ns-resolve 'nihilite.kernel.worker 'dispatch-ready-timeout-ms))

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

(deftest await-var-returns-the-var-when-it-is-already-bound
  (let [v (await-var-fn)]
    (is (= inc (deref (v "clojure.core" "inc"))))))

(deftest await-var-throws-rather-than-returning-an-unbound-var
  ;; The redefine dispatcher install lost a require race and nothing caught it
  ;; for weeks: RT/var on a namespace another thread was still evaluating
  ;; returns a var rooted at Var$Unbound, invoking it throws, and two
  ;; catch-and-log layers downgraded that to a warning nobody read. The result
  ;; was a live agent whose :redefine hooks silently returned the stub default.
  ;;
  ;; So the contract is that await-var gives up loudly. If someone deletes the
  ;; wait and goes back to a bare RT/var, this fails.
  (let [v (await-var-fn)]
    (with-redefs-fn {(timeout-ms-var) 50}
      (fn []
        (is (thrown? clojure.lang.ExceptionInfo
                     (v "clojure.core" "no-such-var-at-all"))
            "an unbindable var throws instead of coming back unbound")))))
