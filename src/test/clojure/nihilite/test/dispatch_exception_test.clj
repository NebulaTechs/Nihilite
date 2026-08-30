(ns nihilite.test.dispatch-exception-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.test.fixtures :as fx]))

(defn- install-bridge
  [id bridge-fn]
  (reg/install! {:id                id
                 :target-internal   "java/lang/String"
                 :method-name       "length"
                 :descriptor        "()I"
                 :position          :entry
                 :bridge            bridge-fn})
  id)

(defn- install-throw-bridge [id]
  (install-bridge id (fn [_] (throw (ex-info "boom" {})))))

(defn- exceptions-of [id]
  (some-> (reg/get-stats id) :exceptions deref))

(use-fixtures :each fx/reg-cleanup)

(deftest dispatch-one-bumps-on-bridge-throw
  (let [id "ex-test"
        _  (install-throw-bridge id)]
    (reg/dispatch-for-spec id nil (object-array 0))
    (is (= 1 (exceptions-of id))
        "single throw yields :exceptions = 1")))

(deftest dispatch-one-no-bump-on-clean-run
  (let [id "ok-test"
        _  (install-bridge id (fn [_] nil))]
    (dotimes [_ 5] (reg/dispatch-for-spec id nil (object-array 0)))
    (is (= 0 (exceptions-of id))
        "no throw → :exceptions stays 0")))

(deftest sibling-spec-isolation
  (let [id-a "iso-a"
        id-b "iso-b"
        _a   (reg/install! {:id                id-a
                            :target-internal   "java/lang/String"
                            :method-name       "length"
                            :descriptor        "()I"
                            :position          :entry
                            :bridge            (fn [_] (throw (ex-info "boom-a" {})))})
        _b   (reg/install! {:id                id-b
                            :target-internal   "java/lang/Integer"
                            :method-name       "intValue"
                            :descriptor        "()I"
                            :position          :entry
                            :bridge            (fn [_] nil)})]
    (reg/dispatch-for-spec id-a nil (object-array 0))
    (dotimes [_ 3] (reg/dispatch-for-spec id-b nil (object-array 0)))
    (is (= 1 (exceptions-of id-a)))
    (is (= 0 (exceptions-of id-b)))))
