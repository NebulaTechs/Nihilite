(ns nihilite.test.compiler-loader-hint-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.test.fixtures :as fx])
  (:import [java.lang.reflect Method]))

(def ^:private hint-prop "nihilite.compiler-loader-hint")

(defn- call-resolve
  ^ClassLoader []
  (let [^Method m (.getDeclaredMethod
                    (Class/forName "nihilite.agent.Worker")
                    "resolveHostClassLoader"
                    (into-array Class []))]
    (.setAccessible m true)
    (.invoke m nil (object-array []))))

(defn- with-prop [v f]
  (let [had? (some? (System/getProperty hint-prop))
        prev (System/getProperty hint-prop)]
    (try
      (if (nil? v)
        (System/clearProperty hint-prop)
        (System/setProperty hint-prop v))
      (f)
      (finally
        (if had?
          (System/setProperty hint-prop prev)
          (System/clearProperty hint-prop))))))

(use-fixtures :each fx/reg-cleanup)

(deftest no-hint-returns-system-loader
  (with-prop nil
    (fn []
      (is (identical? (ClassLoader/getSystemClassLoader) (call-resolve))))))

(deftest hint-without-instrumentation-falls-back
  (with-prop "net/minecraft/server/MinecraftServer"
    (fn []
      (let [cl (call-resolve)]
        (is (identical? (ClassLoader/getSystemClassLoader) cl))))))

(deftest bogus-hint-without-instrumentation-falls-back
  (with-prop "totally/not/a/real/Class$InThisJvm"
    (fn []
      (let [cl (call-resolve)]
        (is (identical? (ClassLoader/getSystemClassLoader) cl))))))

(deftest hint-resolves-to-loaded-class-loader
  (with-prop "nihilite/test/compiler_loader_hint_test$HintTarget"
    (fn []
      (let [resolved (call-resolve)
            expected (ClassLoader/getSystemClassLoader)]
        (is (some? resolved))
        (is (identical? expected resolved))))))