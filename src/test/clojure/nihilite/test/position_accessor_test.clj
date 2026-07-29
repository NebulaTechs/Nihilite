(ns nihilite.test.position-accessor-test
  "Regression for `nihilite.registry.accessors/position` — the
   dual-mode accessor that accepts both :position (keyword) and
   \"position\" (string) at the call site. This dual-mode is the
   30-day default if no operator signoff arrives."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.registry.accessors :as ra]
            [nihilite.registry.install :as install]
            [nihilite.registry.spec :as rs]))

(defn- fixture [f]
  (reg/clear!)
  (try (f) (finally (reg/clear!))))

(use-fixtures :each (fn [t] (fixture t)))

(deftest position-keyword-on-spec-map
  (is (= :entry (ra/position {:position :entry})))
  (is (= :return (ra/position {:position :return})))
  (is (= :throw (ra/position {:position :throw}))))

(deftest position-string-key-on-spec-map
  (is (= :entry (ra/position {"position" :entry})))
  (is (= :redefine (ra/position {"position" :redefine}))))

(deftest position-both-keys-present
  (testing "if BOTH :position and \"position\" are present,
            the string form takes precedence (the string-keyed form
            is checked first because downstream tooling that mixes
            access keys frequently uses the string form for
            stringified access). Adjust
            to keyword-first if the operator signoff goes the
            other way."
    (is (= :str (ra/position {:position :kw "position" :str})))
    (is (= :str (ra/position {"position" :str :position :kw}))))
  (testing "single-source access returns the value verbatim"
    (is (= :entry (ra/position {:position :entry})))
    (is (= :entry (ra/position {"position" :entry})))))

(deftest position-from-hook-event
  (let [ev (rs/map->HookEvent
             {:spec-id "x"
              :source {:class "c" :method "m"
                       :descriptor "()V"
                       :method-key "c/m#()V"}
              :phase :return
              :self nil
              :args (object-array 0)
              :return-value nil
              :cancelled? (constantly false)
              :cancel! (fn [_])
              :thread-name "t"
              :timestamp-ns 0
              :sequence 1
              :note nil
              :stack nil})]
    (is (= :return (ra/position ev)))))

(deftest position-on-installed-spec
  (install/install! {:id "pos-test"
                     :target-internal "java/lang/String"
                     :method-name "length"
                     :descriptor "()I"
                     :position :return
                     :bridge (fn [_])})
  (let [spec (reg/lookup "pos-test")]
    (is (= :return (ra/position spec)))))

(deftest position-missing-returns-nil
  (is (nil? (ra/position {})))
  (is (nil? (ra/position nil)))
  (is (nil? (ra/position {:other-key 1})))
  (is (nil? (ra/position {"other-key" 1}))))