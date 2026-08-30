(ns nihilite.test.dispatch-modified-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.registry :as reg]))

(defn- install-modify [id bridge-fn]
  (reg/install! {:id                id
                 :target-internal   "java/lang/String"
                 :method-name       "toString"
                 :descriptor        "()Ljava/lang/String;"
                 :position          :return
                 :action            :modify
                 :bridge            bridge-fn})
  id)

(use-fixtures :each
  (fn [f]
    (reg/clear!)
    (try (f) (finally (reg/clear!)))))

(defn- modified-of [id]
  (some-> (reg/get-stats id) :modified deref))

(deftest dispatch-return-bumps-modified-on-non-nil
  (let [id "mod-test"
        _  (install-modify id (fn [_] "modified-value"))]
    (reg/dispatch-return-for-spec id nil (object-array 0) "original")
    (is (= 1 (modified-of id))
        ":modify winning with non-nil return increments :modified")))

(deftest dispatch-return-no-bump-on-nil-rv
  (let [id "mod-nil-test"
        _  (install-modify id (fn [_] nil))]
    (reg/dispatch-return-for-spec id nil (object-array 0) "original")
    (is (= 0 (modified-of id))
        ":modify returning nil does NOT bump :modified")))
