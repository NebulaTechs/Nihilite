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

(defn- current-uninstaller
  "The uninstaller the registry currently holds, read through its private var.

   Needed because fx/reg-cleanup calls reg/clear!, which never touches the
   uninstaller, so a substitution made by a test would otherwise leak into
   every namespace that runs after it in the same JVM."
  []
  (when-let [the-var (ns-resolve 'nihilite.builder.registry 'uninstaller-ref)]
    (deref @the-var)))

(deftest failed-uninstall-still-writes-a-consistent-status
  ;; registry/uninstall! removes the spec from by-id BEFORE it retransforms,
  ;; because hook-type-matches? (transformer.clj:71-77) reads that registry as
  ;; its input and returns false only for a target whose bucket is empty --
  ;; leaving the spec in place would re-weave it and uninstall would silently
  ;; do nothing. That ordering is deliberate, not a slip.
  ;;
  ;; What the ordering used to cost: mark-uninstalled! (registry.clj:337-342)
  ;; is the only writer of :registered?, and it sat AFTER the fallible
  ;; uninstaller call, so a failed uninstall left :registered? true -- with a
  ;; stale :woven-count and four runtime counters already zeroed by
  ;; stats/remove-stats -- for a spec that lookup no longer returned.
  ;; Permanently, until something reinstalled over the id.
  (reg/install! (entry-spec "failed-uninstall"))
  (let [saved (current-uninstaller)]
    (try
      (reg/register-uninstaller!
       (fn [_ _] (throw (ex-info "retransform exploded" {}))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (reg/uninstall! "failed-uninstall"))
          "a failed retransform propagates; it is not logged and swallowed")
      (let [s (reg/install-status! "failed-uninstall")]
        (is (nil? (reg/lookup "failed-uninstall"))
            "the spec was removed before the retransform, so it really is gone")
        (is (false? (:registered? s))
            "the status must not keep claiming a spec lookup no longer returns")
        (is (zero? (:woven-count s))
            "the woven-count from the attempt that failed must not survive as if it succeeded")
        (is (some? (:last-error s))
            "and the failure is recorded rather than lost"))
      (finally
        (reg/register-uninstaller! saved)))))
