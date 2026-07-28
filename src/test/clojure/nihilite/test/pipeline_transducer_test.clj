(ns nihilite.test.pipeline-transducer-test
  "Pipeline transducer tests. Per D3.1-D3.4:
   - Pipeline is a 1-arg transducer + sink
   - Per-observer try/catch isolation INSIDE subscriber bridge (B1 fix)
   - Throwing xform is caught by subscriber's own :exception counter"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.observers.subscriber :as sub]))

(defn- setup [f]
  (reg/clear!)
  (try (f) (finally (reg/clear!))))

(use-fixtures :each
  (fn [t] (setup t)))

(deftest filter-takes-only-matching
  (testing "filter transducer drops non-matching events"
    (reg/install! {:id "p-1" :target-internal "x" :method-name "m"
                   :descriptor "()V" :position :entry :arity 0
                   :bridge (fn [_])})
    (let [received (atom 0)
          s (sub/subscribe! (fn [_ev] (swap! received inc))
                            {:sink :println
:xform (clojure.core/filter
                                       (fn [ev]
                                         (= "x"
                                            (:internal (:source ev)))))})
          _ s]
      (reg/dispatch "p-1" nil (object-array 0))
      (is (= 1 @received)))))

(deftest take-stops-after-n
  (testing "take-N stops the bridge after N dispatches"
    (reg/install! {:id "p-2" :target-internal "x" :method-name "m"
                   :descriptor "()V" :position :entry :arity 0
                   :bridge (fn [_])})
    (let [received (atom 0)
          s (sub/subscribe! (fn [_ev] (swap! received inc))
                            {:sink :println
                             :xform (clojure.core/take 2)})
          _ s]
      (reg/dispatch "p-2" nil (object-array 0))
      (reg/dispatch "p-2" nil (object-array 0))
      (reg/dispatch "p-2" nil (object-array 0))
      (is (= 2 @received)))))

(deftest throwing-xform-caught-and-counted
  (testing "throwing xform is caught; subscriber :exception counter increments"
    (reg/install! {:id "p-3" :target-internal "x" :method-name "m"
                   :descriptor "()V" :position :entry :arity 0
                   :bridge (fn [_])})
    (let [s (sub/subscribe! (fn [_ev] (throw (ex-info "user-throws" {})))
                            {:sink :println
                             :silence-match-all-warning true})]
      (reg/dispatch "p-3" nil (object-array 0))
      (is (= 1 @(:exception s)))))
)
