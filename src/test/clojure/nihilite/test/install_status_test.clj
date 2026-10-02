(ns nihilite.test.install-status-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.api :as api]
            [nihilite.registry :as reg]
            [nihilite.registry.dispatch :as dispatch]
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

(deftest status-reports-runtime-counters
  (api/install! (entry-spec "status-runtime"))
  (let [s (reg/install-status! "status-runtime")]
    (is (contains? s :fired) "install-status! must report :fired, not just install-side state")
    (is (contains? s :modified))
    (is (contains? s :cancelled))
    (is (contains? s :exceptions))
    (is (zero? (:fired s)))))

(deftest status-unknown-id-reports-zero-counters
  (let [s (reg/install-status! "never-installed")]
    (is (zero? (:fired s)))
    (is (zero? (:modified s)))
    (is (zero? (:cancelled s)))
    (is (zero? (:exceptions s)))))

(deftest status-fired-tracks-real-dispatch
  (api/install! (entry-spec "status-fired"))
  (is (zero? (:fired (reg/install-status! "status-fired"))))
  (dispatch/dispatch-for-spec "status-fired" nil (object-array 0))
  (dispatch/dispatch-for-spec "status-fired" nil (object-array 0))
  (is (= 2 (:fired (reg/install-status! "status-fired")))))