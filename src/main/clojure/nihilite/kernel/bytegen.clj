(ns nihilite.kernel.bytegen
  "Runtime ByteBuddy class generation. Replaces the AOT clojure.core$generate_class
   path for kernel classes that don't need the JVM before premain runs.

   Used by advice/dispatcher/transformer to materialise ByteBuddy-facing
   classes at first-use time, with method bodies that forward to plain
   Clojure functions in their impl-ns."
(:require [clojure.java.io])
    (:import [net.bytebuddy ByteBuddy]
             [net.bytebuddy.description.annotation AnnotationDescription
                                                      AnnotationDescription$Builder]
            [net.bytebuddy.description.modifier ModifierContributor$ForMethod
                                                  Ownership Visibility]
            [net.bytebuddy.description.type TypeDescription$ForLoadedType]
            [net.bytebuddy.asm MemberAttributeExtension$ForMethod
                     AsmVisitorWrapper AsmVisitorWrapper$Compound]
            [net.bytebuddy.implementation.bytecode ByteCodeAppender
                                                    ByteCodeAppender$Size]
            [net.bytebuddy.jar.asm Label MethodVisitor Opcodes]
            [net.bytebuddy.matcher ElementMatchers]))

(defn- define-value
  "Call `AnnotationDescription$Builder.define` for the value's runtime type
   and return the NEW builder instance. ByteBuddy's AnnotationDescription$Builder
   is immutable: every define call returns a new builder, so the loop must
   thread the returned instance forward rather than mutating the original."
  [^AnnotationDescription$Builder builder ^String key value]
  (cond
    (string? value)
    (.define builder key value)
    (instance? Boolean value)
    (.define builder key ^boolean value)
    (class? value)
    (let [cls (Class/forName "net.bytebuddy.description.annotation.AnnotationDescription$Builder")
          m (.getDeclaredMethod cls "define" (into-array Class [java.lang.String java.lang.Class]))]
      (.setAccessible m true)
      (.invoke m builder (object-array [key value])))
    (instance? Enum value)
    (.define builder key ^Enum value)
    (instance? AnnotationDescription value)
    (.define builder key ^AnnotationDescription value)
    :else
    (throw (ex-info "unsupported annotation value type" {:value value :class (class value)}))))

(defn anno
  ([^Class anno-type]
   (anno anno-type {}))
  ([^Class anno-type attrs]
   (let [builder (AnnotationDescription$Builder/ofType anno-type)]
     (loop [^AnnotationDescription$Builder b builder
            [kv & rest] attrs]
       (if (nil? kv)
         (.build b)
         (let [k (first kv)
               v (second kv)]
           (recur (define-value b (name k) v) rest)))))))

(defn- class-for-type
  [t]
  (cond
    (class? t) t
    (string? t) (let [s t
                      primitive? (case s
                                   "void" Void/TYPE
                                   "boolean" Boolean/TYPE
                                   "byte" Byte/TYPE
                                   "char" Character/TYPE
                                   "short" Short/TYPE
                                   "int" Integer/TYPE
                                   "long" Long/TYPE
                                   "float" Float/TYPE
                                   "double" Double/TYPE
                                   nil)]
                  (cond
                    primitive? primitive?
                    (.startsWith s "[") (Class/forName s)
                    :else (Class/forName s)))
    :else (throw (ex-info "unsupported type spec" {:type t :class (class t)}))))

(defn- find-varargs-method
  [^Class target-class method-name params-classes]
  (first (filter (fn [^java.lang.reflect.Method mtd]
                   (and (= method-name (.getName mtd))
                        (= 1 (alength (.getParameterTypes mtd)))
                        (.isArray ^java.lang.Class
                                  (first (.getParameterTypes mtd)))
                        (= params-classes
                           (.getComponentType ^java.lang.Class
                                              (first (.getParameterTypes mtd))))))
                 (.getMethods target-class))))

