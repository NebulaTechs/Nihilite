(ns nihilite.test.capability-test
  "Tests for the read-only capability report.

   The report answers a question the rest of the API cannot: what this JVM
   can do, and whether the agent is actually armed. A hook reporting
   :woven-count 0 and :pending? true looks the same whether the agent is
   missing, the target class is not loaded, or the JVM does not support
   redefinition, so the report has to say which.

   The property that makes it trustworthy is that it is READ-ONLY. A
   diagnostic that installs an agent or attaches to the running JVM gives
   an answer about a different JVM than the one asked about, and it can
   make the thing it is diagnosing worse. So the tests here check that
   asking the question changes nothing."
  (:require [clojure.test :refer [deftest is]]
            [nihilite.builder.api :as api]
            [nihilite.builder.capability :as cap]
            [nihilite.builder.registry :as reg]))

(deftest report-has-the-documented-shape
  (let [r (cap/capability-report)]
    (doseq [k [:instrumentation-present?
                :entry-point
                :redefine-classes-supported?
                :retransform-classes-supported?
                :java-version
                :java-vendor
                :vm-name
                :os-name
                :os-arch
                :java-instrument-module-present?
                :jdk-attach-present?
                :attach-provider-count
                :relevant-jvm-arguments
                :detail
                :remediation]]
      (is (contains? r k) (str "the report must carry " k)))
    (is (string? (:detail r)))
    (is (or (nil? (:remediation r)) (string? (:remediation r))))))

(deftest report-without-an-agent-says-so
  ;; The contract suite runs without an agent, so the report has to be
  ;; able to say that, and it has to say what to do about it rather than
  ;; leaving the reader to guess.
  (let [r (cap/capability-report)]
    (is (false? (:instrumentation-present? r)))
    (is (nil? (:entry-point r)))
    (is (nil? (:redefine-classes-supported? r)))
    (is (string? (:detail r)))
    (is (some? (:remediation r))
        "an unarmed agent must come with a remediation, not just a fact")))

(deftest report-is-read-only
  ;; Asking the question must not change the answer to it. The registry's
  ;; Instrumentation holder is the observable that matters: if the report
  ;; installed an agent, this would go from nil to an Instrumentation.
  (let [before @reg/instrumentation-ref
        r1 (cap/capability-report)
        after @reg/instrumentation-ref
        r2 (cap/capability-report)]
    (is (= before after)
        "the report must not register an Instrumentation")
    (is (= (:instrumentation-present? r1) (:instrumentation-present? r2))
        "two reports of an unchanged JVM must agree")
    (is (= (:detail r1) (:detail r2)))
    (is (= (:remediation r1) (:remediation r2)))))

(deftest report-agrees-with-the-jvm
  ;; The report quotes the JVM's own facts. If it paraphrased them, it
  ;; would be reporting on itself rather than on the JVM.
  (let [r (cap/capability-report)]
    (is (= (System/getProperty "java.version") (:java-version r)))
    (is (= (System/getProperty "java.vendor") (:java-vendor r)))
    (is (= (System/getProperty "java.vm.name") (:vm-name r)))
    (is (= (System/getProperty "os.name") (:os-name r)))
    (is (= (System/getProperty "os.arch") (:os-arch r)))))

(deftest api-exposes-the-report
  ;; The public surface has to reach it, or it is a report nobody can ask
  ;; for. api/capability-report and capability/capability-report have to
  ;; be the same thing.
  (is (= (cap/capability-report) (api/capability-report))))

(deftest report-only-touches-the-class-it-probes-for
  ;; The report answers questions about the JVM by asking the JVM, and one
  ;; of those questions is whether com.sun.tools.attach is present. Asking
  ;; that question resolves the class -- with initialize=false, so its static
  ;; initialiser does not run -- and that is the only class the report
  ;; touches. It must not resolve anything else, and in particular it must
  ;; not resolve a hook's target, because a hook installed to observe a
  ;; class-loading path re-enters its own advice when the target class is
  ;; loaded by a report.
  (let [probe "nihilite.test.capability_test.NeverReferenced"]
    (is (thrown? ClassNotFoundException
                  (Class/forName probe false
                                 (ClassLoader/getSystemClassLoader)))
        "the probe class must not be resolvable before the test")
    (dotimes [_ 5] (cap/capability-report))
    (is (thrown? ClassNotFoundException
                  (Class/forName probe false
                                 (ClassLoader/getSystemClassLoader)))
        "the report must not have resolved the probe class")))
