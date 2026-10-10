(ns nihilite.test.indy-driver
  "Proves an INVOKEDYNAMIC woven into a bootstrap-loader class fires.

   Target: java.io.FileInputStream.read (bootstrap loader). The advice
   class lives in the agent/app loader, so a normal INVOKESTATIC from the
   instrumented method cannot resolve it. The indy path routes the call
   through IndyBootstrapDispatcher, injected into the bootstrap loader,
   which holds the MethodHandle that reaches back into the agent.

   ByteBuddy AgentBuilder skips bootstrap classes, so the weaving layer is
   a raw ClassFileTransformer. The Advice implementation is itself a
   MethodVisitorWrapper, so the weave is just handing the target method's
   MethodVisitor to Advice.wrap. A generated ClassVisitor subclass does
   that hand-off, since Clojure cannot extend an abstract class.

   Invoked from build.clj as `java nihilite.test.indyDriver`."
  (:require [nihilite.crafter.bytegen :as bg]
            [nihilite.trainer.indy :as indy])
  (:import [java.lang.instrument ClassFileTransformer Instrumentation]
           [net.bytebuddy.agent ByteBuddyAgent]
           [net.bytebuddy.asm Advice Advice$BootstrapArgumentResolver
                               Advice$BootstrapArgumentResolver$Factory
                               Advice$OnMethodEnter
                               AsmVisitorWrapper$ForDeclaredMethods$MethodVisitorWrapper]
           [net.bytebuddy.description.method MethodDescription$InDefinedShape]
           [net.bytebuddy.description.type TypeDescription$ForLoadedType]
           [net.bytebuddy.dynamic ClassFileLocator$Simple]
           [net.bytebuddy.implementation Implementation$Context$Disabled$Factory
                                          Implementation$Context$ExtractableView]
           [net.bytebuddy.implementation.auxiliary AuxiliaryType$NamingStrategy$SuffixingRandom]
           [net.bytebuddy.jar.asm ClassReader ClassVisitor ClassWriter Opcodes]
           [net.bytebuddy.pool TypePool$Default]
           [net.bytebuddy.utility JavaConstant$Simple])
  (:gen-class
   :name nihilite.test.indyDriver
   :prefix "idrv-"
   :main true))

(def ^:private advice-class-name "nihilite.probe.IndyHookAdvice")

(def ^:private target-internal "java/io/FileInputStream")

(def ^:private target-descriptor "([BII)I")

(def ^:private target-loaded (Class/forName "java.io.FileInputStream"))

(def ^:private weaver-class-name "nihilite.probe.IndyWeaver")

(def fired (atom []))

(defn- fail! [why code]
  (println "indyDriver FAIL:" why)
  (System/exit code))

(defn ihk-onEntry
  [^String method-name ^Class host-class ^String descriptor _self _args]
  (swap! fired conj {:method method-name
                     :host (str host-class)
                     :loader (str (.getClassLoader host-class))
                     :descriptor descriptor})
  nil)

