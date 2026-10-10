(ns nihilite.test.uninstall-retransform-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.builder.registry :as reg]
            [nihilite.builder.registry.stats :as stats]
            [nihilite.trainer.installer :as installer]
            [nihilite.trainer.agent :as agent]
            [nihilite.test.fixtures :as fx]))

(defn- entry-spec [id]
  {:id id
   :target-internal "java/lang/String"
   :method-name "length"
   :descriptor "()I"
   :position :entry
   :action :observe
   :bridge (fn [_] nil)})

(use-fixtures :each fx/reg-cleanup)

(deftest uninstall-removes-spec
  (reg/install! (entry-spec "removes-test"))
  (is (true? (reg/uninstall! "removes-test")))
  (is (nil? (reg/lookup "removes-test"))))

(deftest uninstall-missing-spec-returns-nil
  (is (nil? (reg/uninstall! "never-installed"))))

(deftest uninstall-clears-stats
  (reg/install! (entry-spec "stats-test"))
  (is (contains? (stats/stats-snapshot) "stats-test"))
  (reg/uninstall! "stats-test")
  (is (not (contains? (stats/stats-snapshot) "stats-test"))))

(deftest there-is-no-instrumentation-here
  (is (nil? (agent/agent-currentInstrumentation))
      "no Instrumentation in the contract runner, which is why no test in this
       namespace can assert anything about retransform behaviour")
  (is (identical? (Class/forName "java.lang.String")
                  (Class/forName "java.lang.String"))
      "Class identity survives any number of lookups, which is why comparing
       it across a retransform proves nothing"))

(deftest uninstall-retransform-without-instrumentation-reports-zero
  ;; With no Instrumentation there is nothing to retransform, and the count
  ;; says so instead of throwing. This is the path the WARN in
  ;; uninstall-warn-test describes, reached from installer/uninstall directly.
  (is (zero? (installer/uninstall nil "java/lang/String"))
      "installer/uninstall tolerates a nil Instrumentation"))
