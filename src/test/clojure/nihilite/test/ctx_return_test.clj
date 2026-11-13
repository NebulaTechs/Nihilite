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
            [nihilite.builder.registry :as reg]
            [nihilite.builder.registry.dispatch :as dispatch]
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

(deftest ctx-cancel-through-a-context-works
  (testing "cancelling through a HookContext used to throw
            IllegalAccessError: Update to non-static final field
            HookContext.cancelled attempted from a different class. A
            defrecord's fields are final, so ctx-cancel! could not write
            the boolean it was asked to write."
    (let [ev (dispatch/->hook-event {:id                  "spec-cancel"
                                     :position            :entry
                                     :target-internal     "java/lang/String"
                                     :method-name         "toString"
                                     :method-descriptor   "()Ljava/lang/String;"}
                                    "self" (object-array 0) nil)
          ctx (dispatch/->ctx ev)]
      (is (false? (dispatch/ctx-cancelled? ctx))
          "a fresh context is not cancelled")
      (is (true? (dispatch/ctx-cancel! ctx true))
          "the cancellation is delivered")
      (is (true? (dispatch/ctx-cancelled? ctx))
          "and the context reads back as cancelled")
      (is (true? ((:cancelled? ev)))
          "the context shares the event's cancellation state"))))

(deftest ctx-cancel-through-a-hand-made-context-says-it-cannot
  (testing "a HookContext built by hand carries no cancellation channel, so
            ctx-cancel! reports that rather than pretending or throwing"
    (let [ctx (reg/map->HookContext {:hook-id "s" :cancelled false})]
      (is (false? (dispatch/ctx-cancel! ctx true))
          "no channel, so nothing was delivered")
      (is (false? (dispatch/ctx-cancelled? ctx))))))

(deftest event-to-context-roundtrip-preserves-return-value
  (testing "dispatch/->ctx converts HookEvent to HookContext; the kebab-case
            keys (:hook-id, :return-value) must survive that hop"
    (let [ev (dispatch/->hook-event {:id                  "spec-7"
                                     :position            :return
                                     :target-internal     "java/lang/String"
                                     :method-name         "toString"
                                     :method-descriptor   "()Ljava/lang/String;"}
                                    "self" (object-array 0) "rv")
          ctx (dispatch/->ctx ev)]
      (is (instance? nihilite.builder.registry.HookContext ctx))
      (is (= "spec-7" (:hook-id ctx)))
      (is (= "rv" (reg/ctx-return ctx)))
      (is (= "rv" (:return-value ctx))))))
