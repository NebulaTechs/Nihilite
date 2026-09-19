(ns nihilite.kernel.bytegen
  "Runtime ByteBuddy class generation. Replaces the AOT clojure.core$generate_class
   path for kernel classes that don't need the JVM before premain runs.

   Used by advice/dispatcher/transformer to materialise ByteBuddy-facing
   classes at first-use time, with method bodies that forward to plain
   Clojure functions in their impl-ns."
  (:import [net.bytebuddy ByteBuddy]
           [net.bytebuddy.dynamic DynamicType]
           [net.bytebuddy.dynamic.loading ClassLoadingStrategy$Default]
           [net.bytebuddy.description.annotation AnnotationDescription
                                                    AnnotationDescription$Builder]
           [net.bytebuddy.description.modifier ModifierContributor
                                                 ModifierContributor$ForMethod
                                                 Ownership
                                                 Visibility]
           [net.bytebuddy.description.type TypeDescription TypeDefinition TypeDescription$Generic]
           [net.bytebuddy.description.method MethodDescription ParameterDescription]
           [net.bytebuddy.implementation Implementation MethodCall]
           [net.bytebuddy.dynamic.scaffold TypeValidation]
           [java.lang.annotation Retention RetentionPolicy ElementType]))

(defn anno
  "Build an `AnnotationDescription` for the given annotation class, with
   the supplied element attributes (a map from element name → value).

   Values are auto-typed: String/Boolean/Class/Enum/AnnotationDescription.
   To override the auto-typed wrapper, pass a pre-built
   `AnnotationDescription` (use `anno` recursively)."
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
  "Resolve the class object used by ByteBuddy's `.defineMethod` for a
   parameter/return type. Accepts a Class (used as-is) or a fully-qualified
   class name string (resolved via Class/forName)."
  [t]
  (cond
    (class? t) t
    (string? t) (Class/forName t)
    :else (throw (ex-info "unsupported type spec" {:type t :class (class t)}))))

(defn- parameter-list
  "Build the Java Type[] for ByteBuddy's `defineMethod` parameter list."
  [param-types]
  (into-array TypeDefinition (map class-for-type param-types)))

(defn define-method
  "Define a single method on a ByteBuddy builder. `spec` is a map with:
     :name      string — method name
     :static?   boolean — defaults to false
     :return    class-or-string — return type
     :params    vector of class-or-string
     :param-annos  vector of AnnotationDescription (length must match :params)
     :method-annos vector of AnnotationDescription (default [])
     :body      Implementation (e.g. MethodDelegation.to(...))
                 — if nil, the method body is empty (returns default).
   Returns the builder unchanged for fluent chaining."
  [builder spec]
  (let [name ^String (:name spec)
        static? (boolean (:static? spec))
        return-type (class-for-type (:return spec))
        params (or (:params spec) [])
        param-annos (or (:param-annos spec) [])
        method-annos (or (:method-annos spec) [])
        modifiers (into-array ModifierContributor$ForMethod
                              (if static? [Ownership/STATIC] []))
        method-builder (-> builder
                           (.defineMethod name ^Class return-type modifiers)
                           (.withParameters (into-array TypeDefinition (mapv class-for-type params))))]
    ;; parameter annotations
    (dotimes [i (count param-annos)]
      (.annotateParameter method-builder i (nth param-annos i)))
    ;; method-level annotations
    (dotimes [i (count method-annos)]
      (.annotateMethod method-builder (nth method-annos i)))
    ;; method body
    (when-let [body (:body spec)]
      (.intercept method-builder body))
    nil))

(defn define-class!
  "Define a new class with ByteBuddy and load it. `spec` is:
     :name        string — fully-qualified class name
     :super       Class (default Object)
     :interfaces  vector of Class (optional)
     :methods     vector of method-specs (see define-method)
     :loader      ClassLoader to inject into (default system ClassLoader)
   Returns the loaded Class object."
  [spec]
  (let [name ^String (:name spec)
        super ^Class (or (:super spec) Object)
        interfaces (or (:interfaces spec) [])
        methods (or (:methods spec) [])
        loader ^ClassLoader (or (:loader spec) (ClassLoader/getSystemClassLoader))
        builder (-> (ByteBuddy.)
                    (.subclass super)
                    (.name name))]
    (loop [b builder, [iface & more] interfaces]
      (if iface
        (let [result (.implement b ^Class iface)]
          (recur result more))
        b))
    (reduce (fn [b m] (define-method b m) b) builder methods)
    (-> builder
        (.make)
        (.load loader ClassLoadingStrategy$Default/WRAPPER)
        (.getLoaded))))