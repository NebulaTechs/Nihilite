(ns nihilite.test.dispatch-modified-test
  "Regression for `nihilite.registry.dispatch.return` bumping the
   per-spec `:modified` counter when a `:modify` action returns a
   non-nil value that wins the bucket.

   Part 2 of the Wire-up Stats series; mirror of the
   `:exceptions` regression in dispatch-exception-test."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.registry.dispatch :as d]
            [nihilite.registry.index :as ix]
            [nihilite.registry.stats :as stats]
            [nihilite.registry.event :as ev]))

(defn- install-modify [id bridge-fn]
  (reg/install! {:id                id
                 :target-internal   "java/lang/String"
                 :method-name       "toString"
                 :descriptor        "()Ljava/lang/String;"
                 :position          :return
                 :action            :modify
                 :bridge            bridge-fn})
  id)

(defn- fresh-state [f]
  (ix/clear-all!)
  (reg/clear!)
  (stats/clear!)
  (try (f)
       (finally
         (ix/clear-all!)
         (reg/clear!)
         (stats/clear!))))

(use-fixtures :each fresh-state)

(defn- modified-of [id]
  (some-> (stats/get-stats id) :modified deref))

(deftest dispatch-return-bumps-modified-on-non-nil
  (let [id "mod-test"
        _  (install-modify id (fn [_] "modified-value"))]
    (reg/dispatch-return-for-spec id nil (object-array 0) "original")
    (is (= 1 (modified-of id))
        ":modify winning with non-nil return increments :modified")))

(deftest dispatch-return-no-bump-on-nil-rv
  ;; :modify nil-rv → cond branch nil; winner unchanged; :modified stays 0.
  (let [id "mod-nil-test"
        _  (install-modify id (fn [_] nil))]
    (reg/dispatch-return-for-spec id nil (object-array 0) "original")
    (is (= 0 (modified-of id))
        ":modify returning nil does NOT bump :modified")))

(deftest dispatch-return-bumps-only-once-per-bucket
  ;; Two modify specs in the same method-key bucket; first wins,
  ;; second is short-circuited by `decided?` atom. Only first bumps.
  (let [id-a "mod-a"
        id-b "mod-b"]
    (install-modify id-a (fn [_] "a-wins"))
    (install-modify id-b (fn [_] "b-loses"))
    ;; Re-fetch bucket and trigger dispatch on either ID (bucket is shared).
    (reg/dispatch-return-for-spec id-a nil (object-array 0) "original")
    (let [b (d/spec-bucket (reg/lookup id-a))
          visited? (fn [spec] (some? spec))]
      (is (= 2 (count b))
          "shared bucket contains both specs")
      (is (= 1 (+ (modified-of id-a) (modified-of id-b)))
          "exactly one :modified increment for the whole bucket"))))
