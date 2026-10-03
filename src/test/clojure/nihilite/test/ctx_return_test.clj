(ns nihilite.test.ctx-return-test
  "ctx-return is the only public way to read a hooked method's return value
   (README, Hooks API section), and it is the one call site in the whole
   codebase that used defrecord field accessors. Clojure 1.12 does not emit
   those, so it compiled and then threw
   `IllegalArgumentException: No matching field found: getReturnValue` on
   every invocation -- once per read in a real `java -javaagent` JVM, which
   produced 122MB of ERROR logs and made the process unusable. Nothing caught
   it because no test called it and the only caller was an example.

   These cases exist so the accessor form cannot come back unnoticed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.registry.dispatch :as dispatch]
            [nihilite.test.fixtures :as fx]))

(use-fixtures :each fx/reg-cleanup)

(deftest ctx-return-reads-hook-event
  (let [ev (reg/map->HookEvent {:spec-id "s" :return-value 42})]
    (is (= 42 (reg/ctx-return ev))
        "HookEvent exposes :return-value")
    (is (= 42 (:return-value ev))
        "and it is the same value keyword access sees")))

(deftest ctx-return-reads-hook-context
  (let [ctx (reg/map->HookContext {:hook-id "s" :return-value "v"})]
    (is (= "v" (reg/ctx-return ctx))
        "HookContext uses the same kebab-case key as HookEvent")))

(deftest ctx-return-nil-when-absent
  (testing "a missing :return-value and a foreign object both read as nil"
    (is (nil? (reg/ctx-return (reg/map->HookEvent {:spec-id "s"}))))
    (is (nil? (reg/ctx-return (reg/map->HookContext {:hook-id "s"}))))
    (is (nil? (reg/ctx-return nil)))
    (is (nil? (reg/ctx-return "not-a-ctx")))))

(deftest ctx-return-survives-a-real-return-dispatch
  (testing "the bridge of an :observe :return hook sees the target's return
            value through ctx-return. This is the exact path the shipped
            agent broke, one call per target invocation."
    (let [id "ctx-return-live"
          seen (atom ::none)
          _ (reg/install! {:id              id
                           :target-internal "java/lang/String"
                           :method-name     "toString"
                           :descriptor      "()Ljava/lang/String;"
                           :position        :return
                           :action          :observe
                           :bridge          (fn [ctx] (reset! seen (reg/ctx-return ctx)))})
          result (dispatch/dispatch-return-for-spec id nil (object-array 0) "original")]
      (is (= "original" result)
          ":observe leaves the return value alone")
      (is (= "original" @seen)
          "the bridge read the target's return value"))))

(deftest event-to-context-roundtrip-preserves-return-value
  (testing "dispatch/->ctx converts HookEvent to HookContext; the kebab-case
            keys (:hook-id, :return-value) must survive that hop"
    (let [ev (dispatch/->hook-event {:id                  "spec-7"
                                     :position            :return
                                     :target-internal     "java/lang/String"
                                     :method-name         "toString"
                                     :source-descriptor   "()Ljava/lang/String;"
                                     :source-class        "java/lang/String"}
                                    "self" (object-array 0) "rv")
          ctx (dispatch/->ctx ev)]
      (is (instance? nihilite.registry.HookContext ctx))
      (is (= "spec-7" (:hook-id ctx)))
      (is (= "rv" (reg/ctx-return ctx)))
      (is (= "rv" (:return-value ctx))))))
