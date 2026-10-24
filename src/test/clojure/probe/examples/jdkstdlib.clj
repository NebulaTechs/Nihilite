(ns probe.examples.jdkstdlib
  "Deterministic verification probe for the jdkstdlib example.

   The example is loaded by absolute path before this probe, so its namespace
   is resolved at runtime instead of being required from the classpath."
  (:require [nihilite.builder.api :as api]))

(def ^:private example-namespace-symbol 'examples.jdkstdlib.init)
(def ^:private hook-id "fos-write")
(def ^:private probe-ran? (atom false))

(defn- resolve-example-var [sym]
  (when-let [example-ns (find-ns example-namespace-symbol)]
    (ns-resolve example-ns sym)))

(defn- check! [condition message data]
  (when-not condition
    (throw (ex-info message data))))

(defn- numeric-delta [before after]
  (when (and (number? before) (number? after))
    (- after before)))

(defn- write-once! [file payload]
  (with-open [^java.io.FileOutputStream stream (java.io.FileOutputStream. file)]
    (.write stream payload 0 (alength payload))))

(defn -main [& _]
  (when (compare-and-set! probe-ran? false true)
    (let [total-writes-var (resolve-example-var 'total-writes)
          install-status-var (resolve-example-var 'status)
          spec-ids (api/list-specs)]
      (check! (and total-writes-var install-status-var)
              "the jdkstdlib example namespace is not loaded with its public functions"
              {:namespace example-namespace-symbol
               :total-writes-var total-writes-var
               :install-status-var install-status-var})
      (check! (some #(= hook-id %) spec-ids)
              "the jdkstdlib example did not install its hook"
              {:hook-id hook-id :spec-ids spec-ids})
      (let [total-writes (deref total-writes-var)
            install-status (deref install-status-var)
            baseline-count (total-writes)
            baseline-status (install-status)
            payload (.getBytes "nihilite" "UTF-8")
            file (java.io.File/createTempFile "nihilite-jdkstdlib-" ".bin")]
        (try
          (write-once! file payload)
          (let [after-count (total-writes)
                after-status (install-status)
                count-delta (numeric-delta baseline-count after-count)
                fired-delta (numeric-delta (:fired baseline-status)
                                           (:fired after-status))
                exceptions-delta (numeric-delta (:exceptions baseline-status)
                                                 (:exceptions after-status))
                woven-count (:woven-count after-status)
                observed-bytes (java.nio.file.Files/readAllBytes (.toPath file))]
            (check! (and (number? count-delta) (= 1 count-delta))
                    "the jdkstdlib write counter did not advance by exactly one"
                    {:baseline baseline-count :actual after-count})
            (check! (and (number? fired-delta) (pos? fired-delta))
                    "the jdkstdlib hook did not fire"
                    {:baseline (:fired baseline-status)
                     :actual (:fired after-status)})
            (check! (and (number? woven-count) (pos? woven-count))
                    "the jdkstdlib hook was not woven"
                    {:woven-count woven-count})
            (check! (false? (:pending? after-status))
                    "the jdkstdlib hook is still pending"
                    {:pending? (:pending? after-status)})
            (check! (and (number? exceptions-delta) (zero? exceptions-delta))
                    "the jdkstdlib hook recorded an exception"
                    {:baseline (:exceptions baseline-status)
                     :actual (:exceptions after-status)})
            (check! (true? (:registered? after-status))
                    "the jdkstdlib hook is not registered"
                    {:registered? (:registered? after-status)})
            (check! (nil? (:last-error after-status))
                    "the jdkstdlib hook has an installation error"
                    {:last-error (:last-error after-status)})
            (check! (java.util.Arrays/equals payload observed-bytes)
                    "the temporary file did not contain the written payload"
                    {:expected-length (alength payload)
                     :actual-length (alength observed-bytes)})
            (println "nihilite:example-pass jdkstdlib")
            (flush))
          (finally
            (.delete file)))))))

(-main)