(defn- invoke-varargs
  [target method-name params-classes args]
  (let [target-class (class target)
        m (find-varargs-method target-class method-name params-classes)]
    (when (nil? m)
      (throw (ex-info "no matching varargs method" {:method method-name
                                                     :class target-class
                                                     :param-array params-classes})))
    (.setAccessible m true)
    (.invoke m target (object-array [(into-array params-classes args)]))))

(defn- annotate-parameter [^net.bytebuddy.dynamic.DynamicType$Builder$MethodDefinition$ParameterDefinition$Simple$Annotatable mdb ann]
  (invoke-varargs mdb "annotateParameter" AnnotationDescription [ann]))

(defn- empty-implementation []
  net.bytebuddy.implementation.StubMethod/INSTANCE)

(defn- invoke-desc-for
  [param-classes]
  (let [n (count param-classes)
        objs (apply str (repeat n "Ljava/lang/Object;"))]
    (str "(" objs ")Ljava/lang/Object;")))

(defn- forwarder-implementation
  "Returns an `Implementation` (reify of Implementation) that emits a
   gen-class-style forwarder body. The bytecode it produces:

     Var v = Var.intern nsName, varName
     if v.isBound, Object impl = v.get
       if impl instanceof IFn,
         return ((IFn) impl).invoke args..., void: just RETURN
     throw new UnsupportedOperationException methodName nsName varName

   This is byte-buddy's low-level escape: a `ByteCodeAppender` that drives
   an ASM `MethodVisitor` (repackaged under `net.bytebuddy.jar.asm.*`).
   The `StackManipulation` combinators in `bytecode/` only model linear
   sequences with no branching. forwarder needs `ifeq` jumps, so it
   lives at the appender layer, which is byte-buddy's documented way to
   express control flow.

   The appender's `apply(MethodVisitor, Context, MethodDescription)` is
   the entry point byte-buddy invokes during class definition; the third
   arg is the method being woven, used to size locals and stack."
  [^clojure.lang.Symbol forward-var param-classes ^Class return-type ^String method-name]
  (let [ns-name  (str (symbol (namespace forward-var)))
        var-name (str (name forward-var))
        is-void  (identical? return-type Void/TYPE)
        invoke-desc (invoke-desc-for param-classes)
        n-args (count param-classes)
        msg (str method-name " " ns-name "/" var-name " not defined")
        load-local (fn [^MethodVisitor mv i]
                     (.visitVarInsn mv Opcodes/ALOAD i))
        call-method (fn [^MethodVisitor mv op ^String owner ^String name ^String desc iface?]
                     (let [^MethodVisitor mv mv
                           m (.getMethod MethodVisitor "visitMethodInsn"
                                         (into-array Class
                                                     [Integer/TYPE
                                                      String
                                                      String
                                                      String
                                                      Boolean/TYPE]))]
                       (.setAccessible m true)
                       (.invoke m mv (object-array [(int op) owner name desc (boolean iface?)]))))
        size (ByteCodeAppender$Size. (inc n-args) (inc n-args))]
    (reify net.bytebuddy.implementation.Implementation
      (prepare [_ inst-type] inst-type)
      (appender [_ _target]
        (proxy [ByteCodeAppender] []
          (apply [^MethodVisitor mv _ctx _method]
            (let [^MethodVisitor mv mv
                  l-unbound (Label.)
                  l-throw   (Label.)]
              (.visitLdcInsn mv ^Object ns-name)
              (call-method mv Opcodes/INVOKESTATIC "clojure/lang/Symbol"
                           "intern"
                           "(Ljava/lang/String;)Lclojure/lang/Symbol;" false)
              (.visitLdcInsn mv ^Object var-name)
              (call-method mv Opcodes/INVOKESTATIC "clojure/lang/Symbol"
                           "intern"
                           "(Ljava/lang/String;)Lclojure/lang/Symbol;" false)
              (call-method mv Opcodes/INVOKESTATIC "clojure/lang/Var"
                           "intern"
                           "(Lclojure/lang/Symbol;Lclojure/lang/Symbol;)Lclojure/lang/Var;"
                           false)
              (.visitInsn mv Opcodes/DUP)
              (call-method mv Opcodes/INVOKEVIRTUAL "clojure/lang/Var"
                           "isBound"
                           "()Z" false)
              (.visitJumpInsn mv Opcodes/IFEQ l-unbound)
              (call-method mv Opcodes/INVOKEVIRTUAL "clojure/lang/Var"
                           "get"
                           "()Ljava/lang/Object;" false)
              (.visitInsn mv Opcodes/DUP)
              (.visitTypeInsn mv Opcodes/INSTANCEOF "clojure/lang/IFn")
              (.visitJumpInsn mv Opcodes/IFEQ l-throw)
              (.visitTypeInsn mv Opcodes/CHECKCAST "clojure/lang/IFn")
              (dotimes [i n-args] (load-local mv i))
              (call-method mv Opcodes/INVOKEINTERFACE "clojure/lang/IFn"
                           "invoke"
                           invoke-desc true)
              (if is-void
                (.visitInsn mv Opcodes/RETURN)
                (.visitInsn mv Opcodes/ARETURN))
              (.visitFrame mv Opcodes/F_SAME1 0 nil 1 (into-array Object ["clojure/lang/Var"]))
              (.visitLabel mv l-unbound)
              (.visitInsn mv Opcodes/POP)
              (.visitTypeInsn mv Opcodes/NEW "java/lang/UnsupportedOperationException")
              (.visitInsn mv Opcodes/DUP)
              (.visitLdcInsn mv ^Object msg)
              (call-method mv Opcodes/INVOKESPECIAL "java/lang/UnsupportedOperationException"
                           "<init>"
                           "(Ljava/lang/String;)V" false)
              (.visitInsn mv Opcodes/ATHROW)
              (.visitFrame mv Opcodes/F_SAME1 0 nil 1 (into-array Object ["java/lang/Object"]))
              (.visitLabel mv l-throw)
              (.visitInsn mv Opcodes/POP)
              (.visitTypeInsn mv Opcodes/NEW "java/lang/UnsupportedOperationException")
              (.visitInsn mv Opcodes/DUP)
              (.visitLdcInsn mv ^Object msg)
              (call-method mv Opcodes/INVOKESPECIAL "java/lang/UnsupportedOperationException"
                           "<init>"
                           "(Ljava/lang/String;)V" false)
              (.visitInsn mv Opcodes/ATHROW))
            size))))))

