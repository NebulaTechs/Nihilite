(ns nihilite.test.api-facade-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.builder.api :as api]
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

(deftest install-uninstall-roundtrip
  (is (true? (api/install! (entry-spec "facade-test"))))
  (is (some? (api/lookup "facade-test")))
  ;; install! returning true is a different claim from the registry describing
  ;; the hook as installed, and only the second is what a user reads. Without
  ;; this the roundtrip passes while the registry holds a spec the status calls
  ;; absent -- install! removes nothing on a failed retransform, so mark-installed!
  ;; never runs and the spec stays live in by-id with :registered? still false.
  ;; The weaving half of that invariant needs real Instrumentation and is pinned
  ;; by nihilite.test.weaving-contract-driver; this is the facade half.
  (let [s (api/install-status! "facade-test")]
    (is (true? (:registered? s)))
    (is (nil? (:last-error s))))
  (is (true? (api/uninstall! "facade-test")))
  (is (nil? (api/lookup "facade-test")))
  ;; ...and after removal the status must stop claiming it is installed.
  ;; mark-uninstalled! (registry.clj:337-342) is the only writer of that key,
  ;; so a throw between the removal and the status write leaves it true forever.
  (is (false? (:registered? (api/install-status! "facade-test")))))

(deftest list-specs-returns-installed-ids
  (api/install! (entry-spec "alpha"))
  (api/install! (entry-spec "beta"))
  (is (= ["alpha" "beta"] (api/list-specs))))

(deftest list-specs-empty-when-clear
  (is (= [] (api/list-specs))))
