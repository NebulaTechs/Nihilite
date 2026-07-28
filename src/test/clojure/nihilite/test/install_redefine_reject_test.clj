(ns nihilite.test.install-redefine-reject-test
  "Regression for install! throwing on
   {:position :redefine, :action :modify|:cancel}. The plan's v3
   text was silent on this combo; the throw lands BEFORE the
   cancel-requires-entry branch so the most-specific error wins.

   Wave-1 T1 (P1.S1)."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.registry.index :as ix]
            [nihilite.registry.install :as install]))

;; Minimal spec fragments — only :id, :target-internal,
;; :method-name, :descriptor, :position, :action, :bridge are
;; exercised here; other optionals are default-defaulted.
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

;; Reset indexes + stats so tests don't see leakage.
(defn- fresh-state [f]
  (ix/clear-all!)
  (reg/clear!)
  (try (f)
       (finally
         (ix/clear-all!)
         (reg/clear!))))

(use-fixtures :each fresh-state)

(deftest install-rejects-redefine-modify
  (let [spec (redefine-modify-spec "redefine-modify-test")]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #":action :modify\|:cancel invalid on :position :redefine"
         (install/install! spec)))))

(deftest install-rejects-redefine-cancel
  (let [spec (redefine-cancel-spec "redefine-cancel-test")]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #":action :modify\|:cancel invalid on :position :redefine"
         (install/install! spec)))))

(deftest install-allows-redefine-observe
  ;; The throw targets :modify|:cancel — :observe on :redefine
  ;; continues to install without error (redefine's success
  ;; semantics are owned by the byte-buddy side; install does
  ;; not police observe-on-redefine).
  (let [spec (assoc (valid-observe-spec "redefine-observe-test")
                    :position :redefine)]
    (is (true? (install/install! spec)))))

(deftest install-throws-have-correct-kind
  (let [ex (try (install/install! (redefine-modify-spec "kind-test"))
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex) "exception was thrown")
    (is (= :nihilite/invalid-action-on-redefine
           (:nihilite/kind (ex-data ex)))
        "exception data carries :nihilite/invalid-action-on-redefine")))