(defn define-method
  "Define a single method on a ByteBuddy builder and return the result.
   intercept() on the MethodDefinition sub-builder does NOT mutate the
   outer type Builder — it returns a new Builder that represents the
   modified type, which callers must thread forward."
  [builder spec]
  (let [name ^String (:name spec)
        static? (boolean (:static? spec))
        return-type (class-for-type (:return spec))
        params (or (:params spec) [])
        param-annos (or (:param-annos spec) [])
        forward-var (:forward-var spec)
        modifiers (into-array ModifierContributor$ForMethod
                              (cond-> [Visibility/PUBLIC]
                                static? (conj Ownership/STATIC)))
        mdb (.defineMethod builder name ^Class return-type modifiers)]
    (loop [b mdb
           i 0]
      (if (< i (count params))
        (let [t (class-for-type (nth params i))
              b2 (.withParameter b t)]
          (if (< i (count param-annos))
            (recur (annotate-parameter b2 (nth param-annos i)) (inc i))
            (recur b2 (inc i))))
        (let [param-classes (mapv class-for-type params)
              body (or (:body spec)
                       (if forward-var
                         (forwarder-implementation forward-var param-classes return-type name)
                         (empty-implementation)))
              thrown (or (:throws spec) [])
              b (if (seq thrown)
                  (.throwing b (into-array Class (mapv class-for-type thrown)))
                  b)]
          (.intercept ^net.bytebuddy.dynamic.DynamicType$Builder$MethodDefinition$ReceiverTypeDefinition
                       b body))))))

(def ^:private method-annotation-spec
  "Map from method name (string) → vector of AnnotationDescription. Populated
   by define-class! from method specs and consumed by the AsmVisitorWrapper
   pass that emits RuntimeVisibleAnnotations for matching methods."
  (atom {}))

