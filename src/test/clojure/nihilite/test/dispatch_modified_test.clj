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

(deftest dispatch-return-modify-narrows-a-number-to-the-target-type
  ;; A Clojure literal boxes to Long. The advice writes the bridge's value
  ;; into the target's return slot with a CHECKCAST to the target type, so a
  ;; bridge returning a bare 7 against an int-returning method used to fail
  ;; with "Long cannot be cast to Integer" from inside the woven method --
  ;; naming neither the hook nor the spec. The narrowing happens in Clojure now
  ;; so the value is right before the cast.
  (let [id "mod-narrow"
        _  (reg/install! {:id                id
                          :target-internal   "java/util/BitSet"
                          :method-name       "size"
                          :descriptor        "()I"
                          :position          :return
                          :action            :modify
                          :bridge            (fn [_] 7)})]
    (is (= 7 (dispatch/dispatch-return-for-spec id nil (object-array 0) (int 0)))
        "a Long from a bare literal is narrowed to the target's int")))

(deftest coerce-return-converts-by-descriptor
  (let [f #'dispatch/coerce-return]
    (is (instance? Integer (f 7 "()I")))
    (is (instance? Long (f 7 "()J")))
    (is (instance? Short (f 7 "()S")))
    (is (instance? Byte (f 7 "()B")))
    (is (instance? Double (f 7 "()D")))
    (is (instance? Float (f 7.5 "()F")))
    (is (= 7 (f 7 "()V")) "a void target is passed through unchanged")
    (is (= 7 (f 7 "()Ljava/lang/String;"))
        "a reference target is passed through untouched")))
