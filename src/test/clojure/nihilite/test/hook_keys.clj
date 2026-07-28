(ns nihilite.test.hook-keys
  "Method-key shape contract: the Java-side `HookKeys.build`
   and the Clojure-side `nihilite.registry/method-key` MUST
   produce identical strings for the same input triple
   `(class-internal, method-name, descriptor)`. The shape is
   fixed by the P0 plan: `<internal>` + `/` + `<method-name>`
   + `#` + `<descriptor>`. The separator `#` is not legal in
   any JVM-internal name, method name, or JLS descriptor, so
   the concatenation is unambiguous."
  (:require [clojure.test :refer [deftest is]]
            [nihilite.registry])
  (:import [nihilite.hooks HookKeys]))

(def ^:private internal     "net/minecraft/server/MinecraftServer")
(def ^:private method-name  "sendSystemMessage")
(def ^:private descriptor   "(Lnet/minecraft/network/chat/Component;Z)V")
(def ^:private expected-key
  (str internal "/" method-name "#" descriptor))

(deftest java-build-produces-canonical-shape
  (is (some? HookKeys)
      "HookKeys class is on the runtime classpath")
  (is (= expected-key
         (HookKeys/build internal method-name descriptor))
      "HookKeys/build concatenates with the canonical separator"))

(deftest clojure-method-key-produces-canonical-shape
  (is (= expected-key
         (nihilite.registry/method-key internal method-name descriptor))
      "nihilite.registry/method-key concatenates with the canonical separator"))

(deftest java-and-clojure-agree-on-empty-descriptor
  (let [k1 (HookKeys/build "x/y" "m" "()V")
        k2 (nihilite.registry/method-key "x/y" "m" "()V")]
    (is (= "x/y/m#()V" k1)
        "Java build for trivial triple")
    (is (= k1 k2)
        "Java and Clojure agree byte-for-byte on the trivial triple")))

(deftest java-and-clojure-agree-on-long-descriptor
  (let [d "(Lnet/minecraft/network/chat/Component;Z)V"
        k1 (HookKeys/build "net/minecraft/server/MinecraftServer"
                           "sendSystemMessage" d)
        k2 (nihilite.registry/method-key "net/minecraft/server/MinecraftServer"
                                         "sendSystemMessage" d)]
    (is (= k1 k2)
        "Java and Clojure agree byte-for-byte on the canonical MC triple")))