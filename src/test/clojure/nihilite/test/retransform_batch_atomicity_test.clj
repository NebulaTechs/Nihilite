(ns nihilite.test.retransform-batch-atomicity-test
  "retransform is batch-atomic in HotSpot: every channel into
   Instrumentation#retransformClasses routes into one VM_RedefineClasses,
   so a single illegal class fails the entire batch. retransform-loaded-
   matching! therefore calls it once per class, logs each failure with its
   class name, and throws a summary after the rest have been processed.

   Contract tests run without a live Instrumentation, so these drive a
   stub implementing only the three methods the function touches. The
   multi-class case uses one name loaded twice through
   ClassLoadingStrategy/WRAPPER, which is how a single name legitimately
   maps to more than one loaded Class."
  (:require [clojure.test :refer [deftest is]]
            [nihilite.builder.registry :as reg])
  (:import [net.bytebuddy ByteBuddy]))

(defn- stub-instrumentation
  "Returns [calls-atom instrumentation]. `failing` is a set of class names
   for which retransformClasses throws; each call records the class names
   it received."
  [classes failing]
  (let [calls (atom [])]
    [calls
     (reify java.lang.instrument.Instrumentation
       (getAllLoadedClasses [_] (into-array Class (vec classes)))
       (isModifiableClass [_ _c] true)
       (retransformClasses [_ cs]
         (let [names (mapv #(.getName ^Class %) (seq cs))]
           (swap! calls conj names)
             (let [rejected (filter failing names)]
               (when (seq rejected)
                 (throw (UnsupportedOperationException.
                          (str "retransform rejected " (first rejected)))))))))]))

(def ^:private dup-target "dup.Target")

(defn- load-twice []
  (let [make (fn []
               (-> (ByteBuddy.) (.subclass Object) (.name dup-target) (.make)
                   (.load (ClassLoader/getSystemClassLoader)
                          net.bytebuddy.dynamic.loading.ClassLoadingStrategy$Default/WRAPPER)
                   .getLoaded))]
    [(make) (make)]))

(deftest failure-attributes-the-class-and-still-throws
  (let [[calls inst] (stub-instrumentation [String] #{"java.lang.String"})
        thrown (try (reg/retransform-loaded-matching! "java/lang/String" inst)
                    (catch clojure.lang.ExceptionInfo e e))]
    (is (some? thrown) "a retransform failure must surface, not be swallowed")
    (is (= "java/lang/String" (:target (ex-data thrown))))
    (is (= 1 (count (:failed (ex-data thrown)))))
    (is (= "java.lang.String" (ffirst (:failed (ex-data thrown)))))
    (is (re-find #"retransform rejected java\.lang\.String"
                 (second (first (:failed (ex-data thrown))))))
    (is (= [["java.lang.String"]] @calls) "one retransform call per class")))

(deftest one-bad-class-does-not-abandon-the-others
  (let [[calls inst] (stub-instrumentation (load-twice) #{})
        result (reg/retransform-loaded-matching! dup-target inst)]
    (is (= 2 result) "both same-named classes counted")
    (is (= 2 (count @calls)) "each class got its own retransform call")
    (is (every? #(= [dup-target] %) @calls) "no call carried more than one class")))

(deftest summary-throws-after-processing-every-class
  (let [[calls inst] (stub-instrumentation (load-twice) #{dup-target})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"retransform failed for 2 class"
                          (reg/retransform-loaded-matching! dup-target inst)))
    (is (= 2 (count @calls)) "the rejected classes were still attempted individually")))

(deftest success-returns-count-without-throw
  (let [[calls inst] (stub-instrumentation [String] #{})]
    (is (= 1 (reg/retransform-loaded-matching! "java/lang/String" inst)))
    (is (= [["java.lang.String"]] @calls))))

(deftest nil-instrumentation-returns-zero
  (is (zero? (reg/retransform-loaded-matching! "java/lang/String" nil))))

(deftest no-matching-class-makes-no-call
  (let [[calls inst] (stub-instrumentation [String] #{})]
    (is (zero? (reg/retransform-loaded-matching! "com/example/Nope" inst)))
    (is (empty? @calls))))
