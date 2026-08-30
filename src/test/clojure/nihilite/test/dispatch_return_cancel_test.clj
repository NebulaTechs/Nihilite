(ns nihilite.test.dispatch-return-cancel-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.test.fixtures :as fx]))

(use-fixtures :each fx/reg-cleanup)

(deftest return-cancel-short-circuits-return-bucket
  (testing "cancel flips skip rest of return bucket"
    (reg/install! {:id "rc-a" :target-internal "x" :method-name "m"
                   :descriptor "()V" :position :return :arity 0
                   :action :modify
                   :bridge (fn [_ev] "MUT-A")})
    (reg/install! {:id "rc-b" :target-internal "y" :method-name "m"
                   :descriptor "()V" :position :return :arity 0
                   :action :observe
                   :bridge (fn [ev] ((:cancel! ev) true))})
    (reg/install! {:id "rc-c" :target-internal "z" :method-name "m"
                   :descriptor "()V" :position :return :arity 0
                   :action :modify
                   :bridge (fn [_ev] "MUT-C")})
    (let [rv (reg/dispatch-return-for-spec "rc-a" nil
                                          (object-array 0) "ORIG")]
      (is (= "MUT-A" rv)))))

(deftest return-no-cancel-runs-all-bucket
  (testing "no cancel runs all return-bucket observers"
    (reg/install! {:id "nc-a" :target-internal "x" :method-name "m"
                   :descriptor "()V" :position :return :arity 0
                   :action :modify
                   :bridge (fn [_ev] "MUT-A")})
    (reg/install! {:id "nc-b" :target-internal "y" :method-name "m"
                   :descriptor "()V" :position :return :arity 0
                   :action :modify
                   :bridge (fn [_ev] "MUT-B")})
    (let [rv (reg/dispatch-return-for-spec "nc-a" nil
                                          (object-array 0) "ORIG")]
      (is (= "MUT-A" rv)))))

(deftest return-modify-winner-still-first
  (testing ":modify first-non-nil winner without cancel flip"
    (reg/install! {:id "fm-a" :target-internal "x" :method-name "m"
                   :descriptor "()V" :position :return :arity 0
                   :action :modify
                   :bridge (fn [_ev] "MUT-A")})
    (reg/install! {:id "fm-b" :target-internal "y" :method-name "m"
                   :descriptor "()V" :position :return :arity 0
                   :action :modify
                   :bridge (fn [_ev] "MUT-B")})
    (let [rv (reg/dispatch-return-for-spec "fm-a" nil
                                          (object-array 0) "ORIG")]
      (is (= "MUT-A" rv)))))

(deftest return-missing-spec-returns-original
  (testing "missing spec id returns current-return"
    (is (= "ORIG" (reg/dispatch-return-for-spec "no-such-spec"
                                                nil (object-array 0)
                                                "ORIG")))))