(ns nihilite.crafter.bytegen
  "Runtime ByteBuddy class generation. Replaces the AOT clojure.core$generate_class
   path for the agent's classes that don't need the JVM before premain runs.

   Used by advice/dispatcher/transformer to materialise ByteBuddy-facing
   classes at first-use time, with method bodies that forward to plain
   Clojure functions in their impl-ns.

   This namespace accounts for every remaining *warn-on-reflection* warning in
   src/, and deliberately so. Clojure's own guidance for a namespace with Java
   interop is to switch the warning on and see what it says
   (clojure.org/dev/screening_tickets); having run it, the residue is 22 calls
   whose receivers ByteBuddy returns as interfaces from other Java methods, so
   the compiler cannot know the concrete type. Suppressing each one would mean
   a type hint on nearly every builder call, kept in step with ByteBuddy's API
   by hand, to speed up code that runs four times per JVM: once per advice
   class. The guidance also says not to hint when it is not certain a hint
   helps, so these are left. Any reflection warning appearing OUTSIDE this
   namespace is a real finding -- the ones in crafter/jul and
   trainer/advice were, and were fixed."
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

(def ^:private primitive-table
  "The JVM's eight primitive types, each with the facts the bytecode generator
   needs about it: its Class, its descriptor, the box class that carries it,
   the return opcode that returns it, the load opcode that pushes it, and
   whether it occupies two stack slots.

   These were six parallel tables over the same eight names -- one for the
   name-to-Class mapping, one for the descriptor, one for the box class, one
   for the return opcode, a fifth that repeated the box class under a second
   name, and a sixth for the load opcode -- plus a seventh copy of the
   two-slot fact written twice inside the same function. A table per fact
   meant a new primitive had to be added in seven places, and the copies had
   already drifted: one of them threw on an unknown type while its twin
   returned nil.

   One entry per primitive, keyed by the type's name, so the facts cannot
   disagree about which type they describe. `void` is absent on purpose: it
   has no box class and no return opcode, because a method returning void
   returns without carrying a value. The two-slot fact is true only of long
   and double; the int-sized types share one 32-bit slot, so they share
   ILOAD too."
  {"boolean" {:class Boolean/TYPE :desc "Z" :box "Boolean" :return-opcode Opcodes/IRETURN :load-opcode Opcodes/ILOAD}
   "byte"    {:class Byte/TYPE    :desc "B" :box "Byte"    :return-opcode Opcodes/IRETURN :load-opcode Opcodes/ILOAD}
   "char"    {:class Character/TYPE :desc "C" :box "Character" :return-opcode Opcodes/IRETURN :load-opcode Opcodes/ILOAD}
   "short"   {:class Short/TYPE   :desc "S" :box "Short"   :return-opcode Opcodes/IRETURN :load-opcode Opcodes/ILOAD}
   "int"     {:class Integer/TYPE :desc "I" :box "Integer" :return-opcode Opcodes/IRETURN :load-opcode Opcodes/ILOAD}
   "long"    {:class Long/TYPE    :desc "J" :box "Long"    :return-opcode Opcodes/LRETURN :load-opcode Opcodes/LLOAD :wide? true}
   "float"   {:class Float/TYPE   :desc "F" :box "Float"   :return-opcode Opcodes/FRETURN :load-opcode Opcodes/FLOAD}
   "double"  {:class Double/TYPE  :desc "D" :box "Double"  :return-opcode Opcodes/DRETURN :load-opcode Opcodes/DLOAD :wide? true}})

(defn- primitive
  "The table entry for a primitive class, or nil when the class is not one of
   the eight the table knows.

   The lookup is by name rather than by identity so that `Integer/TYPE` and
   the string \"int\" reach the same entry."
  [^Class t]
  (get primitive-table (.getName t)))

(defn- primitive-class
  "The Class for a primitive type named by a string, or nil when the name is
   not one of the eight the table knows.

   `void` is the one primitive with no entry: it has no box class and no
   return opcode, because a method returning void returns without carrying
   a value."
  [name]
  (:class (get primitive-table name)))

(defn- wide?
  "True when a primitive occupies two stack slots. long and double do; the
   int-sized types do not."
  [^Class t]
  (boolean (:wide? (primitive t))))

(defn- prim-desc
  "JVM descriptor for a primitive type."
  [^Class t]
  (if-let [{:keys [desc]} (primitive t)]
    desc
    (throw (IllegalArgumentException. (str "not primitive: " t)))))

(defn- prim-box
  "Box class for a primitive type, or nil when the value is already a
   reference.

   The same box class names the type that carries a primitive through an
   Object -- so it is both what a primitive argument is boxed INTO and what a
   primitive return value is unpacked FROM."
  [^Class t]
  (:box (primitive t)))

(defn- prim-return
  "The xRETURN opcode matching a primitive return type."
  [^Class t]
  (if-let [{:keys [return-opcode]} (primitive t)]
    return-opcode
    (throw (IllegalArgumentException. (str "not primitive: " t)))))

(defn- load-opcode
  "The xLOAD opcode for a value of the given type.

   A reference type is pushed with ALOAD; a primitive with the opcode its
   table entry names."
  [^Class t]
  (if-let [{:keys [load-opcode]} (primitive t)]
    load-opcode
    Opcodes/ALOAD))

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
                      primitive? (if (= s "void")
                                   Void/TYPE
                                   (primitive-class s))]
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

