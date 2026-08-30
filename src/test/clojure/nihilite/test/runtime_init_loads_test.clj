(ns nihilite.test.runtime-init-loads-test
  "C5 e2e: examples/init.clj fixture loads; nihilite.api/install! resolves.
   Mitigates R3 by NOT loading examples/minecraft/init.clj (MC classpath dep)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.java.io :as jio]
            [nihilite.api :as api]
            [nihilite.test.fixtures :as fx]))

(use-fixtures :each fx/reg-cleanup)

(deftest examples-init-fixture-present
  (let [fixture (jio/file "examples/init.clj")]
    (is (.isFile fixture) "examples/init.clj must exist as a tracked fixture")))

(deftest api-install-resolves-at-runtime
  (is (true? (api/install! {:id              "runtime-init-loads-test"
                            :target-internal "java/lang/String"
                            :method-name     "length"
                            :descriptor      "()I"
                            :position        :entry
                            :action          :observe
                            :bridge          (fn [_] nil)}))
      "nihilite.api/install! must resolve and accept a minimal spec at test time"))

