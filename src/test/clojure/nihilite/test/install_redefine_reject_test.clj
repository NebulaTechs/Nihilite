(ns nihilite.test.install-redefine-reject-test
  "Regression: install! throws on {:position :redefine, :action :modify|:cancel}."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.test.fixtures :as fx]))

(defn- redefine-modify-spec [id]
  {:id id
   :target-internal "java/lang/String"
   :method-name "length"
   :descriptor "()I"
   :position :redefine
   :action :modify
   :bridge (fn [_] nil)})

(defn- redefine-cancel-spec [id]
  {:id id
   :target-internal "java/lang/String"
   :method-name "length"
   :descriptor "()I"
   :position :redefine
   :action :cancel
   :bridge (fn [_] nil)})

(defn- valid-observe-spec [id]
  {:id id
   :target-internal "java/lang/String"
   :method-name "length"
   :descriptor "()I"
   :position :entry
   :action :observe
   :bridge (fn [_] nil)})

(use-fixtures :each fx/reg-cleanup)

(deftest install-rejects-redefine-modify
  (let [spec (redefine-modify-spec "redefine-modify-test")]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #":action :modify\|:cancel invalid on :position :redefine"
         (reg/install! spec)))))

(deftest install-rejects-redefine-cancel
  (let [spec (redefine-cancel-spec "redefine-cancel-test")]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #":action :modify\|:cancel invalid on :position :redefine"
         (reg/install! spec)))))

(deftest install-allows-redefine-observe
  (let [spec (assoc (valid-observe-spec "redefine-observe-test")
                    :position :redefine)]
    (is (true? (reg/install! spec)))))

(deftest install-throws-have-correct-kind
  (let [ex (try (reg/install! (redefine-modify-spec "kind-test"))
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :nihilite/invalid-action-on-redefine
           (:nihilite/kind (ex-data ex)))
        "exception data carries :nihilite/invalid-action-on-redefine")))

(deftest install-fresh-throws-on-duplicate-id
  (reg/install! (valid-observe-spec "fresh-dup-test"))
  (is (thrown-with-msg?
        clojure.lang.ExceptionInfo
        #"already installed"
        (reg/install-fresh! (valid-observe-spec "fresh-dup-test")))))

(deftest install-fresh-succeeds-on-new-id
  (is (true? (reg/install-fresh!
              (valid-observe-spec "fresh-new-test")))))

(deftest install-rejects-invoke-position
  (let [spec (assoc (valid-observe-spec "invoke-reject-test")
                    :position :invoke-before)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #":position .* is reserved/removed"
         (reg/install! spec)))))