(defn- param-opcode-at
  "Type of the value in local slot `i`, accounting for the receiver that an
   instance method keeps in slot 0 and for long/double taking two slots."
  [i param-classes static?]
  (let [slots (mapcat (fn [^Class t]
                        (if (wide? t)
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

   ```java
   Var v = Var.intern nsName, varName
   if v.isBound, Object impl = v.get
     if impl instanceof IFn,
       return ((IFn) impl).invoke args...   // void: just RETURN
   throw new UnsupportedOperationException methodName nsName varName
   ```

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
                     (let [^Class t (param-opcode-at i param-classes static?)]
                       (.visitVarInsn mv (load-opcode t) i)
                       (when-let [box (prim-box t)]
                         (call-method mv Opcodes/INVOKESTATIC
                                      (str "java/lang/" box)
                                      "valueOf"
                                      (str "(" (prim-desc t) ")Ljava/lang/"
                                           box ";")
                                      false))))
        ;; The appender must reserve a local slot for every value it loads
        ;; and a wide slot for the receiver plus any long/double argument, so
        ;; the local count is derived from the declared parameters rather than
        ;; from the operand stack. A too-small local count makes the emitted
        ;; frame claim a slot is a reference when it holds a primitive, which
        ;; fails verification at load time.
        local-size (or stack-size (+ 1 (reduce + (map #(if (wide? %) 2 1)
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
                      (let [unbox (prim-box return-type)]
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

(defn- define-method
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

(defn- method-annotation-map
  "Map from method name (string) → vector of AnnotationDescription, read off
   the method specs and consumed by the AsmVisitorWrapper pass that emits
   RuntimeVisibleAnnotations for matching methods.

   A pure function of the specs, not an accumulator in a global atom. It used
   to be an atom that define-class! reset on entry and filled as it walked
   the method list, which meant two concurrent define-class! calls raced: the
   second one's reset wiped the first one's annotations, and the first class
   came out with no @Advice$OnMethodEnter on it — an advice that silently
   does nothing, which is the failure mode this project cannot have."
  [methods]
  (reduce (fn [acc spec]
            (let [name (str (:name spec))
                  annos (or (:method-annos spec) [])]
              (if (seq annos) (assoc acc name annos) acc)))
          {}
          methods))

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
  "Generates one class from `spec` and injects it into the system classloader.

   `:save-dir` is where the generated .class is ALSO written to disk, for
   inspection. It is OFF by default, and that is deliberate: the default used
   to be `target/classes`, a RELATIVE path, so it resolved against the
   working directory of whatever JVM mounted the agent — the host
   application's directory, not Nihilite's. The agent wrote six class files
   into the application's working directory on every mount, and a JVM whose
   working directory was not writable could not mount the agent at all:
   ByteBuddy's saveIn threw `Could not create directory:
   target/classes/nihilite/trainer` out of premain and the JVM aborted with
   exit 134.

   Nothing reads those files. The injected class leaves no classpath entry
   behind (ByteBuddy's injector deletes its temporary jar), so the
   transformer reads the advice bytecode from `generated-class-bytes` instead
   — see `nihilite.trainer.transformer/advice-locator`. The bytes are kept
   in this JVM; the file on disk was never the source of truth.

   A caller that wants the file says where to put it: `:save-dir \"/tmp/x\"`."
  [spec]
  (let [name ^String (:name spec)
        super ^Class (or (:super spec) Object)
        interfaces (or (:interfaces spec) [])
        fields (or (:fields spec) [])
        methods (or (:methods spec) [])
        inst ^java.lang.instrument.Instrumentation (:instrumentation spec)
        ;; Absent means "do not write it anywhere". A caller that wants the
        ;; file names the directory; there is no implicit one.
        save-dir (:save-dir spec)
        builder (reduce (fn [b iface]
                          (.implement b ^net.bytebuddy.description.type.TypeDefinition
                                     (into-array net.bytebuddy.description.type.TypeDefinition
                                                 [(TypeDescription$ForLoadedType/of ^Class iface)])))
                        (-> (ByteBuddy.)
                            (.subclass super)
                            (.name name))
                        interfaces)
        b (-> (reduce (fn [b m] (define-method b m))
                      builder
                      methods)
             (build-fields fields))
        spec-map (method-annotation-map methods)
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
