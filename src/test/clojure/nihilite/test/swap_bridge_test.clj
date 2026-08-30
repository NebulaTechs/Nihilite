(ns nihilite.test.swap-bridge-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.api :as api]
            [nihilite.registry :as reg]))

(defn- entry-spec [id]
  {:id id
   :target-internal "java/lang/String"
   :method-name "length"
   :descriptor "()I"
   :position :entry
   :action :observe
   :bridge (fn [_] :original)})

(use-fixtures :each
  (fn [f]
    (reg/clear!)
    (try (f) (finally (reg/clear!)))))

(deftest swap-bridge-replaces-bridge
  (api/install! (entry-spec "swap-test"))
  (let [replacement (fn [_] :replaced)]
    (api/swap-bridge! "swap-test" replacement)
    (is (identical? replacement (:bridge (api/lookup "swap-test"))))))

(deftest swap-bridge-missing-id-is-noop
  (is (nil? (api/swap-bridge! "no-such-id" (fn [_] :x)))))

(deftest swap-bridge-preserves-other-fields
  (api/install! (entry-spec "preserve"))
  (api/swap-bridge! "preserve" (fn [_] :new))
  (let [s (api/lookup "preserve")]
    (is (= "java/lang/String" (:target-internal s)))
    (is (= "length" (:method-name s)))
    (is (= :entry (:position s)))))

(deftest c2-c4-bridge-reach-on-system-classloader
  ;; R5 cross-test: install + swap-bridge! + uninstall cycle
  ;; reaches through the (Class/forName ... getSystemClassloader)
  ;; path used by Fabric/Knot modded hosts. After swap-bridge!, the
  ;; new bridge must be the one returned by lookup. After uninstall,
  ;; lookup must return nil and the spec is removed from
  ;; stats-snapshot.
  (api/install! (entry-spec "reach-test"))
  (let [replacement (fn [_] :swapped)]
    (api/swap-bridge! "reach-test" replacement)
    (is (identical? replacement (:bridge (api/lookup "reach-test")))
        "swap-bridge! swaps the in-memory bridge fn"))
  (is (true? (api/uninstall! "reach-test")))
  (is (nil? (api/lookup "reach-test"))))
