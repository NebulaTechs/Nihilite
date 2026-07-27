(ns nihilite.test.eval-test
  "Unit tests for eval-form and eval-form-lf in nihilite.readline."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nihilite.readline :as rl]))

(deftest test-eval-form
  (testing "Successful evaluation with CRLF termination"
    (let [state (atom {:ns (find-ns 'user) :*1 nil :*2 nil :*3 nil :*e nil})
          res (rl/eval-form "(+ 1 2)" state)]
      (is (= "=> 3\r\n" res))
      (is (= 3 (:*1 @state)))))

  (testing "Successful evaluation with LF termination"
    (let [state (atom {:ns (find-ns 'user) :*1 nil :*2 nil :*3 nil :*e nil})
          res (rl/eval-form-lf "(* 6 7)" state)]
      (is (= "=> 42\n" res))
      (is (= 42 (:*1 @state)))))

  (testing "REPL history bindings (*1, *2, *3)"
    (let [state (atom {:ns (find-ns 'user) :*1 nil :*2 nil :*3 nil :*e nil})]
      (rl/eval-form "10" state)
      (is (= 10 (:*1 @state)))
      (rl/eval-form "20" state)
      (is (= 20 (:*1 @state)))
      (is (= 10 (:*2 @state)))
      (rl/eval-form "30" state)
      (is (= 30 (:*1 @state)))
      (is (= 20 (:*2 @state)))
      (is (= 10 (:*3 @state)))))

  (testing "Error formatting and *e binding"
    (let [state (atom {:ns (find-ns 'user) :*1 nil :*2 nil :*3 nil :*e nil})
          res (rl/eval-form "(/ 1 0)" state)]
      (is (str/starts-with? res "ERROR [runtime-error]"))
      (is (some? (:*e @state)))
      (is (instance? ArithmeticException (:*e @state))))))
