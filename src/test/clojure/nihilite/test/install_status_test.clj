(ns nihilite.test.install-status-test
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
   :bridge (fn [_] nil)})

(use-fixtures :each fx/reg-cleanup)

(deftest status-unknown-id
  (let [s (reg/install-status! "never-installed")]
    (is (= "never-installed" (:spec-id s)))
    (is (false? (:registered? s)))
    (is (zero? (:woven-count s)))
    (is (false? (:pending? s)))
    (is (nil? (:last-error s)))))

(deftest status-after-install-without-agent
  (api/install! (entry-spec "status-test"))
  (let [s (reg/install-status! "status-test")]
    (is (true? (:registered? s)))
    (is (zero? (:woven-count s)))
    (is (true? (:pending? s)))
    (is (nil? (:last-error s)))))

(deftest status-after-uninstall-without-agent
  (api/install! (entry-spec "status-uninst"))
  (api/uninstall! "status-uninst")
  (let [s (reg/install-status! "status-uninst")]
    (is (false? (:registered? s)))
    (is (zero? (:woven-count s)))
    (is (false? (:pending? s)))
    (is (nil? (:last-error s)))))

(deftest status-after-replace
  (api/install! (entry-spec "status-replace"))
  (api/install! (assoc (entry-spec "status-replace") :note "second"))
  (let [s (reg/install-status! "status-replace")]
    (is (true? (:registered? s)))
    (is (zero? (:woven-count s)))))