(ns nihilite.test.debug-api-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.debug :as dbg]
            [nihilite.test.fixtures :as fx]))

(use-fixtures :each fx/reg-cleanup)

(deftest why-firing-unknown-spec
  (is (= {:firing? false :reason :unknown-spec :id "nope"}
         (dbg/why-firing? "nope"))))

(deftest why-firing-class-not-loaded
  (reg/install! {:id              "ghost"
                 :target-internal "com/example/DefinitelyNotLoaded"
                 :source-class    "java/lang/Object"
                 :method-name     "bar"
                 :descriptor      "(I)V"
                 :position        :entry
                 :action          :observe
                 :bridge          (fn [_] nil)})
  (let [r (dbg/why-firing? "ghost")]
    (is (false? (:firing? r)))
    (is (= :class-not-loaded (:reason r)))))

(deftest why-firing-loaded-class
  (reg/install! {:id              "real"
                 :target-internal "java/lang/Object"
                 :source-class    "java/lang/Object"
                 :method-name     "toString"
                 :descriptor      "()Ljava/lang/String;"
                 :position        :entry
                 :action          :observe
                 :bridge          (fn [_] nil)})
  (let [r (dbg/why-firing? "real")]
    (is (true? (:firing? r)))
    (is (= :ok (:reason r)))
    (is (some? (:stats r)))))

(deftest trace-last-fires-empty
  (is (= [] (dbg/trace-last-fires 10))))

(deftest trace-last-fires-records
  (reg/install! {:id              "t1"
                 :target-internal "java/lang/Object"
                 :source-class    "java/lang/Object"
                 :method-name     "toString"
                 :descriptor      "()Ljava/lang/String;"
                 :position        :entry
                 :action          :observe
                 :bridge          (fn [_] nil)})
  (reg/dispatch-for-spec "t1" (Object.) (object-array 0))
  (let [traces (dbg/trace-last-fires 10)]
    (is (= 1 (count traces)))
    (is (= "t1" (:spec-id (first traces))))
    (is (= :entry (:phase (first traces))))))