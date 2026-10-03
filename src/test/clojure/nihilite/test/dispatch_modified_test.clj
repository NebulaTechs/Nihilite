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

(deftest coerce-return-rejects-values-that-would-truncate
  ;; JVM's .byteValue/.intValue saturate instead of failing, so without a
  ;; range check a bridge returning 300 against a byte-returning method makes
  ;; the woven method return 44 with no signal anywhere. Measured before the
  ;; check existed: 300->44, 1e20->Integer/MAX_VALUE, NaN->0,
  ;; Long/MAX_VALUE->-1.
  (let [f #'dispatch/coerce-return
        rejected? (fn [rv desc]
                    (try (f rv desc) false
                         (catch clojure.lang.ExceptionInfo e
                           (= :nihilite/invalid-modify-value
                              (:nihilite/kind (ex-data e))))))]
    (testing "out of range for the target"
      (is (rejected? 300 "()B")      "300 does not fit a byte")
      (is (rejected? -300 "()B")     "-300 does not fit a byte")
      (is (rejected? 1e20 "()I")     "1e20 does not fit an int")
      (is (rejected? 1e20 "()B")     "1e20 does not fit a byte")
      (is (rejected? 40000 "()S")    "40000 does not fit a short")
      (is (rejected? (biginteger "9223372036854775808") "()J")
          "Long/MAX_VALUE + 1 does not fit a long"))
    (testing "the error names the spec's descriptor, not just the value"
      (let [d (ex-data (try (f 300 "()B") (catch clojure.lang.ExceptionInfo e e)))]
        (is (= "()B" (:nihilite/descriptor d)))
        (is (= Byte (:nihilite/target d)))))
    (testing "in range still narrows"
      (is (= 127 (f 127 "()B")))
      (is (= -128 (f -128 "()B")))
      (is (= 2147483647 (f 2147483647 "()I")))
      (is (= -2147483648 (f -2147483648 "()I")))
      (is (= 32767 (f 32767 "()S")))
      (is (= (int 2147483647) (f 2147483647 "()I"))))
    (testing "widening never rejects"
      (is (= 7 (f (byte 7) "()I")) "a byte always fits an int")
      (is (= 7 (f (short 7) "()I")) "a short always fits an int")
      (is (instance? Double (f 1 "()D"))))
    (testing "a value with no exact integer form is not range-checked"
      ;; Deciding what 1.5 becomes against an int return is the JVM's
      ;; narrowing rule, not a range question, so it is left alone.
      (is (= 1 (f 1.5 "()I")))
      (is (= 0 (f 0.9 "()B")))
      (is (= 0 (f Double/NaN "()I")) "NaN has no integer value to check")
      (is (= 2147483647 (f Double/POSITIVE_INFINITY "()I"))))
    (testing "an integral Double IS range-checked"
      ;; 1e20 is exactly 10^20, so it has an integer value and it does not
      ;; fit an int. This is the case that used to return
      ;; Integer/MAX_VALUE with no signal.
      (is (rejected? 1e20 "()I"))
      (is (rejected? 1e30 "()J"))
      (is (rejected? (biginteger "123456789012345678901234567890") "()J"))
      (is (instance? Long (f 1e10 "()J")) "1e10 fits a long"))))

(deftest modify-bridge-returning-out-of-range-value-is-rejected
  ;; The same rejection, end to end through dispatch-return-for-spec, so the
  ;; spec id reaches the caller instead of a bare Clojure number.
  (let [id "mod-truncating"
        _  (reg/install! {:id              id
                          :target-internal "java/util/BitSet"
                          :method-name     "size"
                          :descriptor      "()I"
                          :position        :return
                          :action          :modify
                          :bridge          (fn [_] 1e20)})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":modify bridge returned"
                          (dispatch/dispatch-return-for-spec id nil (object-array 0) (int 0)))
        "a value that cannot fit the target's return type is rejected, not truncated")
    (is (zero? (modified-of id))
        "a rejected :modify does not count as modified")))
