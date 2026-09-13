(ns nihilite.kernel.dispatcher
  "MethodDelegation dispatcher and assigner for :redefine hooks, generated
   from Clojure.

   Two classes are produced into *compile-path* during AOT:
     - nihilite.kernel.GenericDispatcher  @RuntimeType/@Origin/@This/@AllArguments
       static dispatch method; its stub forwards to the dispatch var below,
       which delegates to the redefine dispatcher installed by
       nihilite.registry.dispatch/install-redefine-dispatcher! (the :redefine-dispatcher-ref
       atom in that namespace).
     - nihilite.kernel.DynamicAssigner implements
       net.bytebuddy.implementation.bytecode.assign.Assigner; its assign
       stub forwards to the gd-assign var.

    Both are emitted via the private generate-class function because reify
    cannot implement ByteBuddy's nested-generic Assigner interface and the
    ns gen-class form spec rejects array parameter types."
  (:require [nihilite.kernel.classgen :as cg]
            [nihilite.kernel.annparam :as ap]))

(defn- extract-method-name [^String sig]
  (let [paren (.indexOf ^String sig "(")
        last-dot (.lastIndexOf ^String sig "." (if (< paren 0) (.length ^String sig) paren))]
    (if (< paren 0)
      (.substring ^String sig (inc last-dot))
      (.substring ^String sig (inc last-dot) paren))))

(defn- extract-descriptor [^String sig]
  (let [paren (.indexOf ^String sig "(")]
    (when (> paren 0)
      (.substring ^String sig paren))))

(defn dispatch
  "Generated GenericDispatcher dispatch stub. host-class and method-sig
   come from @Origin annotations; self from @This(optional=true); args
   from @AllArguments. Delegates to the redefine dispatcher installed
   by nihilite.registry.dispatch/install-redefine-dispatcher!; throws when the
   worker has not yet booted (dispatcher null)."
  [^java.lang.Class host-class ^String method-sig ^Object self ^[Object] args]
  (let [host (ap/host-internal host-class)
        m-name (extract-method-name method-sig)
        descriptor (extract-descriptor method-sig)
        registry-reinstaller (clojure.java.api.Clojure/var "nihilite.registry.dispatch" "redefine-dispatcher")
        reinstaller (deref ^clojure.lang.Atom registry-reinstaller)]
    (if (nil? reinstaller)
      (throw (IllegalStateException. "GenericDispatcher: worker not booted (REDISPATCHER null)"))
      (.invoke ^clojure.lang.IFn reinstaller host m-name self args descriptor))))

(defn gd-assign
  "Forwarded by the generated DynamicAssigner.assign stub. Mirrors the
   original DynamicAssigner: returns a trivial manipulation when source
   equals target or is assignable to target, otherwise a cast to target."
  [_ ^net.bytebuddy.description.type.TypeDescription$Generic source
    ^net.bytebuddy.description.type.TypeDescription$Generic target
    ^java.lang.Enum _typing]
   (let [trivial (first (filter #(= "INSTANCE" (.getName ^java.lang.reflect.Field %))
                                (.getFields (Class/forName "net.bytebuddy.implementation.bytecode.StackManipulation$Trivial"))))
         casting (Class/forName "net.bytebuddy.implementation.bytecode.assign.TypeCasting")]
    (if (or (.equals source target)
            (.isAssignableTo (.asErasure source) (.asErasure target)))
      (.get trivial nil)
      (.invokeStatic casting "to" target))))

(defn- dispatch-method-metadata []
  (read-string
   "{net.bytebuddy.implementation.bind.annotation.RuntimeType {}}"))

(defn- origin-class-param []
  (with-meta (symbol "Class")
              (read-string "{net.bytebuddy.implementation.bind.annotation.Origin {}}")))

(defn- origin-string-param []
  (with-meta (symbol "String")
              (read-string "{net.bytebuddy.implementation.bind.annotation.Origin {}}")))

(defn- this-param []
  (with-meta (symbol "Object")
              (read-string "{net.bytebuddy.implementation.bind.annotation.This {:optional true}}")))

(defn- all-args-param []
  (with-meta (symbol "Object/1")
              (read-string "{net.bytebuddy.implementation.bind.annotation.AllArguments {}}")))

(defn- gen-dispatcher! []
  (let [mname (with-meta (symbol "dispatch")
                (dispatch-method-metadata))
        pclasses [(origin-class-param)
                  (origin-string-param)
                  (this-param)
                  (all-args-param)]
        msig (with-meta (vector mname pclasses (symbol "Object")) {:static true})]
    (cg/generate-class-bytes!
     {:name "nihilite.kernel.GenericDispatcher"
      :prefix "gd-"
      :impl-ns "nihilite.kernel.dispatcher"
      :main false
      :methods [msig]})))

(defn- gen-assigner! []
  (let [mname (with-meta (symbol "assign") {})
        pclasses [(symbol "java.lang.Object")
                  (symbol "net.bytebuddy.description.type.TypeDescription$Generic")
                  (symbol "net.bytebuddy.description.type.TypeDescription$Generic")
                  (symbol "net.bytebuddy.implementation.bytecode.assign.Assigner$Typing")]
        msig (with-meta (vector mname pclasses (symbol "Object")) {})
        assigner-iface (Class/forName "net.bytebuddy.implementation.bytecode.assign.Assigner")]
    (cg/generate-class-bytes!
     {:name "nihilite.kernel.DynamicAssigner"
      :prefix "gd-"
      :impl-ns "nihilite.kernel.dispatcher"
      :main false
      :implements [assigner-iface]
      :methods [msig]})))

(defn gen-all!
  "Generates GenericDispatcher and DynamicAssigner into *compile-path*.
   Intended to run during AOT compilation of this namespace; a no-op
   outside of a compile because writeClassFile only writes when
   *compile-files* is set."
  []
  (gen-dispatcher!)
  (gen-assigner!)
  nil)

(def instance
  "Lazy accessor for a DynamicAssigner instance suitable for
   MethodDelegation.to(...).withAssigner(...). Loads the generated class
   (generating it on demand when not compiling)."
  (delay
   (let [cls (Class/forName "nihilite.kernel.DynamicAssigner")]
     (.newInstance cls (object-array [])))))

(when *compile-files*
  (gen-all!))
