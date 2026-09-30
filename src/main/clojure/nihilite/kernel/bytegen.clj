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

(defn- prim-desc
  "JVM descriptor for a primitive type."
  [^Class t]
  (case (.getName t)
    "boolean" "Z"
    "byte" "B"
    "char" "C"
    "short" "S"
    "int" "I"
    "long" "J"
    "float" "F"
    "double" "D"
    (throw (IllegalArgumentException. (str "not primitive: " t)))))

(defn- prim-unbox
  "Box class for a primitive return type, used to unpack the Object a
   Clojure fn returns into the primitive the generated method declares."
  [^Class t]
  (case (.getName t)
    "boolean" "Boolean"
    "byte" "Byte"
    "char" "Character"
    "short" "Short"
    "int" "Integer"
    "long" "Long"
    "float" "Float"
    "double" "Double"))

(defn- prim-return
  "The xRETURN opcode matching a primitive return type."
  [^Class t]
  (case (.getName t)
    "boolean" Opcodes/IRETURN
    "byte" Opcodes/IRETURN
    "char" Opcodes/IRETURN
    "short" Opcodes/IRETURN
    "int" Opcodes/IRETURN
    "long" Opcodes/LRETURN
    "float" Opcodes/FRETURN
    "double" Opcodes/DRETURN
    (throw (IllegalArgumentException. (str "not primitive: " t)))))

(defn- invoke-desc-for
  [param-classes]
  (let [n (count param-classes)
        objs (apply str (repeat n "Ljava/lang/Object;"))]
    (str "(" objs ")Ljava/lang/Object;")))

(defn- prim-box
  "Box class for a primitive type, or nil when the value is already a
   reference."
  [^Class t]
  (when (.isPrimitive t)
    (case (.getName t)
      "boolean" "Boolean"
      "byte" "Byte"
      "char" "Character"
      "short" "Short"
      "int" "Integer"
      "long" "Long"
      "float" "Float"
      "double" "Double")))

(defn- load-opcode
  "The xLOAD opcode for a value of the given type."
  [^Class t]
  (cond
    (identical? t Long/TYPE) Opcodes/LLOAD
    (identical? t Double/TYPE) Opcodes/DLOAD
    (identical? t Float/TYPE) Opcodes/FLOAD
    (or (identical? t Integer/TYPE)
        (identical? t Short/TYPE)
        (identical? t Byte/TYPE)
        (identical? t Character/TYPE)) Opcodes/ILOAD
    :else Opcodes/ALOAD))

(defn- param-opcode-at
  "Type of the value in local slot `i`, accounting for the receiver that an
   instance method keeps in slot 0 and for long/double taking two slots."
  [i param-classes static?]
  (let [slots (mapcat (fn [^Class t]
                        (if (or (identical? t Long/TYPE)
                                (identical? t Double/TYPE))
                          [t t]
                          [t]))
                      param-classes)
        locals (if static?
                 slots
                 (into [(Class/forName "java.lang.Object")] slots))]
    (if (< i (count locals))
      (nth locals i)
      (Class/forName "java.lang.Object"))))

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
   arg is the method being woven, used to size locals and stack.

   An instance method has the receiver in local 0, so its forwarder has to
   load one more local than it has declared parameters; otherwise the last
   parameter never reaches the target fn."
  [^clojure.lang.Symbol forward-var param-classes ^Class return-type ^String method-name
   & {:keys [stack-size static?]}]
  (let [ns-name  (str (symbol (namespace forward-var)))
        var-name (str (name forward-var))
        is-void  (identical? return-type Void/TYPE)
        n-args   (count param-classes)
        ;; The receiver occupies local 0 on an instance method, so the
        ;; forwarder passes it as the first argument and every declared
        ;; parameter after it.
        n-locals (if static? n-args (inc n-args))
        invoke-desc (invoke-desc-for (if static? param-classes
                                       (into [Object] param-classes)))
        msg (str method-name " " ns-name "/" var-name " not defined")
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
        load-local (fn [^MethodVisitor mv i]
                     ;; ALOAD only loads references, so a primitive parameter
                     ;; is loaded with its own opcode and then boxed: IFn.invoke
                     ;; takes Object, and leaving a primitive on the operand
                     ;; stack fails verification.
                     (let [^Class t (param-opcode-at i param-classes static?)]
                       (.visitVarInsn mv (load-opcode t) i)
                       (when-let [box (prim-box t)]
                         (call-method mv Opcodes/INVOKESTATIC
                                      (str "java/lang/" box)
                                      "valueOf"
                                      (str "(" (prim-desc t) ")Ljava/lang/"
                                           (prim-unbox t) ";")
                                      false))))
        ;; The appender must reserve a local slot for every value it loads
        ;; and a wide slot for the receiver plus any long/double argument, so
        ;; the local count is derived from the declared parameters rather than
        ;; from the operand stack. A too-small local count makes the emitted
        ;; frame claim a slot is a reference when it holds a primitive, which
        ;; fails verification at load time.
        local-size (or stack-size (+ 1 (reduce + (map #(if (or (identical? Long/TYPE %)
                                                             (identical? Double/TYPE %))
                                                          2 1)
                                                        param-classes))))
        size (ByteCodeAppender$Size. (or stack-size (+ 2 n-locals)) local-size)]
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
              (dotimes [i n-locals] (load-local mv i))
              (call-method mv Opcodes/INVOKEINTERFACE "clojure/lang/IFn"
                           "invoke"
                           invoke-desc true)
               (if is-void
                 (.visitInsn mv Opcodes/RETURN)
                 (do
                   ;; IFn.invoke is typed Object, but the generated method is
                   ;; declared with the spec's return type, so the JVM needs a
                   ;; narrowing conversion before the ARETURN. CHECKCAST takes
                   ;; an internal name, not a dotted one. A primitive return
                   ;; needs the boxed value unpacked rather than cast, so the
                   ;; generated method would fail verification otherwise.
                    (if (.isPrimitive return-type)
                      (let [unbox (prim-unbox return-type)]
                        (.visitTypeInsn mv Opcodes/CHECKCAST
                                       (str "java/lang/" unbox))
                        (call-method mv Opcodes/INVOKEVIRTUAL
                                     (str "java/lang/" unbox)
                                     (str (.getName return-type) "Value")
                                     (str "()" (prim-desc return-type))
                                     false))
                      (when-not (identical? return-type Object)
                        (.visitTypeInsn mv Opcodes/CHECKCAST
                                       (.replace (.getName return-type) "." "/"))))
                   (.visitInsn mv (if (.isPrimitive return-type)
                                    (prim-return return-type)
                                    Opcodes/ARETURN))))
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
                         (forwarder-implementation
                          forward-var param-classes return-type name
                          :stack-size (:stack-size spec)
                          :static? static?)
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

