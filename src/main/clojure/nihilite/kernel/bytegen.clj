(ns nihilite.kernel.bytegen
  "Runtime ByteBuddy class generation. Replaces the AOT clojure.core$generate_class
   path for kernel classes that don't need the JVM before premain runs.

   Used by advice/dispatcher/transformer to materialise ByteBuddy-facing
   classes at first-use time, with method bodies that forward to plain
   Clojure functions in their impl-ns."
   (:import [net.bytebuddy ByteBuddy]
            [net.bytebuddy.dynamic.loading ClassLoadingStrategy$Default]
            [net.bytebuddy.description.annotation AnnotationDescription
                                                     AnnotationDescription$Builder]
            [net.bytebuddy.description.modifier ModifierContributor$ForMethod
                                                  Ownership]
            [net.bytebuddy.description.type TypeDefinition]
            [net.bytebuddy.jar.asm Type]))

(defn anno
  ([^Class anno-type]
   (anno anno-type {}))
  ([^Class anno-type attrs]
   (let [builder (AnnotationDescription$Builder/ofType anno-type)]
     (doseq [[k v] attrs]
       (cond
         (string? v)        (.define builder (name k) v)
         (instance? Boolean v) (.define builder (name k) v)
         (class? v)         (.define builder (name k) v)
         (instance? Enum v) (.define builder (name k) v)
         (instance? AnnotationDescription v) (.define builder (name k) v)
         :else (throw (ex-info "unsupported annotation value type" {:value v :class (class v)}))))
     (.build builder))))

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
        modifiers (into-array ModifierContributor$ForMethod
                              (if static? [Ownership/STATIC] []))
        mdb (.defineMethod builder name ^Class return-type modifiers)]
    (loop [b mdb
           i 0]
      (if (< i (count params))
        (let [t (class-for-type (nth params i))
              b2 (.withParameter b t)]
          (if (< i (count param-annos))
            (recur (annotate-parameter b2 (nth param-annos i)) (inc i))
            (recur b2 (inc i))))
        (let [body (or (:body spec) (empty-implementation))]
          (.intercept ^net.bytebuddy.dynamic.DynamicType$Builder$MethodDefinition$ReceiverTypeDefinition
                       b body))))))

(def ^:private method-annotation-spec
  (atom {}))

(defn- collect-method-annos! [spec]
  (let [name (str (:name spec))
        annos (or (:method-annos spec) [])]
    (when (seq annos)
      (swap! method-annotation-spec assoc name annos))))

(defn- annotation-descriptor
  [^AnnotationDescription ad]
  (str (.getDescriptor (.-type ad))))

(defn- annotation-attribute-value
  [v]
  (cond
    (string? v) v
    (instance? Boolean v) v
    (class? v) (Type/getType (str "L" (.getName ^Class v) ";"))
    (instance? Enum v) (str v)
    (instance? AnnotationDescription v) (annotation-descriptor v)
    :else (str v)))

(defn- annotation-class-visitor [class-visitor spec-map]
  (proxy [net.bytebuddy.jar.asm.ClassVisitor] [class-visitor]
    (visit [api access name desc sig exceptions]
      (.visit class-visitor api access name desc sig exceptions))
    (visitSource [source debug]
      (.visitSource class-visitor source debug))
    (visitModule [name access version]
      (.visitModule class-visitor name access version))
    (visitNestHost [nestHost]
      (.visitNestHost class-visitor nestHost))
    (visitOuterClass [owner name desc]
      (.visitOuterClass class-visitor owner name desc))
    (visitNestMember [nestMember]
      (.visitNestMember class-visitor nestMember))
    (visitPermittedSubclass [permittedSubclass]
      (.visitPermittedSubclass class-visitor permittedSubclass))
    (visitInnerClass [name outerName innerName access]
      (.visitInnerClass class-visitor name outerName innerName access))
    (visitRecordComponent [name mdesc msig]
      (.visitRecordComponent class-visitor name mdesc msig))
    (visitField [access fname fdesc fsig fvalue]
      (.visitField class-visitor access fname fdesc fsig fvalue))
    (visitMethod [access mname mdesc msig mexcs]
      (let [matched (get spec-map mname)
            ^net.bytebuddy.jar.asm.MethodVisitor new-mv
            (.visitMethod class-visitor access mname mdesc msig mexcs)]
        (when (and matched new-mv)
          (doseq [^AnnotationDescription ad matched]
            (let [^net.bytebuddy.jar.asm.AnnotationVisitor av
                  (.visitAnnotation new-mv (annotation-descriptor ad) true)]
              (when-let [values (.-elementValues ad)]
                (let [it (.iterator values)]
                  (while (.hasNext it)
                    (let [e (.next it)
                          k (.getKey e)
                          v (.getValue e)]
                      (.visit av (name k) (annotation-attribute-value v))))))
              (.visitEnd av))))
        new-mv))
    (visitAnnotation [desc visible]
      (.visitAnnotation class-visitor desc visible))
    (visitTypeAnnotation [typeRef typePath desc visible]
      (.visitTypeAnnotation class-visitor typeRef typePath desc visible))
    (visitAttribute [attribute]
      (.visitAttribute class-visitor attribute))
    (visitEnd []
      (.visitEnd class-visitor))))

(defn- build-annotation-visitor [spec-map]
  (proxy [net.bytebuddy.asm.AsmVisitorWrapper$AbstractBase] []
    (wrap [_type-desc class-visitor _impl-ctx _type-pool _fields _methods _writer-flags _reader-flags]
      (annotation-class-visitor class-visitor spec-map))))

(defn define-class!
  [spec]
  (reset! method-annotation-spec {})
  (let [name ^String (:name spec)
        super ^Class (or (:super spec) Object)
        interfaces (or (:interfaces spec) [])
        methods (or (:methods spec) [])
        loader ^ClassLoader (or (:loader spec) (ClassLoader/getSystemClassLoader))
        builder (-> (ByteBuddy.)
                    (.subclass super)
                    (.name name))]
    (let [b (reduce (fn [b m]
                      (collect-method-annos! m)
                      (define-method b m))
                    builder
                    methods)
          spec-map @method-annotation-spec]
      (let [b (if (seq spec-map)
                (.visit b ^net.bytebuddy.asm.AsmVisitorWrapper (build-annotation-visitor spec-map))
                b)]
        (-> b
            (.make)
            (.load loader ClassLoadingStrategy$Default/INJECTION)
            (.getLoaded))))))