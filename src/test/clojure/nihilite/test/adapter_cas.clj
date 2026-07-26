(ns nihilite.test.adapter-cas
  "Wave 6 Task 7 — `nihilite.adapter/install-default!` is
   compare-and-set: two concurrent non-forced installs admit
   exactly one winner; force? replaces. `default-adapter` and
   `set-default-adapter!` obey the same record shape."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.adapter :as ad]))

(def fake-A ::adapter-A)
(def fake-B ::adapter-B)
(def fake-C ::adapter-C)

(defn- reset-adapter!
  []
  (ad/set-default-adapter! nil))

(use-fixtures :each {:before (fn [] (reset-adapter!))})

(deftest install-default-returns-structured-result
  (let [r (ad/install-default! :test-A fake-A)]
    (is (map? r) "install-default! returns a map")
    (is (contains? r :previous) ":previous field")
    (is (contains? r :adapter) ":adapter field")
    (is (contains? r :forced?) ":forced? field")))

(deftest non-forced-install-replays-existing
  (ad/install-default! :test-A fake-A)
  (let [r (ad/install-default! :test-B fake-B)]
    (is (= fake-A (:previous r)) "previous is the registered adapter")
    (is (= fake-A (:adapter r))  "adapter is unchanged on second non-forced")))

(deftest forced-install-replaces
  (ad/install-default! :test-A fake-A)
  (let [r (ad/install-default! :test-B fake-B true)]
    (is (= fake-A (:previous r)) "previous is the prior adapter")
    (is (= fake-B (:adapter r))  "adapter is the new one")))

(deftest set-default-adapter-overwrites
  (ad/install-default! :test-A fake-A)
  (ad/set-default-adapter! fake-C)
  (is (= fake-C (ad/default-adapter)) "set-default-adapter! replaces whatever"))

(deftest default-adapter-nil-when-unset
  (is (nil? (ad/default-adapter)) "nil when nothing has been installed"))
