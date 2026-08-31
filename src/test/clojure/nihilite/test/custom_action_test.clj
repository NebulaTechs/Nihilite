(ns nihilite.test.custom-action-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.test.fixtures :as fx]))

(use-fixtures :each fx/reg-cleanup)

(deftest register-action-adds-to-known-set
  (testing "default actions are present"
    (is (contains? (reg/registered-actions) :observe))
    (is (contains? (reg/registered-actions) :modify))
    (is (contains? (reg/registered-actions) :cancel))
    (is (contains? (reg/registered-actions) :subscriber)))
  (testing "a new action becomes installable"
    (is (not (contains? (reg/registered-actions) :audit)))
    (reg/register-action! :audit)
    (is (contains? (reg/registered-actions) :audit))))

(deftest register-action-rejects-non-keyword
  (is (thrown? clojure.lang.ExceptionInfo
               (reg/register-action! "audit"))))

(deftest custom-action-installs-and-dispatches
  (reg/register-action! :audit)
  (let [spec {:id              "audit-hook"
              :target-internal "com/example/Foo"
              :source-class    "java/lang/Object"
              :method-name     "bar"
              :descriptor      "(I)V"
              :position        :entry
              :action          :audit
              :bridge          (fn [_ctx] :audited)}]
    (is (true? (reg/install! spec)))
    (is (= :audit (:action (reg/lookup "audit-hook"))))))

(deftest unknown-action-still-rejected
  (let [spec {:id              "bad-hook"
              :target-internal "com/example/Foo"
              :source-class    "java/lang/Object"
              :method-name     "bar"
              :descriptor      "(I)V"
              :position        :entry
              :action          :not-registered
              :bridge          (fn [_ctx] nil)}]
    (is (thrown? clojure.lang.ExceptionInfo
                 (reg/install! spec)))))