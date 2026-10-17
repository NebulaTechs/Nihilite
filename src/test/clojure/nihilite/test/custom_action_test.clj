(ns nihilite.test.custom-action-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [nihilite.builder.api :as api]
            [nihilite.builder.registry :as reg]
            [nihilite.test.fixtures :as fx]))

(use-fixtures :each fx/reg-cleanup)

(deftest register-action-adds-to-known-set
  (testing "default actions are present"
    (is (contains? (reg/registered-actions) :observe))
    (is (contains? (reg/registered-actions) :modify))
    (is (contains? (reg/registered-actions) :cancel))
    (is (contains? (reg/registered-actions) :subscriber)))
  (testing "a new action becomes installable"
    (is (not (contains? (reg/registered-actions) :audit)))
    (reg/register-action! :audit)
    (is (contains? (reg/registered-actions) :audit))))

(deftest register-action-rejects-non-keyword
  (is (thrown? clojure.lang.ExceptionInfo
               (reg/register-action! "audit"))))

(deftest custom-action-installs-and-dispatches
  (reg/register-action! :audit)
  (let [spec {:id              "audit-hook"
              :target-internal "com/example/Foo"
              :method-name     "bar"
              :descriptor      "(I)V"
              :position        :entry
              :action          :audit
              :bridge          (fn [_ctx] :audited)}]
    (is (true? (reg/install! spec)))
    (is (= :audit (:action (reg/lookup "audit-hook"))))))

(deftest unknown-action-still-rejected
  (let [spec {:id              "bad-hook"
              :target-internal "com/example/Foo"
              :method-name     "bar"
              :descriptor      "(I)V"
              :position        :entry
              :action          :not-registered
              :bridge          (fn [_ctx] nil)}]
    (is (thrown? clojure.lang.ExceptionInfo
                 (reg/install! spec)))))

;; actions-registry is a defonce atom and fx/reg-cleanup calls reg/clear!,
;; which does not touch it. Every test below therefore restores what it
;; registered, or it would decide the outcome of register-action-adds-to-known-set
;; by ordering rather than by anything it asserts.

(deftest unregister-action-takes-back-what-register-action-added
  (let [k ::probe]
    (try
      (is (not (contains? (reg/registered-actions) k)))
      (reg/register-action! k)
      (is (contains? (reg/registered-actions) k))
      (is (true? (reg/unregister-action! k))
          "removing something that is there reports true")
      (is (not (contains? (reg/registered-actions) k)))
      (is (false? (reg/unregister-action! k))
          "and removing it again reports false, so this is safe to call twice")
      (finally
        (reg/unregister-action! k)))))

(deftest unregister-action-refuses-the-builtins
  ;; The dispatch layer branches on these in walk-bucket whether or not this
  ;; registry lists them, so taking one back would leave install! rejecting
  ;; specs whose :action the runtime still honours -- spec and runtime
  ;; disagreeing, which is the failure install-status! exists to rule out.
  (doseq [k [:observe :modify :cancel :subscriber]]
    (try
      (is (thrown? clojure.lang.ExceptionInfo (reg/unregister-action! k))
          (str k " cannot be taken back"))
      (is (contains? (reg/registered-actions) k)
          (str k " is still registered after the refusal"))
      (finally
        ;; Restored unconditionally, not only when the assertions pass. This
        ;; test is the one thing that would remove :observe from the registry
        ;; if the refusal ever regressed, and actions-registry is a defonce
        ;; atom that fx/reg-cleanup does not touch: an unrestored removal
        ;; turns every later install! into a failure and buries the single
        ;; real failure under a hundred of them.
        (reg/register-action! k)))))

(deftest an-action-taken-back-is-no-longer-installable
  (let [k    ::install-probe
        spec {:id              "taken-back-hook"
              :target-internal "com/example/Foo"
              :method-name     "bar"
              :descriptor      "(I)V"
              :position        :entry
              :action          k
              :bridge          (fn [_ctx] nil)}]
    (try
      (reg/register-action! k)
      (is (true? (reg/install! spec))
          "while registered, the action is installable")
      (reg/uninstall! "taken-back-hook")
      (reg/unregister-action! k)
      (is (thrown? clojure.lang.ExceptionInfo (reg/install! spec))
          "once taken back it is rejected exactly like any unknown action")
      (finally
        (reg/uninstall! "taken-back-hook")
        (reg/unregister-action! k)))))

(deftest the-facade-exports-the-whole-action-registry
  (let [k ::facade-probe]
    (try
      (is (contains? (api/registered-actions) :cancel)
          "the built-ins are visible through the facade, not only through reg")
      (is (not (contains? (api/registered-actions) k)))
      (is (= k (api/register-action! k)))
      (is (contains? (api/registered-actions) k))
      (is (true? (api/unregister-action! k)))
      (is (not (contains? (api/registered-actions) k)))
      (is (thrown? clojure.lang.ExceptionInfo (api/unregister-action! :cancel))
          "and the facade does not become a way around the builtin refusal")
      (finally
        ;; Same reasoning as unregister-action-refuses-the-builtins: if that
        ;; refusal regresses, this :cancel call is what would actually strip
        ;; it, and nothing else would put it back.
        (api/register-action! :cancel)
        (api/unregister-action! k)))))
