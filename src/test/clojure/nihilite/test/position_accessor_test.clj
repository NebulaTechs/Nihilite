(ns nihilite.test.position-accessor-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.test.fixtures :as fx]))

(use-fixtures :each fx/reg-cleanup)

(deftest position-keyword-on-spec-map
  (is (= :entry (reg/position {:position :entry})))
  (is (= :return (reg/position {:position :return})))
  (is (= :throw (reg/position {:position :throw}))))

(deftest position-string-key-on-spec-map
  (is (= :entry (reg/position {"position" :entry})))
  (is (= :redefine (reg/position {"position" :redefine}))))

(deftest position-both-keys-present
  (testing "string-key form takes precedence when both keys present"
    (is (= :str (reg/position {:position :kw "position" :str})))
    (is (= :str (reg/position {"position" :str :position :kw}))))
  (testing "single-source access returns the value verbatim"
    (is (= :entry (reg/position {:position :entry})))
    (is (= :entry (reg/position {"position" :entry})))))

(deftest position-on-installed-spec
  (reg/install! {:id "pos-test"
                 :target-internal "java/lang/String"
                 :method-name "length"
                 :descriptor "()I"
                 :position :return
                 :bridge (fn [_])})
  (let [spec (reg/lookup "pos-test")]
    (is (= :return (reg/position spec)))))

(deftest position-missing-returns-nil
  (is (nil? (reg/position {})))
  (is (nil? (reg/position nil)))
  (is (nil? (reg/position {:other-key 1})))
  (is (nil? (reg/position {"other-key" 1}))))