(defn- collect-method-annos! [spec]
  (let [name (str (:name spec))
        annos (or (:method-annos spec) [])]
    (when (seq annos)
      (swap! method-annotation-spec assoc name annos))))

(defn- build-method-extension-writer
  "Build an AsmVisitorWrapper that adds the annotations in `spec-map`
   to matching methods. Each entry is `method-name → [AnnotationDescription]`;
   the wrapper is composed of MemberAttributeExtension.ForMethod wrappers,
   one per method name, scoped by ElementMatchers.named(method-name)."
  [spec-map]
  (let [wrappers (for [[mname annos] spec-map]
                   ^AsmVisitorWrapper
                   (.on (.annotateMethod (MemberAttributeExtension$ForMethod.)
                                         (into-array AnnotationDescription annos))
                         (ElementMatchers/named mname)))]
    (AsmVisitorWrapper$Compound. (into-array AsmVisitorWrapper wrappers))))

(defn- pass-through-writer
  "Pass-through AsmVisitorWrapper used when no method-level annotations
   are required."
  []
  (reify AsmVisitorWrapper
    (mergeWriter [_ flags] flags)
    (mergeReader [_ flags] flags)
    (wrap [_ _type-desc class-visitor _impl-ctx _type-pool _fields _methods _writer-flags _reader-flags]
      class-visitor)))

(defn define-class!
  [spec]
  (reset! method-annotation-spec {})
  (let [name ^String (:name spec)
        super ^Class (or (:super spec) Object)
        interfaces (or (:interfaces spec) [])
        methods (or (:methods spec) [])
        inst ^java.lang.instrument.Instrumentation (:instrumentation spec)
        save-dir (or (:save-dir spec) (clojure.java.io/file "target" "classes"))
        builder (reduce (fn [b iface]
                          (.implement b ^net.bytebuddy.description.type.TypeDefinition
                                     (into-array net.bytebuddy.description.type.TypeDefinition
                                                 [(TypeDescription$ForLoadedType/of ^Class iface)])))
                        (-> (ByteBuddy.)
                            (.subclass super)
                            (.name name))
                        interfaces)
        b (reduce (fn [b m]
                    (collect-method-annos! m)
                    (define-method b m))
                  builder
                  methods)
        spec-map @method-annotation-spec
        dynamic-type (-> b
                         (.visit ^AsmVisitorWrapper (if (seq spec-map)
                                                      (build-method-extension-writer spec-map)
                                                      (pass-through-writer)))
                         (.make))]
    (when save-dir
      (.mkdirs save-dir)
      (.saveIn dynamic-type ^java.io.File save-dir))
    (if inst
      (let [^net.bytebuddy.dynamic.loading.ClassInjector injector
              (net.bytebuddy.dynamic.loading.ClassInjector$UsingInstrumentation/of
                (java.io.File. (str (clojure.java.io/file "target") "/nihilite-classes"))
                net.bytebuddy.dynamic.loading.ClassInjector$UsingInstrumentation$Target/SYSTEM
                inst)
            target-loader (ClassLoader/getSystemClassLoader)
          strategy (reify net.bytebuddy.dynamic.loading.ClassLoadingStrategy
                     (load [_ _cl types]
                       (.inject ^net.bytebuddy.dynamic.loading.ClassInjector injector types)))]
        (.mkdirs (java.io.File. (str (clojure.java.io/file "target") "/nihilite-classes")))
        (let [^net.bytebuddy.dynamic.DynamicType$Loaded loaded
                (.load ^net.bytebuddy.dynamic.DynamicType$Unloaded dynamic-type target-loader strategy)]
          (.getLoaded loaded)))
      (throw (IllegalStateException.
               (str "bytegen/define-class! cannot inject " name
                    " into the system classloader without an Instrumentation. "
                    "Ensure Nihilite is installed via -javaagent or agentmain "
                    "(premain/agentmain must capture the Instrumentation)."))))))