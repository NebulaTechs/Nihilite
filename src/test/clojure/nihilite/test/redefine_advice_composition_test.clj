(ns nihilite.test.redefine-advice-composition-test
  "Verifies that a :redefine spec and an :entry spec on the same method
   coexist in the registry and split into separate position buckets,
   which is what the single combined transformer (redefine-then-advice)
   depends on.

   Pure-registry assertions: both specs register for the same
   target/method, `matching` returns both, and the position grouping
   keeps them apart so the transformer can apply redefine first and
   advice second."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.registry.dispatch :as dispatch]
            [nihilite.test.fixtures :as fx]))

(defn- entry-spec [id]
  {:id id
   :target-internal "nihilite/test/DummyTarget"
   :method-name "doThing"
   :descriptor "()V"
   :position :entry
   :action :observe
   :bridge (fn [_ctx] nil)})

(defn- redefine-spec [id]
  {:id id
   :target-internal "nihilite/test/DummyTarget"
   :method-name "doThing"
   :descriptor "()V"
   :position :redefine
   :action :observe
   :bridge (fn [_ctx] nil)})

(use-fixtures :each fx/reg-cleanup-with-dispatcher)

(deftest redefine-and-entry-register-on-same-method
  (reg/install! (entry-spec "comp-entry"))
  (reg/install! (redefine-spec "comp-redef"))
  (let [specs (reg/matching "nihilite/test/DummyTarget")]
    (is (= 2 (count specs)) "both specs visible for the target class")
    (let [by-pos (group-by :position specs)]
      (is (seq (:entry by-pos)) "entry bucket has the entry spec")
      (is (seq (:redefine by-pos)) "redefine bucket has the redefine spec"))))

(deftest combined-transformer-sees-both-specs
  (reg/install! (entry-spec "order-entry"))
  (reg/install! (redefine-spec "order-redef"))
  (let [specs (reg/matching "nihilite/test/DummyTarget")]
    (is (= 2 (count specs)) "combined transformer sees both specs")
    (is (some #(= :redefine (:position %)) specs) "redefine spec present")
    (is (some #(= :entry (:position %)) specs) "entry spec present")))

(deftest spec-bucket-scopes-to-the-dispatch-position
  (reg/install! (entry-spec "bucket-entry"))
  (reg/install! (redefine-spec "bucket-redef"))
  (let [entry  (reg/lookup "bucket-entry")
        redef  (reg/lookup "bucket-redef")
        eb     (reg/spec-bucket entry)
        rb     (reg/spec-bucket redef)]
    (is (= ["bucket-entry"] (mapv :id eb))
        "an :entry dispatch must not see the co-located :redefine spec")
    (is (= ["bucket-redef"] (mapv :id rb))
        "a :redefine dispatch must not see the co-located :entry spec")))

(deftest entry-dispatch-never-calls-a-redefine-bridge
  (testing "A :redefine bridge takes (self args method-name); every other
            position's bridge takes a single ctx. If an :entry dispatch
            walked a :redefine spec it would call that bridge with one
            argument and the fixed-arity bridge would throw."
    (let [calls (atom [])
          redef-bridge (fn [& xs] (swap! calls conj (vec xs)) :redefine)
          redef (redefine-spec "arity-redef")]
      (reg/install! (assoc redef :bridge redef-bridge))
      (reg/install! (entry-spec "arity-entry"))
      (dispatch/dispatch-for-spec "arity-entry" nil (object-array 0))
      (is (empty? @calls)
            "the :entry dispatch invoked no redefine bridge")
      (is (some? (reg/lookup "arity-redef"))
            "the redefine spec is still registered"))))
