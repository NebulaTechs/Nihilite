(ns nihilite.test.dispatch-modified-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.registry.stats :as stats]
            [nihilite.registry.dispatch :as dispatch]
            [nihilite.test.fixtures :as fx]))

(defn- install-modify [id bridge-fn]
  (reg/install! {:id                id
                 :target-internal   "java/lang/String"
                 :method-name       "toString"
                 :descriptor        "()Ljava/lang/String;"
                 :position          :return
                 :action            :modify
                 :bridge            bridge-fn})
  id)

(use-fixtures :each fx/reg-cleanup)

(defn- modified-of [id]
  (some-> (stats/get-stats id) :modified deref))

(deftest dispatch-return-bumps-modified-on-non-nil
  (let [id "mod-test"
        _  (install-modify id (fn [_] "modified-value"))]
    (dispatch/dispatch-return-for-spec id nil (object-array 0) "original")
    (is (= 1 (modified-of id))
        ":modify winning with non-nil return increments :modified")))

(deftest dispatch-return-no-bump-on-nil-rv
  (let [id "mod-nil-test"
        _  (install-modify id (fn [_] nil))]
    (dispatch/dispatch-return-for-spec id nil (object-array 0) "original")
    (is (= 0 (modified-of id))
        ":modify returning nil does NOT bump :modified")))

(deftest dispatch-return-modify-accepts-compatible-value
  (let [id "mod-ok"
        _  (install-modify id (fn [_] "replacement"))]
    (is (= "replacement"
           (dispatch/dispatch-return-for-spec id nil (object-array 0) "original"))
        "a same-typed replacement value is used as the return value")))

(deftest dispatch-return-modify-rejects-incompatible-value
  (testing "A :modify bridge that returns ctx (a HookEvent) instead of the
            replacement value cannot legally replace a String return. The
            advice is woven with @Advice.Return(typing=DYNAMIC), so the JVM
            would cast it and fail with a bare ClassCastException that names
            neither the hook nor the spec. dispatch must reject it first with
            a diagnosable error."
    (let [id "mod-bad"
          _  (install-modify id (fn [ctx] ctx))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"cannot replace the target's"
                            (dispatch/dispatch-return-for-spec
                              id nil (object-array 0) "original"))
          "incompatible :modify value throws a named error")
      (is (= 0 (modified-of id))
          "a rejected value does not count as :modified"))))

(deftest dispatch-return-modify-rejection-names-the-spec
  (let [id "mod-bad-named"
        _  (install-modify id (fn [ctx] ctx))]
    (try
      (dispatch/dispatch-return-for-spec id nil (object-array 0) "original")
      (is false "expected the incompatible :modify value to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :nihilite/invalid-modify-value (:nihilite/kind (ex-data e))))
        (is (= id (:nihilite/id (ex-data e)))
            "the error carries the offending spec id")
        (is (re-find (re-pattern id) (ex-message e))
            "the message names the spec")))))
