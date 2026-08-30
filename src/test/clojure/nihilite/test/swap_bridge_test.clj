(ns nihilite.test.swap-bridge-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.api :as api]
            [nihilite.registry :as reg]
            [nihilite.test.fixtures :as fx]))

(defn- entry-spec [id]
  {:id id
   :target-internal "java/lang/String"
   :method-name "length"
   :descriptor "()I"
   :position :entry
   :action :observe
   :bridge (fn [_] :original)})

(use-fixtures :each fx/reg-cleanup)

(deftest swap-bridge-replaces-bridge
  (api/install! (entry-spec "swap-test"))
  (let [replacement (fn [_] :replaced)]
    (api/swap-bridge! "swap-test" replacement)
    (is (identical? replacement (:bridge (api/lookup "swap-test"))))))

(deftest swap-bridge-missing-id-is-noop
  (is (false? (api/swap-bridge! "no-such-id" (fn [_] :x)))))

(deftest swap-bridge-preserves-other-fields
  (api/install! (entry-spec "preserve"))
  (api/swap-bridge! "preserve" (fn [_] :new))
  (let [s (api/lookup "preserve")]
    (is (= "java/lang/String" (:target-internal s)))
    (is (= "length" (:method-name s)))
    (is (= :entry (:position s)))))

(deftest c2-c4-bridge-reach-on-system-classloader
  (api/install! (entry-spec "reach-test"))
  (let [replacement (fn [_] :swapped)]
    (api/swap-bridge! "reach-test" replacement)
    (is (identical? replacement (:bridge (api/lookup "reach-test")))))
  (is (true? (api/uninstall! "reach-test")))
  (is (nil? (api/lookup "reach-test"))))

(deftest swap-bridge-preserves-stats-across-swap
  (api/install! (entry-spec "stats-preserve"))
  (reg/dispatch-for-spec "stats-preserve" nil (object-array 0))
  (reg/dispatch-for-spec "stats-preserve" nil (object-array 0))
  (let [fired-before (-> (reg/get-stats "stats-preserve") :fired deref)]
    (api/swap-bridge! "stats-preserve" (fn [_] :swapped))
    (let [fired-after  (-> (reg/get-stats "stats-preserve") :fired deref)]
      (is (= fired-before fired-after)))
    (reg/dispatch-for-spec "stats-preserve" nil (object-array 0))
    (let [fired-final (-> (reg/get-stats "stats-preserve") :fired deref)]
      (is (= (inc fired-before) fired-final)))))

(deftest swap-bridge-does-not-touch-by-target-bucket
  (api/install! (entry-spec "bucket-preserve"))
  (api/swap-bridge! "bucket-preserve" (fn [_] :new))
  (let [bucket (reg/matching "java/lang/String")
        ours   (filter #(= "bucket-preserve" (:id %)) bucket)]
    (is (= 1 (count ours)))
    (is (= "bucket-preserve" (:id (first ours))))))

(deftest swap-bridge-concurrent-dispatch-sees-one-or-the-other
  (let [counter (atom 0)
        bridge-a (fn [_] (swap! counter inc) :a)
        bridge-b (fn [_] (swap! counter inc) :b)]
    (api/install! (assoc (entry-spec "concurrent-swap") :bridge bridge-a))
    (reg/dispatch-for-spec "concurrent-swap" nil (object-array 0))
    (api/swap-bridge! "concurrent-swap" bridge-b)
    (reg/dispatch-for-spec "concurrent-swap" nil (object-array 0))
    (is (= 2 @counter))))
