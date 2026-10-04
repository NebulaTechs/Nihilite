(ns nihilite.test.uninstall-retransform-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.registry.stats :as stats]
            [nihilite.kernel.installer :as installer]
            [nihilite.kernel.agent :as agent]
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
  ;; This namespace used to carry two tests that could not fail. Both are
  ;; replaced by this one, which states the fact they silently relied on.
  ;;
  ;; is-modifiable-class-false-for-final-class wrapped its only assertion in
  ;; (when-let [inst (agent/agent-currentInstrumentation)] ...). The contract
  ;; runner launches a plain JVM, so that is nil, the body never ran, and the
  ;; test reported a pass having asserted nothing. Asking the JVM whether
  ;; java.lang.Math is modifiable is also not this project's behaviour to
  ;; test -- it is the JVM's, and it belongs in a driver that really has an
  ;; Instrumentation.
  ;;
  ;; fabric-retransform-persistence-after-classforName asserted (identical?
  ;; before after) around two Class/forName calls. Verified true here without
  ;; any retransform: the JVM keys loaded classes by (name, loader) and
  ;; retransformClasses replaces the bytes behind an existing Class rather than
  ;; producing a new one, so that assertion held for any implementation,
  ;; including a broken one.
  (is (nil? (agent/agent-currentInstrumentation))
      "no Instrumentation in the contract runner, which is why no test in this
       namespace can assert anything about retransform behaviour")
  (is (identical? (Class/forName "java.lang.String")
                  (Class/forName "java.lang.String"))
      "Class identity survives any number of lookups, which is why comparing
       it across a retransform proves nothing"))

(deftest uninstall-retransform-without-instrumentation-reports-zero
  ;; The reachable half of what the removed test was gesturing at: with no
  ;; Instrumentation there is nothing to retransform, and the count says so
  ;; instead of throwing. This is the path the WARN in uninstall-warn-test
  ;; describes, reached from installer/uninstall directly.
  (is (zero? (installer/uninstall nil "java/lang/String"))
      "installer/uninstall tolerates a nil Instrumentation"))
