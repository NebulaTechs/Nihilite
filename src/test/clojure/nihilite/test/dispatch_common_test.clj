(ns nihilite.test.dispatch-common-test
  (:require [clojure.test :refer [deftest is]]
            [nihilite.registry :as reg]))

(deftest position-from-bucket-walks-entry-and-throw
  (reg/clear!)
  (reg/install! {:id "common-entry"
                 :target-internal "java/lang/String"
                 :method-name "length"
                 :descriptor "()I"
                 :position :entry
                 :bridge (fn [_])})
  (reg/install! {:id "common-throw"
                 :target-internal "java/lang/String"
                 :method-name "length"
                 :descriptor "()I"
                 :position :throw
                 :bridge (fn [_])})
  (let [e (reg/lookup "common-entry")
        t (reg/lookup "common-throw")]
    (is (= :entry (reg/position e)))
    (is (= :throw (reg/position t)))
    (is (= 2 (count (reg/matching "java/lang/String"))))))