(def ^:private advice-spec
  {:name advice-class-name
   :methods [{:name "onEnter"
              :static? true
              :return "java.lang.Object"
              :params ["java.lang.String" "java.lang.Class" "java.lang.String"
                       "java.lang.Object" "[Ljava.lang.Object;"]
              :param-annos [(bg/anno net.bytebuddy.asm.Advice$Origin {:value "#m"})
                            (bg/anno net.bytebuddy.asm.Advice$Origin {})
                            (bg/anno net.bytebuddy.asm.Advice$Origin {:value "#d"})
                            (bg/anno net.bytebuddy.asm.Advice$This {:optional true})
                            (bg/anno net.bytebuddy.asm.Advice$AllArguments {})]
              :method-annos [(bg/anno Advice$OnMethodEnter {:inline false})]
              :forward-var 'nihilite.test.indy-driver/ihk-onEntry}]})

(defn- ensure-advice-class! [^Instrumentation inst]
  (bg/define-class! (assoc advice-spec :instrumentation inst :save-dir nil)))

(defn- advice-implementation [^Class advice]
  (let [locator (ClassFileLocator$Simple.
                 {advice-class-name (bg/generated-class-bytes advice-class-name)})
        factory (reify Advice$BootstrapArgumentResolver$Factory
                  (resolve [_ advice-method _is-exit]
                    (reify Advice$BootstrapArgumentResolver
                      (resolve [_ _instrumented-type _instrumented-method]
                        [(JavaConstant$Simple/ofLoaded "nihilite")
                         (JavaConstant$Simple/ofLoaded (.getDescriptor advice-method))
                         (JavaConstant$Simple/ofLoaded advice-class-name)]))))]
    (-> (Advice/withCustomMapping)
        (.bootstrap (indy/bootstrap-method) factory)
        (.to (TypeDescription$ForLoadedType/of advice)
             locator))))

(def ^:private impl-ref (atom nil))

(defn- implementation-context []
  (.make Implementation$Context$Disabled$Factory/INSTANCE
         (TypeDescription$ForLoadedType/of target-loaded)
         (AuxiliaryType$NamingStrategy$SuffixingRandom. "")
         net.bytebuddy.dynamic.scaffold.TypeInitializer$None/INSTANCE
         net.bytebuddy.ClassFileVersion/JAVA_V8
         net.bytebuddy.ClassFileVersion/JAVA_V8))

(defn- set-inner! [^ClassVisitor inner]
  (let [^java.lang.reflect.Field f
        (.getDeclaredField (Class/forName weaver-class-name) "inner")]
    (.setAccessible f true)
    (.set f nil inner)))

(defn iw-visitMethod
  "visitMethod of the generated ClassVisitor subclass. For the target
   method it routes the MethodVisitor that the delegate produced through the
   Advice implementation, so the indy call site lands in that method's body
   instead of the original code."
  [_self access ^String name ^String desc _sig ^"[Ljava.lang.String;" exs]
  (let [^java.lang.reflect.Field f
        (.getDeclaredField (Class/forName weaver-class-name) "inner")
        _ (.setAccessible f true)
        ^ClassVisitor inner (.get f nil)
        mv (.visitMethod inner
                          (int access) name desc _sig exs)]
    (if (= target-descriptor desc)
      (let [impl @impl-ref
            ctx ^Implementation$Context$ExtractableView (implementation-context)
            pool (TypePool$Default/ofSystemLoader)
            ;; read is overloaded, so the target has to be picked by
            ;; descriptor: matching on the name alone picks a different
            ;; overload depending on the JDK, and Advice then binds
            ;; arguments for a signature the call site does not have.
            ^java.lang.reflect.Method
            method (first (keep (fn [^java.lang.reflect.Method m]
                                 (when (= target-descriptor
                                        (.getDescriptor
                                          (net.bytebuddy.description.method.MethodDescription$ForLoadedMethod. m)))
                                   m))
                               (.getDeclaredMethods target-loaded)))
            ^MethodDescription$InDefinedShape
            md (net.bytebuddy.description.method.MethodDescription$ForLoadedMethod. method)]
        (.wrap ^AsmVisitorWrapper$ForDeclaredMethods$MethodVisitorWrapper
               impl
               (TypeDescription$ForLoadedType/of target-loaded)
               md
               ^net.bytebuddy.jar.asm.MethodVisitor mv
               ctx
               pool
               0 0))
      mv)))

(def ^:private weaver-spec
  {:name weaver-class-name
   :super ClassVisitor
   :fields [{:name "inner" :type ClassVisitor :static? true}]
   :methods [{:name "visitMethod"
              :return "net.bytebuddy.jar.asm.MethodVisitor"
              :params [Integer/TYPE String String String "[Ljava.lang.String;"]
              :forward-var 'nihilite.test.indy-driver/iw-visitMethod}]})

(defn iw-cinit [_self _api ^ClassVisitor inner]
  (.init Opcodes/ASM9 inner)
  nil)

(def ^:private weaver-ref (atom nil))

(defn- ensure-weaver! [^Instrumentation inst]
  (when (nil? @weaver-ref)
    (let [loaded (bg/define-class! (assoc weaver-spec
                                          :instrumentation inst
                                          :save-dir nil))
          ^Class c (if (class? loaded) loaded (.getLoaded loaded))]
      (reset! weaver-ref c)))
  @weaver-ref)

;; ClassFileTransformer gained a Module-arity transform in JDK 9, but that
;; overload is a default method whose body delegates to the ClassLoader-only
;; arity. Overriding only the 5-parameter form therefore covers both: JDK 9+
;; calls the Module arity, which the interface forwards here, and JDK 8 calls
;; this one directly.
(def ^:private transformer-spec
  {:name "nihilite.probe.IndyTransformer"
   :interfaces [ClassFileTransformer]
   :methods [{:name "transform"
              :return "[B"
              :params ["java.lang.ClassLoader" "java.lang.String" "java.lang.Class"
                       "java.security.ProtectionDomain" "[B"]
              :forward-var 'nihilite.test.indy-driver/it-transform}
             {:name "canRetransform" :return "boolean" :params []
              :forward-var 'nihilite.test.indy-driver/it-canRetransform}
             {:name "canRemove" :return "boolean" :params []
              :forward-var 'nihilite.test.indy-driver/it-canRemove}
             {:name "reset" :return "void" :params []
              :forward-var 'nihilite.test.indy-driver/it-reset}]})

(defn- weave! [^bytes classfile ^Instrumentation inst]
  (let [^Class weaver (ensure-weaver! inst)
        ctor (.getConstructor weaver (into-array Class [Integer/TYPE ClassVisitor]))
        _ (.setAccessible ctor true)
        inner (ClassWriter. 0)
        ^ClassVisitor wrapped (.newInstance ctor (into-array Object
                                                        [Opcodes/ASM9 inner]))]
    (set-inner! inner)
    (.accept (ClassReader. classfile) wrapped 0)
    (let [out (.toByteArray inner)]
      (with-open [fos (java.io.FileOutputStream.
                        (java.io.File/createTempFile "woven" ".class"))]
        (.write fos out))
      out)))

(def ^:private inst-ref (atom nil))

(defn- idrv-weave [nm ^bytes classfile]
  (if (= target-internal nm)
    (try
      (println "INDY_TRANSFORM" nm "in=" (alength classfile))
      (let [out (weave! ^bytes classfile @inst-ref)]
        (println "INDY_TRANSFORM out=" (when out (alength out)))
        out)
      (catch Throwable t
        (.println System/err "INDY_WEAVE_ERROR")
        (.printStackTrace t)
        nil))
    nil))

(defn it-transform [_self _loader nm _def _pd ^bytes classfile]
  (idrv-weave nm classfile))

(defn it-canRetransform [_self] true)

(defn it-canRemove [_self] false)

(defn it-reset [_self] nil)

(def ^:private transformer-ref (atom nil))

(defn- ensure-transformer! [^Instrumentation inst]
  (when (nil? @transformer-ref)
    (let [loaded (bg/define-class! (assoc transformer-spec
                                          :instrumentation inst
                                          :save-dir nil))
          ^Class c (if (class? loaded) loaded (.getLoaded loaded))]
      (reset! transformer-ref c)))
  @transformer-ref)

(defn idrv-main [& _args]
  (let [inst (ByteBuddyAgent/install)
        Agent (Class/forName "nihilite.trainer.Agent")
        premain (.getDeclaredMethod Agent "premain"
                                    (into-array Class [String Instrumentation]))]
    (.setAccessible premain true)
    (.invoke premain nil (object-array [nil inst]))
    (indy/install-bridge!)
    (reset! inst-ref inst)
    (reset! fired [])
    (let [advice (ensure-advice-class! inst)
           impl (advice-implementation advice)
          xform (.newInstance (ensure-transformer! inst))]
      (indy/preload-advice! advice-class-name "onEnter")
      (reset! impl-ref impl)
        (.addTransformer inst xform true)
        (println "INDY_TRANSFORMER registered, forcing retransform")
        (try
          (.retransformClasses inst
                               (into-array Class [target-loaded]))
          (println "retransformClasses OK")
          (catch Throwable t
            (.println System/err "retransform FAILED")
            (.printStackTrace t)))
        (let [f (java.io.File/createTempFile "nihilite-indy" ".bin")]
          (with-open [in (java.io.FileInputStream. f)]
            (let [buf (byte-array 16)]
              (.read in buf 0 16)))
          (.delete f))
        (if (empty? @fired)
          (fail! "no indy call site fired for java.io.FileInputStream.read" 1)
          (do
            (doseq [e @fired]
              (println "INDY_FIRED" (pr-str e)))
            (println "DRIVER_PASS indy call site fired in a"
                     " bootstrap-loader class")
            (System/exit 0))))))