(def ^:private generated-bytes
  "class-name → byte[] for every class define-class! injected. The
   injected class leaves no classpath entry behind (ByteBuddy's injector
   deletes its temporary jar), so this map is the only reliable way for
   the transformer to obtain the advice bytecode later."
  (atom {}))

(defn- build-fields
  [builder fields]
  (reduce (fn [b {:keys [name type static? volatile?]}]
            (.defineField ^net.bytebuddy.dynamic.DynamicType$Builder b
                          ^String name
                          ^Class type
                          (cond-> [Visibility/PUBLIC]
                            static? (conj Ownership/STATIC)
                            volatile? (conj net.bytebuddy.description.modifier.FieldManifestation/VOLATILE))))
          builder
          fields))

(defn define-class!
  [spec]
  (reset! method-annotation-spec {})
  (let [name ^String (:name spec)
        super ^Class (or (:super spec) Object)
        interfaces (or (:interfaces spec) [])
        fields (or (:fields spec) [])
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
        b (-> (reduce (fn [b m]
                        (collect-method-annos! m)
                        (define-method b m))
                      builder
                      methods)
             (build-fields fields))
        spec-map @method-annotation-spec
        dynamic-type (-> b
                         (.visit ^AsmVisitorWrapper (if (seq spec-map)
                                                      (build-method-extension-writer spec-map)
                                                      (pass-through-writer)))
                         (.make))
        class-bytes (.getBytes dynamic-type)]
    (when save-dir
      (.mkdirs save-dir)
      (.saveIn dynamic-type ^java.io.File save-dir))
    ;; Keep the bytes reachable: the injected class leaves no classpath
    ;; entry behind (the injector deletes its temp jar), so the
    ;; transformer cannot read the advice class off the classpath later.
    (swap! generated-bytes assoc name class-bytes)
    (if inst
      (let [;; ClassInjector$UsingInstrumentation writes a temporary jar into
            ;; this directory the moment `of` is called, so the directory must
            ;; exist BEFORE the injector is constructed — creating it
            ;; afterwards loses the race and surfaces as
            ;; java.nio.file.NoSuchFileException on the temp jar.
            ;;
            ;; A fresh temp dir is used rather than a hard-coded target/
            ;; path: the agent also runs from a single uberjar where the
            ;; process CWD has no target/ directory.
            inject-dir (.toFile (java.nio.file.Files/createTempDirectory
                                  "nihilite-classes" (make-array java.nio.file.attribute.FileAttribute 0)))
            inject-target (or (:inject-target spec)
                              net.bytebuddy.dynamic.loading.ClassInjector$UsingInstrumentation$Target/SYSTEM)
            ^net.bytebuddy.dynamic.loading.ClassInjector injector
            (net.bytebuddy.dynamic.loading.ClassInjector$UsingInstrumentation/of
              ^java.io.File inject-dir
              inject-target
              inst)
            target-loader (ClassLoader/getSystemClassLoader)
          strategy (reify net.bytebuddy.dynamic.loading.ClassLoadingStrategy
                     (load [_ _cl types]
                       (.inject ^net.bytebuddy.dynamic.loading.ClassInjector injector types)))]
        (.getLoaded (.load ^net.bytebuddy.dynamic.DynamicType$Unloaded
                            dynamic-type target-loader strategy)))
      (throw (IllegalStateException.
               (str "bytegen/define-class! cannot inject " name
                    " into the system classloader without an Instrumentation. "
                    "Ensure Nihilite is installed via -javaagent or agentmain "
                    "(premain/agentmain must capture the Instrumentation)."))))))

(defn generated-class-bytes
  "Bytecode of an already-injected class, or nil when it was never
   generated in this JVM."
  [class-name]
  (get @generated-bytes class-name))