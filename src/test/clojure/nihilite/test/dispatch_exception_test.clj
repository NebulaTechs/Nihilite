(ns nihilite.test.dispatch-exception-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.builder.registry :as reg]
            [nihilite.builder.registry.stats :as stats]
            [nihilite.builder.registry.dispatch :as dispatch]
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
  (some-> (stats/get-stats id) :exceptions deref))

(use-fixtures :each fx/reg-cleanup)

(deftest dispatch-one-bumps-on-bridge-throw
  (let [id "ex-test"
        _  (install-throw-bridge id)]
    (dispatch/dispatch-for-spec id nil (object-array 0))
    (is (= 1 (exceptions-of id))
        "single throw yields :exceptions = 1")))

(deftest dispatch-one-no-bump-on-clean-run
  (let [id "ok-test"
        _  (install-bridge id (fn [_] nil))]
    (dotimes [_ 5] (dispatch/dispatch-for-spec id nil (object-array 0)))
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
    (dispatch/dispatch-for-spec id-a nil (object-array 0))
    (dotimes [_ 3] (dispatch/dispatch-for-spec id-b nil (object-array 0)))
    (is (= 1 (exceptions-of id-a)))
    (is (= 0 (exceptions-of id-b)))))

(deftest an-exception-from-a-bridge-does-not-stop-the-walk
  ;; The isolation contract: one observer misbehaving must not take down the
  ;; host application, nor prevent the other hooks on the same method from
  ;; running. dispatch-for-spec for id-a returns the whole bucket (see
  ;; registry/spec-bucket), so this walks A then B in one call.
  (let [hits    (atom [])
        id-a    "exc-a"
        id-b    "exc-b"
        _a      (reg/install! {:id                id-a
                               :target-internal   "java/lang/String"
                               :method-name       "length"
                               :descriptor        "()I"
                               :position          :entry
                               :bridge            (fn [_]
                                                    (swap! hits conj :a)
                                                    (throw (ex-info "boom-a" {})))})
        _b      (reg/install! {:id                id-b
                               :target-internal   "java/lang/String"
                               :method-name       "length"
                               :descriptor        "()I"
                               :position          :entry
                               :bridge            (fn [_] (swap! hits conj :b))})]
    (is (nil? (dispatch/dispatch-for-spec id-a nil (object-array 0)))
        "the Exception is contained, not propagated")
    (is (= 1 (exceptions-of id-a))
        "and it is counted against the spec that threw")
    (is (= [:a :b] @hits)
        (str "the sibling hook still ran -- got " (pr-str @hits)))))

(deftest an-error-from-a-bridge-escapes-instead-of-being-counted
  ;; dispatch-one! catches Exception, not Throwable. Error means the JVM
  ;; itself is in trouble -- StackOverflowError above all, which is what the
  ;; reentrancy cycle produces and which docs/hook-limits.md records as too
  ;; deep for the JVM to print a stack for. Counting that as an ordinary
  ;; observer exception, reporting a normal :exceptions and answering
  ;; ::no-return made the worst failure this project has look routine.
  ;;
  ;; Letting it out means it reaches the advice layer's advice-ex! wrapper,
  ;; which can attach the spec-id. It also stops the walk, because there is no
  ;; longer a reason to keep invoking user bridges on a JVM that is failing.
  (let [hits    (atom [])
        id-a    "err-a"
        id-b    "err-b"
        _a      (reg/install! {:id                id-a
                               :target-internal   "java/lang/String"
                               :method-name       "length"
                               :descriptor        "()I"
                               :position          :entry
                               :bridge            (fn [_]
                                                    (swap! hits conj :a)
                                                    (throw (Error. "host JVM is failing")))})
        _b      (reg/install! {:id                id-b
                               :target-internal   "java/lang/String"
                               :method-name       "length"
                               :descriptor        "()I"
                               :position          :entry
                               :bridge            (fn [_] (swap! hits conj :b))})]
    (is (thrown? Error (dispatch/dispatch-for-spec id-a nil (object-array 0)))
        "an Error is not an observer exception and must not be reported as one")
    (is (zero? (exceptions-of id-a))
        (str "so it is not counted as an :exceptions either -- got "
             (exceptions-of id-a)))
    (is (= [:a] @hits)
        (str "and the walk stops rather than invoking more user bridges -- got "
             (pr-str @hits)))))
