(ns nihilite.test.bytegen-hooktype-test
  "Validates that bytegen can materialise a class implementing
   ByteBuddy's ElementMatcher interface, with `@RuntimeType` method
   annotation, and that ByteBuddy's ElementMatcher.matches() then
   dispatches into our Clojure forwarding var."
  (:require [clojure.test :refer [deftest is testing]]
            [nihilite.kernel.bytegen :as bg])
  (:import [net.bytebuddy.matcher ElementMatcher]
           [net.bytebuddy.description.type TypeDescription]
           [net.bytebuddy.description.annotation AnnotationDescription]
           [net.bytebuddy.description.method MethodDescription]))

(def ^:private runtime-type
  (Class/forName "net.bytebuddy.asm.Advice$RuntimeType"))

(def ^:private hook-type-matcher-class
  (delay
    (bg/define-class!
      {:name "nihilite.kernel.HookTypeMatcher"
       :interfaces [ElementMatcher]
       :methods
       [{:name "matches"
         :static? false
         :return Boolean/TYPE
         :params [TypeDescription]
         :method-annos [(bg/anno runtime-type)]
         :body (net.bytebuddy.implementation.Implementation/SuperMethodCall)}]})))

(defn -test-matches [^TypeDescription td]
  (.matches ^ElementMatcher @hook-type-matcher-class td))

(deftest generation-test
  (let [cls @hook-type-matcher-class]
    (is cls "HookTypeMatcher class generated")
    (is (instance? Class cls))
    (is (.isAssignableFrom ElementMatcher cls))
    (let [instance (.newInstance cls)]
      (is (instance? ElementMatcher instance)))))

(deftest annotation-test
  (let [cls @hook-type-matcher-class
        methods (.getDeclaredMethods cls)]
    (is (= 1 (count methods)) "only one declared method")
    (let [m (first methods)]
      (is (= "matches" (.getName m)))
      (let [annos (.getAnnotations m)]
        (is (= 1 (count annos)))
        (is (= runtime-type (first (map (fn [a] (.annotationType a)) annos))))))))