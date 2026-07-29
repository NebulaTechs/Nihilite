(ns nihilite.test.dispatch-exception-test
  "Regression tests for `nihilite.registry.event/dispatch-one!`
   bumping the per-spec `:exceptions` counter when an observer
   IFn throws.

   Previously `stats.exceptions` was permanently 0 because
   `bump-exception!` had no callers; the wiring here makes the
   counter observable."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.registry.event :as ev]
            [nihilite.registry.index :as ix]
            [nihilite.registry.spec :as rs]
            [nihilite.registry.stats :as stats]))

(defn- install-bridge
  "Install a bridge-fn under a fresh spec id; return the id so
   the test can dispatch against it."
  [id bridge-fn]
  (reg/install! {:id                id
                 :target-internal   "java/lang/String"
                 :method-name       "length"
                 :descriptor        "()I"
                 :position          :entry
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

(defn- exceptions-of [id]
  (some-> (stats/get-stats id) :exceptions deref))

(deftest dispatch-one-bumps-on-observer-throw
  (let [id      "ex-test"
        _       (install-bridge id (fn [_] (throw (ex-info "boom" {}))))
        ev     (rs/map->HookEvent {:spec-id      id
                                   :source       {:class "x" :method "m"
                                                  :descriptor "()V" :method-key "x/m#()V"}
                                   :phase        :entry
                                   :self         nil
                                   :args         (object-array 0)
                                   :return-value nil
                                   :cancelled?   (constantly false)
                                   :cancel!      (fn [_])
                                   :thread-name  "t"
                                   :timestamp-ns 0
                                   :sequence     1
                                   :note         nil
                                   :stack        nil})]
    (ev/dispatch-one! (:bridge (reg/lookup id)) ev)
    (is (= 1 (exceptions-of id))
        "single throw yields :exceptions = 1")))

(deftest dispatch-one-no-bump-on-clean-run
  (let [id      "ok-test"
        _       (install-bridge id (fn [_] nil))
        ev     (rs/map->HookEvent {:spec-id      id
                                   :source       {:class "x" :method "m"
                                                  :descriptor "()V" :method-key "x/m#()V"}
                                   :phase        :entry
                                   :self         nil
                                   :args         (object-array 0)
                                   :return-value nil
                                   :cancelled?   (constantly false)
                                   :cancel!      (fn [_])
                                   :thread-name  "t"
                                   :timestamp-ns 0
                                   :sequence     1
                                   :note         nil
                                   :stack        nil})]
    (dotimes [_ 5] (ev/dispatch-one! (:bridge (reg/lookup id)) ev))
    (is (= 0 (exceptions-of id))
        "no throw → :exceptions stays 0")))

(deftest sibling-spec-isolation
  ;; Two specs; spec-A throws, spec-B does not. After dispatch on BOTH,
  ;; A's :exceptions is 1, B's :exceptions is 0.
  (let [id-a   "iso-a"
        id-b   "iso-b"
        _a     (install-bridge id-a (fn [_] (throw (ex-info "boom-a" {}))))
        _b     (install-bridge id-b (fn [_] nil))
        ev-a   (rs/map->HookEvent {:spec-id      id-a
                                   :source       {:class "x" :method "m"
                                                  :descriptor "()V" :method-key "x/m#()V"}
                                   :phase        :entry
                                   :self         nil
                                   :args         (object-array 0)
                                   :return-value nil
                                   :cancelled?   (constantly false)
                                   :cancel!      (fn [_])
                                   :thread-name  "t"
                                   :timestamp-ns 0
                                   :sequence     1
                                   :note         nil
                                   :stack        nil})
        ev-b   (assoc ev-a :spec-id id-b)]
    (ev/dispatch-one! (:bridge (reg/lookup id-a)) ev-a)
    (dotimes [_ 3] (ev/dispatch-one! (:bridge (reg/lookup id-b)) ev-b))
    (is (= 1 (exceptions-of id-a))
        "spec-A's spec-id is the bump key")
    (is (= 0 (exceptions-of id-b))
        "spec-B's :exceptions stays 0 despite spec-A's throw")))

(deftest nil-bridge-does-not-bump
  ;; nil ifn is handled before the try/catch → no exception path,
  ;; no bump. This is the contract test for `dispatch-one!`'s
  ;; nil-guard short-circuit.
  (let [id      "nil-bridge-test"
        _       (install-bridge id (fn [_] nil))
        ev     (rs/map->HookEvent {:spec-id      id
                                   :source       {:class "x" :method "m"
                                                  :descriptor "()V" :method-key "x/m#()V"}
                                   :phase        :entry
                                   :self         nil
                                   :args         (object-array 0)
                                   :return-value nil
                                   :cancelled?   (constantly false)
                                   :cancel!      (fn [_])
                                   :thread-name  "t"
                                   :timestamp-ns 0
                                   :sequence     1
                                   :note         nil
                                   :stack        nil})]
    (is (= :nihilite.registry.event/no-return (ev/dispatch-one! nil ev))
        "nil ifn returns :no-return sentinel")
    (is (= 0 (exceptions-of id))
        "nil ifn does not bump :exceptions")))
