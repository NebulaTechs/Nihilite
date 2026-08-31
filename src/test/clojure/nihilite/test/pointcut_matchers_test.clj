(ns nihilite.test.pointcut-matchers-test
  "C4 deliverable: validate Pointcut protocol and three implementations."
  (:require [clojure.test :refer [deftest testing is]]
            [nihilite.pointcut :as p])
  (:import (nihilite.pointcut ExactPointcut WildcardPointcut AnnotationPointcut)
           (java.lang.reflect Method)))

(deftest exact-pointcut-hits-on-all-fields
  (let [ep (p/exact "com.example.Foo" "bar" "(I)V")
        hit {:target-internal "com.example.Foo"
             :method-name     "bar"
             :descriptor      "(I)V"}]
    (is (p/matches? ep hit))
    (testing "slash form is normalized"
      (is (p/matches? ep (assoc hit :target-internal "com/example/Foo"))))))

(deftest exact-pointcut-misses-on-different-method
  (let [ep (p/exact "com.example.Foo" "bar" "(I)V")
        miss {:target-internal "com.example.Foo"
              :method-name     "baz"
              :descriptor      "(I)V"}]
    (is (not (p/matches? ep miss)))))

(deftest exact-pointcut-ignores-descriptor-when-nil
  (let [ep (p/exact "com.example.Foo" "bar" nil)]
    (is (p/matches? ep {:target-internal "com.example.Foo"
                        :method-name     "bar"
                        :descriptor      "(I)V"}))))

(deftest wildcard-pointcut-single-segment-star
  (let [wp (p/wildcard "com.example.Foo" "set*")]
    (is (p/matches? wp {:target-internal "com.example.Foo" :method-name "setName"}))
    (is (p/matches? wp {:target-internal "com.example.Foo" :method-name "set"}))
    (is (not (p/matches? wp {:target-internal "com.example.Foo" :method-name "getName"})))))

(deftest wildcard-pointcut-double-star-uses-multi-segment
  (let [wp (p/wildcard "com.example.**.Foo" "bar")]
    (is (p/matches? wp {:target-internal "com.example.deep.nested.Foo" :method-name "bar"}))
    (is (p/matches? wp {:target-internal "com.example.X.Foo" :method-name "bar"}))
    (is (not (p/matches? wp {:target-internal "com.example.deep.Baz" :method-name "bar"})))))

(deftest wildcard-pointcut-question-marks-exactly-one-char
  (let [wp (p/wildcard "com.example.Fo?" "ba?")]
    (is (p/matches? wp {:target-internal "com.example.Foo" :method-name "bar"}))
    (is (not (p/matches? wp {:target-internal "com.example.Fo" :method-name "bar"})))))

(deftest annotation-pointcut-without-method-or-class-returns-false
  (let [ap (p/annotation #{"java.lang.Deprecated"})]
    (is (not (p/matches? ap {:target-internal "x" :method-name "y"})))))

(deftest glob-regex-conversion-special-chars
  (is (= "foo\\.bar"  (p/glob->regex "foo.bar")))
  (is (= "foo.*bar"   (p/glob->regex "foo*bar")))
  (is (= "foo.bar"    (p/glob->regex "foo?bar"))))