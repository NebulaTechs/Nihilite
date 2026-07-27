(ns nihilite.test.paren-balance-test
  "Unit tests for paren-balance in nihilite.readline."
  (:require [clojure.test :refer [deftest is testing]]
            [nihilite.readline :as rl]))

(deftest test-paren-balance
  (testing "Basic paren balance"
    (is (= 0 (rl/paren-balance "")))
    (is (= 0 (rl/paren-balance "()")))
    (is (= 0 (rl/paren-balance "(foo bar)")))
    (is (= 1 (rl/paren-balance "(")))
    (is (= 2 (rl/paren-balance "((")))
    (is (= -1 (rl/paren-balance ")"))))

  (testing "Mixed bracket balance"
    (is (= 0 (rl/paren-balance "[1 2 3]")))
    (is (= 0 (rl/paren-balance "{:a 1 :b 2}")))
    (is (= 0 (rl/paren-balance "({[foo]})")))
    (is (= 3 (rl/paren-balance "({[")))
    (is (= -3 (rl/paren-balance "]})"))))

  (testing "Ignoring brackets inside string literals"
    (is (= 0 (rl/paren-balance "\"(unbalanced string\"")))
    (is (= 0 (rl/paren-balance "\"foo ( bar [ baz { qux\"")))
    (is (= 0 (rl/paren-balance "\"\\\"(\"")))
    (is (= 0 (rl/paren-balance "\"(foo \\\"bar\\\")\""))))

  (testing "Ignoring brackets inside line comments"
    (is (= 0 (rl/paren-balance "; (unbalanced comment\n")))
    (is (= 0 (rl/paren-balance ";; (hello [world {\n")))
    (is (= 0 (rl/paren-balance "(foo ; (comment\n)")))
    (is (= 1 (rl/paren-balance "(foo ; (comment\n")))
    (is (= 0 (rl/paren-balance "; comment to EOF ("))))

  (testing "Ignoring brackets inside form-discard comments (#_)"
    (is (= 0 (rl/paren-balance "#_(unbalanced form")))
    (is (= 0 (rl/paren-balance "#_(foo [bar {baz}])")))
    (is (= 0 (rl/paren-balance "(#_(discarded [form]) foo)")))
    (is (= 1 (rl/paren-balance "(#_(discarded [form]) foo"))))

  (testing "Ignoring character literals"
    (is (= 0 (rl/paren-balance "\\(")))
    (is (= 0 (rl/paren-balance "\\)")))
    (is (= 0 (rl/paren-balance "\\[")))
    (is (= 0 (rl/paren-balance "\\]")))
    (is (= 0 (rl/paren-balance "\\{")))
    (is (= 0 (rl/paren-balance "\\}")))
    (is (= 0 (rl/paren-balance "\\newline")))
    (is (= 0 (rl/paren-balance "\\space")))))
