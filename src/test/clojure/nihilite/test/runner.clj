(ns nihilite.test.runner
  "Contract runner: pure clojure.test, exit status from accumulated counters."
  (:require [clojure.test])
  (:import [java.util.logging LogManager ConsoleHandler SimpleFormatter]))

(defn- sync-logging!
  []
  (let [m (LogManager/getLogManager)]
    (doseq [^String name (enumeration-seq (.getLoggerNames m))
            :let [h (.getLogger m name)]
            :when h
            ^ConsoleHandler c (filter #(instance? ConsoleHandler %) (.getHandlers h))]
      (doto c
        (.setFormatter (SimpleFormatter.))
        (.setLevel java.util.logging.Level/WARNING)))))

(def ^:const TEST_NAMESPACES
  ["nihilite.test.registry-atomic-install-test"
   "nihilite.test.pointcut-matchers-test"
   "nihilite.test.custom-action-test"
   "nihilite.test.dispatch-return-cancel-test"
   "nihilite.test.install-redefine-reject-test"
   "nihilite.test.dispatch-exception-test"
   "nihilite.test.dispatch-modified-test"
   "nihilite.test.dispatch-common-test"
   "nihilite.test.dispatch-entry-cancel-test"
   "nihilite.test.transport-timeout-test"
   "nihilite.test.position-accessor-test"
   "nihilite.test.attach-test"
   "nihilite.test.agent-worker-once-test"
   "nihilite.test.transport-bencode-roundtrip-test"
   "nihilite.test.version-resolution-test"
   "nihilite.test.uninstall-retransform-test"
   "nihilite.test.api-facade-test"
   "nihilite.test.swap-bridge-test"])

(defn- safe-deref
  [r]
  (if r @r {}))

(defn- run-one
  [ns-sym]
  (try
    (require ns-sym)
    (let [r (clojure.test/test-ns ns-sym)]
      {:counters (if (instance? clojure.lang.IRef r)
                   (safe-deref r)
                   r)
       :throwable nil})
    (catch Throwable t
      {:counters nil :throwable t})))

(defn -main
  [& _args]
  (sync-logging!)
  (let [loaded (atom [])
        load-failures (atom [])
        agg (atom {:pass 0 :fail 0 :error 0})]
    (binding [*out* *err*]
      (println "[nihilite.test.runner] discovered" (count TEST_NAMESPACES)
               "ns(s):" (vec TEST_NAMESPACES))
      (flush))
    (try
      (doseq [ns-sym (map symbol TEST_NAMESPACES)]
        (let [{:keys [counters throwable]} (run-one ns-sym)]
          (if throwable
            (do
              (swap! load-failures conj
                     {:ns (str ns-sym)
                      :throwable-class (.getName (class throwable))
                      :message (.getMessage throwable)})
              (binding [*out* *err*]
                (println "[nihilite.test.runner] load/run failure:" ns-sym
                         (.getMessage throwable))
                (flush)))
            (do
              (swap! loaded conj ns-sym)
              (swap! agg
                     (fn [{:keys [pass fail error] :as cur}]
                       (assoc cur
                              :pass  (+ pass  (:pass counters 0))
                              :fail  (+ fail  (:fail counters 0))
                              :error (+ error (:error counters 0)))))))))
      (let [{:keys [pass fail error]} @agg
            load-fail-bias (* (count @load-failures) 1000)
            total-fail (+ fail error load-fail-bias)]
        (println "[nihilite.test.runner] summary: pass=" pass
                 "fail=" fail "error=" error
                 "load-failures=" (count @load-failures)
                 "loaded=" (vec @loaded))
        (when (pos? total-fail)
          (binding [*out* *err*]
            (println "[nihilite.test.runner] FAIL — non-zero exit pending")
            (flush)))
        (System/exit (if (zero? total-fail) 0 1)))
      (catch Throwable t
        (binding [*out* *err*]
          (println "[nihilite.test.runner] CRASH:" (class t) (.getMessage t))
          (.printStackTrace t)
          (flush))
        (System/exit 1)))))
