(ns nihilite.test.api-facade-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.api :as api]
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
  (is (true? (api/uninstall! "facade-test")))
  (is (nil? (api/lookup "facade-test"))))

(deftest list-specs-returns-installed-ids
  (api/install! (entry-spec "alpha"))
  (api/install! (entry-spec "beta"))
  (is (= ["alpha" "beta"] (api/list-specs))))

(deftest list-specs-empty-when-clear
  (is (= [] (api/list-specs))))
