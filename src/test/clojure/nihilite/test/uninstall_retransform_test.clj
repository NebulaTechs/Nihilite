(ns nihilite.test.uninstall-retransform-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.registry :as reg]))

(defn- entry-spec [id]
  {:id id
   :target-internal "java/lang/String"
   :method-name "length"
   :descriptor "()I"
   :position :entry
   :action :observe
   :bridge (fn [_] nil)})

(use-fixtures :each
  (fn [f]
    (reg/clear!)
    (try (f) (finally (reg/clear!)))))

(deftest uninstall-removes-spec
  (reg/install! (entry-spec "removes-test"))
  (is (true? (reg/uninstall! "removes-test")))
  (is (nil? (reg/lookup "removes-test"))))

(deftest uninstall-missing-spec-returns-nil
  (is (nil? (reg/uninstall! "never-installed"))))

(deftest uninstall-clears-stats
  (reg/install! (entry-spec "stats-test"))
  (is (contains? (reg/stats-snapshot) "stats-test"))
  (reg/uninstall! "stats-test")
  (is (not (contains? (reg/stats-snapshot) "stats-test"))))

(deftest fabric-retransform-persistence-after-classforName
  ;; R4 verification: HookInstaller.uninstall walks getAllLoadedClasses,
  ;; retransforms matching ones. With system classloader loaded class
  ;; (deterministic fallback when MC classpath is absent), the
  ;; retransformClasses call must NOT throw, AND post-call
  ;; (Class/forName ... getSystemClassLoader) must return the
  ;; retransformed definition.
  (let [target "java/lang/String"
        inst   (nihilite.agent.Agent/currentInstrumentation)
        before (Class/forName "java.lang.String" false (ClassLoader/getSystemClassLoader))
        _      (nihilite.hooks.HookInstaller/uninstall inst target)
        after  (Class/forName "java.lang.String" false (ClassLoader/getSystemClassLoader))]
    (is (identical? before after)
        "system-CL loadClass returns the same Class<?> instance after retransform")))

(deftest is-modifiable-class-false-for-final-class
  ;; A1 byteman borrow: pre-check `inst.isModifiableClass` (byteman
  ;; Retransformer.java:295). Requires -javaagent (Instrumentation
  ;; registered); skipped in bare clojureContractTest.
  (when-let [inst (nihilite.agent.Agent/currentInstrumentation)]
    (is (false? (.isModifiableClass inst (Class/forName "java.lang.Math")))
        "java.lang.Math must be unmodifiable in a stock JVM")))
