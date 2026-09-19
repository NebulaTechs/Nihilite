(ns nihilite.test.bytegen-hookadvice-test
  "Smoke test: prove bytegen-generated HookAdvice actually weaves a probe(int)
   method under AgentBuilder.RETRANSFORMATION. If this passes, the gen-class
   vs byte-buddy loader mismatch hypothesis is dead and we can replace
   advice.clj's gen-class path with bytegen."
  (:require [clojure.test :refer [deftest is]]
            [nihilite.kernel.bytegen :as bg]
            [nihilite.kernel.advice :as advice])
  (:import [net.bytebuddy ByteBuddy]
           [net.bytebuddy.agent ByteBuddyAgent]
           [net.bytebuddy.agent.builder AgentBuilder]
           [net.bytebuddy.asm Advice]
           [net.bytebuddy.matcher ElementMatchers]
           [net.bytebuddy.description.method MethodDescription]
           [net.bytebuddy.dynamic DynamicType DynamicType$Builder]
           [net.bytebuddy.dynamic.loading ClassLoadingStrategy$Default]
           [net.bytebuddy.description.type TypeDescription]
           [net.bytebuddy.description.annotation AnnotationDescription]
           [net.bytebuddy.implementation.bytecode.assign Assigner Assigner$Typing]))

(def dummy-class-name "nihilite.test.bytegen_hookadvice_test.ProbeTarget")

(defn- gen-probe-target []
  (bg/define-class!
   {:name dummy-class-name
    :methods [{:name "probe"
               :static? true
               :return "java.lang.String"
               :params ["int"]
               :body (fn [_] (fn [_x] "PROBE_ORIGINAL"))}]}))

(defn- gen-hook-advice-via-bytegen []
  (let [on-enter (bg/anno net.bytebuddy.asm.Advice$OnMethodEnter {:inline false})
        origin-m (bg/anno net.bytebuddy.asm.Advice$Origin {:value "#m"})
        origin-c (bg/anno net.bytebuddy.asm.Advice$Origin {})
        origin-d (bg/anno net.bytebuddy.asm.Advice$Origin {:value "#d"})
        this-anno (bg/anno net.bytebuddy.asm.Advice$This {:optional true})
        all-args (bg/anno net.bytebuddy.asm.Advice$AllArguments {})]
    (bg/define-class!
     {:name "nihilite.kernel.HookAdviceBytegen"
      :methods [{:name "onEntry"
                 :static? true
                 :return "void"
                 :params ["java.lang.String"
                          "java.lang.Class"
                          "java.lang.String"
                          "java.lang.Object"
                          "java.lang.Object[]"]
                 :param-annos [origin-m origin-c origin-d this-anno all-args]
                 :method-annos [on-enter]
                 :body nil}]})))

(defn- read-bytes [^DynamicType dt]
  (.getBytes dt))

(defn- dump-woven [^DynamicType dt out]
  (let [bytes (read-bytes dt)
        f (java.io.File. out)]
    (.mkdirs (.getParentFile f))
    (with-open [os (java.io.FileOutputStream. f)] (.write os bytes))
    (println "DUMPED" out "size=" (alength bytes))))

(deftest bytegen-hookadvice-smoke
  (let [target-cls (gen-probe-target)
        _ (Class/forName (.getName target-cls))
        hook-cls (gen-hook-advice-via-bytegen)
        hook-adv (Class/forName (.getName hook-cls))
        wcm (let [m (.getDeclaredMethod Advice "withCustomMapping"
                                         (into-array Class []))]
              (.setAccessible m true)
              (.invoke m nil (object-array [])))
        ppf-cls (Class/forName "net.bytebuddy.asm.Advice$AssignReturned$Factory")
        ctor (.getDeclaredConstructor ppf-cls (into-array Class []))
        _ (.setAccessible ctor true)
        ppf (.newInstance ctor (object-array []))
        advice-obj (-> ^Class Advice
                       (.to (.with wcm ppf) hook-adv))
        transformer (reify net.bytebuddy.agent.builder.AgentBuilder$Transformer
                     (transform [_this ^DynamicType$Builder b ^TypeDescription td cl mod pp]
                       (println "TRANSFORM called for" (.getName td))
                       (.visit ^DynamicType$Builder b (.on advice-obj
                                                         (ElementMatchers/named "probe")))))
        inst (ByteBuddyAgent/install)
        builder (net.bytebuddy.agent.builder.AgentBuilder$Default.)]
    (try
      (-> builder
          (.with AgentBuilder$RedefinitionStrategy/RETRANSFORMATION)
          (.with AgentBuilder$RedefinitionStrategy$DiscoveryStrategy$Reiterating/INSTANCE)
          (.type (ElementMatchers/named (.getName target-cls)))
          (.transform transformer)
          (.installOn inst))
      (Thread/sleep 200)
      (.retransformClasses inst (into-array Class [target-cls]))
      (Thread/sleep 200)
      (let [probe (.getDeclaredMethod target-cls "probe" (into-array Class [Integer/TYPE]))
            r (try (.invoke probe nil (object-array [(int 1)]))
                   (catch Throwable t (str "threw: " t)))]
        (println "PROBE result:" r)
        (is (= "PROBE_ORIGINAL" r)))
      (finally
        (try (.retransformClasses inst (into-array Class [target-cls])) (catch Throwable _))))))