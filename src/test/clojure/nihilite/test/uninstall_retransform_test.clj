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
  (let [target "java/lang/String"
        inst   (nihilite.agent.Agent/currentInstrumentation)
        before (Class/forName "java.lang.String" false (ClassLoader/getSystemClassLoader))
        _      (nihilite.hooks.HookInstaller/uninstall inst target)
        after  (Class/forName "java.lang.String" false (ClassLoader/getSystemClassLoader))]
    (is (identical? before after)
        "system-CL loadClass returns the same Class<?> instance after retransform")))

(deftest is-modifiable-class-false-for-final-class
  (when-let [inst (nihilite.agent.Agent/currentInstrumentation)]
    (is (false? (.isModifiableClass inst (Class/forName "java.lang.Math")))
        "java.lang.Math must be unmodifiable in a stock JVM")))
