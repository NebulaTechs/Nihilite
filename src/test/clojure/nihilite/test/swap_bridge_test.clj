(ns nihilite.test.swap-bridge-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.api :as api]
            [nihilite.registry :as reg]
            [nihilite.registry.stats :as stats]
            [nihilite.registry.dispatch :as dispatch]
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
  (dispatch/dispatch-for-spec "stats-preserve" nil (object-array 0))
  (dispatch/dispatch-for-spec "stats-preserve" nil (object-array 0))
  (let [fired-before (-> (stats/get-stats "stats-preserve") :fired deref)]
    (api/swap-bridge! "stats-preserve" (fn [_] :swapped))
    (let [fired-after  (-> (stats/get-stats "stats-preserve") :fired deref)]
      (is (= fired-before fired-after)))
    (dispatch/dispatch-for-spec "stats-preserve" nil (object-array 0))
    (let [fired-final (-> (stats/get-stats "stats-preserve") :fired deref)]
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
    (dispatch/dispatch-for-spec "concurrent-swap" nil (object-array 0))
    (api/swap-bridge! "concurrent-swap" bridge-b)
    (dispatch/dispatch-for-spec "concurrent-swap" nil (object-array 0))
    (is (= 2 @counter))))

(deftest swap-bridge-reaches-the-dispatch-path
  (let [calls (atom [])]
    (api/install! (assoc (entry-spec "swap-dispatch")
                         :bridge (fn [_] (swap! calls conj :old) :old)))
    (dispatch/dispatch-for-spec "swap-dispatch" nil (object-array 0))
    (api/swap-bridge! "swap-dispatch" (fn [_] (swap! calls conj :new) :new))
    (dispatch/dispatch-for-spec "swap-dispatch" nil (object-array 0))
    (is (= [:old :new] @calls)
        "the second dispatch must run the swapped-in bridge, not the one the
         method bucket still holds")))

(deftest swap-bridge-reaches-the-return-path
  (let [seen (atom [])]
    (api/install! (assoc (entry-spec "swap-return")
                         :target-internal "java/lang/Integer"
                         :descriptor "(I)I"
                         :position :return
                         :action :modify
                         :bridge (fn [_] (swap! seen conj :old) 1)))
    (is (= 1 (dispatch/dispatch-return-for-spec "swap-return" nil (object-array [7]) 7)))
    (api/swap-bridge! "swap-return" (fn [_] (swap! seen conj :new) 2))
    (is (= 2 (dispatch/dispatch-return-for-spec "swap-return" nil (object-array [7]) 7))
        ":return walks the same method bucket, so a swap that only rewrites
         by-id leaves the old bridge in place there too")
    (is (= [:old :new] @seen))))

(deftest swap-bridge-preserves-the-action
  (let [seen (atom [])
        mark (fn [tag]
               (fn [ctx] (swap! seen conj tag) ((:cancel! ctx) true) nil))]
    (api/install! (assoc (entry-spec "swap-action") :action :cancel :bridge (mark :old)))
    (is (= :nihilite/short-circuit
           (dispatch/dispatch-for-spec "swap-action" nil (object-array 0))))
    (api/swap-bridge! "swap-action" (mark :new))
    (is (= :nihilite/short-circuit
           (dispatch/dispatch-for-spec "swap-action" nil (object-array 0))))
    (is (= [:old :new] @seen)
        "the swap rewrites the bridge, never the action: a re-pointed bridge
         must not silently change what the hook does to the call")))

(deftest swap-bridge-keeps-one-spec-per-bucket
  (api/install! (entry-spec "bucket-dedup"))
  (api/swap-bridge! "bucket-dedup" (fn [_] :new))
  (let [ours (filter #(= "bucket-dedup" (:id %))
                     (reg/matching "java/lang/String"))]
    (is (= 1 (count ours))
        "the swapped spec must replace the old one in the target bucket, not
         sit beside it")
    (let [s (first ours)]
      (is (identical? (:bridge (api/lookup "bucket-dedup")) (:bridge s))
          "by-id and the target bucket must hold the same spec value")
      (is (= 1 (count (filter #(= "bucket-dedup" (:id %))
                             (reg/spec-bucket s))))
          "the method bucket must also hold exactly one entry for the id"))))
