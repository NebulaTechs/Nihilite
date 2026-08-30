(ns nihilite.test.dispatch-entry-cancel-test
  ":entry :cancel unit tests. Verifies that dispatch-for-spec returns
   the ::short-circuit sentinel when an :action :cancel spec flips the
   event. The Java HookAdvice reads this sentinel and throws
   HookCancelledException; see retransformDriver for the ByteBuddy-level
   e2e that proves the host method body is actually skipped."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.test.fixtures :as fx]))

(defn- entry-cancel-spec [id]
  {:id id
   :target-internal "java/lang/String"
   :method-name "length"
   :descriptor "()I"
   :position :entry
   :action :cancel
   :bridge (fn [ctx] ((:cancel! ctx) true))})

(defn- entry-observe-spec [id]
  {:id id
   :target-internal "java/lang/String"
   :method-name "length"
   :descriptor "()I"
   :position :entry
   :action :observe
   :bridge (fn [_] nil)})

(use-fixtures :each fx/reg-cleanup)

(deftest entry-cancel-returns-short-circuit-sentinel
  (reg/install! (entry-cancel-spec "cancel-test"))
  (is (= :nihilite.registry/short-circuit
         (reg/dispatch-for-spec "cancel-test" nil (object-array 0)))))

(deftest entry-observe-returns-nil
  (reg/install! (entry-observe-spec "observe-test"))
  (is (nil? (reg/dispatch-for-spec "observe-test" nil (object-array 0)))))

(deftest entry-cancel-stops-on-first-cancel
  (reg/install! (entry-cancel-spec "first-cancel"))
  (reg/install! (entry-observe-spec "would-be-second"))
  (is (= :nihilite.registry/short-circuit
         (reg/dispatch-for-spec "first-cancel" nil (object-array 0)))))

(deftest cancel-sentinel-is-namespaced
  (reg/install! (entry-cancel-spec "id-test"))
  (let [a (reg/dispatch-for-spec "id-test" nil (object-array 0))
        b (reg/dispatch-for-spec "id-test" nil (object-array 0))]
    (is (identical? a b))
    (is (= "short-circuit" (name a)))))