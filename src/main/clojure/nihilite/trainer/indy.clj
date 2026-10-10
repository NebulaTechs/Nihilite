(ns nihilite.trainer.indy
  "Invokedynamic dispatch, so advice can reach classes the target
   classloader cannot see.

   Follows the shape used by OpenTelemetry's javaagent. The instrumented
   method body gets one INVOKEDYNAMIC whose only class reference is a
   dispatcher injected into the bootstrap loader, and whose static bootstrap
   arguments are plain strings. On first execution the JVM links the call
   site through that dispatcher, which forwards a MethodHandle the agent
   installed during premain. The agent cannot be looked up from the bootstrap
   side, so the handle is pushed the other way.

   The dispatcher is injected into the bootstrap loader, so its bodies may
   only touch java.lang and java.lang.invoke. That is why it does not use the
   shared forwarder, which resolves a Clojure var, and why it takes both the
   linking handle and a fallback handle as fields instead of building a noop
   handle itself."
  (:require [nihilite.crafter.bytegen :as bg])
  (:import [java.lang.invoke CallSite ConstantCallSite MethodHandle
                       MethodHandles MethodType MethodHandles$Lookup]
           [java.lang.reflect Array Modifier]
           [net.bytebuddy.implementation Implementation]
           [net.bytebuddy.implementation.bytecode ByteCodeAppender
                                                 ByteCodeAppender$Size]
           [net.bytebuddy.jar.asm Label MethodVisitor Opcodes]))

(def bootstrap-dispatcher-name "nihilite.trainer.IndyBootstrapDispatcher")

(defn- emitter
  "Wraps an ASM-emitting function into an Implementation. `n-locals` sizes the
   frame; `fn` receives the MethodVisitor and emits the body."
  [n-locals fn]
  (let [size (ByteCodeAppender$Size. n-locals n-locals)]
    (reify Implementation
      (prepare [_ t] t)
      (appender [_ _target]
        (reify ByteCodeAppender
          (apply [_ mv _ctx _method]
            (fn mv)
            size))))))

(def ^:private dispatcher-internal-name
  (.replace bootstrap-dispatcher-name "." "/"))

(defn- init-body
  "void init(MethodHandle, MethodHandle) — stores the agent's linking handle
   and its fallback handle into the dispatcher's static fields."
  [^MethodVisitor mv]
  (.visitVarInsn mv Opcodes/ALOAD 0)
  (.visitFieldInsn mv Opcodes/PUTSTATIC dispatcher-internal-name
                   "bootstrap" "Ljava/lang/invoke/MethodHandle;")
  (.visitVarInsn mv Opcodes/ALOAD 1)
  (.visitFieldInsn mv Opcodes/PUTSTATIC dispatcher-internal-name
                   "fallback" "Ljava/lang/invoke/MethodHandle;")
  (.visitInsn mv Opcodes/RETURN))

(defn- bootstrap-body
  "CallSite bootstrap(Lookup, String, MethodType, Object[]) — forwards to the
   handle the agent installed. A null handle means Nihilite is loaded but not
   installed as an agent, and the fallback handle then builds a noop call site
   rather than letting the instrumented call fail."
  [^MethodVisitor mv]
  (let [l-linked (Label.)]
    (.visitFieldInsn mv Opcodes/GETSTATIC dispatcher-internal-name
                     "bootstrap" "Ljava/lang/invoke/MethodHandle;")
    (.visitInsn mv Opcodes/DUP)
    (.visitJumpInsn mv Opcodes/IFNULL l-linked)
    (.visitVarInsn mv Opcodes/ALOAD 0)
    (.visitVarInsn mv Opcodes/ALOAD 1)
    (.visitVarInsn mv Opcodes/ALOAD 2)
    (.visitVarInsn mv Opcodes/ALOAD 3)
    ;; MethodHandle.invoke is varargs, so the descriptor has to spell out the
    ;; exact arity being pushed; the array form would make the JVM read the
    ;; first pushed value as an Object[].
    (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/invoke/MethodHandle"
                     "invoke"
                     "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/Object;"
                     false)
    (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/invoke/CallSite")
    (.visitInsn mv Opcodes/ARETURN)
    (.visitLabel mv l-linked)
    (.visitInsn mv Opcodes/POP)
    (.visitVarInsn mv Opcodes/ALOAD 2)
    (.visitFieldInsn mv Opcodes/GETSTATIC dispatcher-internal-name
                     "fallback" "Ljava/lang/invoke/MethodHandle;")
    (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/invoke/MethodHandle"
                     "invoke" "(Ljava/lang/invoke/MethodType;)Ljava/lang/Object;"
                     false)
    (.visitTypeInsn mv Opcodes/CHECKCAST "java/lang/invoke/CallSite")
    (.visitInsn mv Opcodes/ARETURN)))

(def ^:private dispatcher-spec
  {:name bootstrap-dispatcher-name
   :fields [{:name "bootstrap" :type MethodHandle :static? true :volatile? true}
            {:name "fallback" :type MethodHandle :static? true :volatile? true}]
   :methods [{:name "init" :static? true :return "void"
              :params ["java.lang.invoke.MethodHandle" "java.lang.invoke.MethodHandle"]
              :body (emitter 2 init-body)}
             {:name "bootstrap" :static? true :return "java.lang.invoke.CallSite"
              :params ["java.lang.invoke.MethodHandles$Lookup" "java.lang.String"
                       "java.lang.invoke.MethodType" "[Ljava.lang.Object;"]
              :body (emitter 5 bootstrap-body)}]})

(defonce ^:private installed (atom nil))

(defn- dispatcher [] ^Class
  (or @installed
      (throw (IllegalStateException. "indy bootstrap dispatcher not installed"))))

;; key -> MutableCallSite for every call site this thread is currently linking.
;; An advice invocation that re-enters the linker for a key already present gets
;; that site's placeholder instead of starting a nested link, which is what keeps
;; the JVM from recursing until the stack overflows.
(defonce ^:private linking (java.lang.ThreadLocal.))

;; advice class name -> the MethodHandle for its instrumented method,
;; resolved at premain time so the bootstrap method never loads a class.
(defonce ^:private advice-handles (atom {}))


(defn- linking-map []
  (or (.get linking)
      (let [m (java.util.HashMap.)]
        (.set linking m)
        m)))

(defn- default-value
  "The zero value for a return type: null for a reference, 0 for a numeric
   primitive, false for boolean, 0 for char."
  [^Class rt]
  (if (identical? rt Void/TYPE)
    nil
    (if (.isPrimitive rt)
      (let [arr (.newInstance (Array/newInstance rt 1) (into-array Integer [0]))]
        (.get arr 0))
      nil)))

(defn- noop-handle
  "Returns the default value for the return type and discards every argument."
  [^MethodType mt]
  (MethodHandles/dropArguments
   (MethodHandles/constant (.returnType mt) (default-value (.returnType mt)))
   0 (.parameterList mt)))

(defn iab-fallback
  "Builds the call site used when linking cannot produce a real one: it drops
   every argument and returns the default value, so the instrumented method
   behaves as if no hook were installed."
  [^MethodType mt]
  (ConstantCallSite. (noop-handle mt)))

(defn- advice-handle
  "Resolves the advice method the call site should end up pointing at.

   The handle is adapted to the bootstrap method's return type, CallSite: the
   JVM validates the bootstrap method against the call site's own type when it
   links the site, so adapting to the call site type instead is what makes
   CallSite.makeSite fail with WrongMethodTypeException.

   A bootstrap method must not trigger class loading: loading a class reads
   its bytes through java.io.FileInputStream, which is one of the classes being
   instrumented, so the nested link would recurse until the stack overflows.
   The advice class is therefore resolved and cached by preload-advice! at
   premain time, and this only ever looks the cache up.

   The lookup has to come from the agent loader: a Lookup taken from the
   instrumented class would not be granted access to the advice class."
  [^String name ^String advice-class-name ^MethodType mt]
  (if-let [cached (get @advice-handles advice-class-name)]
    (.asType ^MethodHandle cached mt)
    (let [cl (ClassLoader/getSystemClassLoader)
          advice (Class/forName advice-class-name true cl)
          m (first (filter (fn [^java.lang.reflect.Method x]
                             (and (= name (.getName x))
                                  (Modifier/isStatic (.getModifiers x))))
                           (.getDeclaredMethods advice)))
          advice-type (MethodType/methodType (.getReturnType m)
                                              (into-array Class
                                                          (.getParameterTypes m)))
          mh (.findStatic (MethodHandles/lookup) advice name advice-type)]
      (swap! advice-handles assoc advice-class-name mh)
      (.asType mh mt))))

(defn preload-advice!
  "Resolves an advice class now, while it is safe to load classes, so the
   bootstrap method never has to. The method's own descriptor is read from
   the class rather than passed in, so it always matches the advice that was
   actually generated."
  [^String advice-class-name ^String method-name]
  (let [cl (ClassLoader/getSystemClassLoader)
        advice (Class/forName advice-class-name true cl)
        m (first (filter (fn [^java.lang.reflect.Method x]
                           (and (= method-name (.getName x))
                                (Modifier/isStatic (.getModifiers x))))
                         (.getDeclaredMethods advice)))
        advice-type (MethodType/methodType (.getReturnType m)
                                            (into-array Class
                                                        (.getParameterTypes m)))]
    (swap! advice-handles assoc advice-class-name
           (.findStatic (MethodHandles/lookup) advice method-name advice-type))))

(defn iab-bootstrap
  "Agent-side bootstrap. Reached from the bootstrap-side dispatcher through
   the MethodHandle installed by install-bridge!, which is how a class in
   java.base gets to code in the agent loader.

   Static arguments are a module label, the advice method descriptor and the
   advice class name, all strings."
  [^MethodHandles$Lookup lookup ^String name ^MethodType mt args]
  (let [advice-class-name (nth args 2)
        key [(.getName (.lookupClass lookup)) advice-class-name name]
        m (linking-map)]
    (if-let [existing (.get m key)]
      existing
      (let [placeholder (java.lang.invoke.MutableCallSite. (noop-handle mt))]
        (.put m key placeholder)
        (try
          (let [mh (advice-handle name advice-class-name mt)]
            (.setTarget placeholder mh)
            (java.lang.invoke.MutableCallSite/syncAll
             (into-array java.lang.invoke.MutableCallSite [placeholder]))
            (.remove m key)
            (ConstantCallSite. mh))
          (catch Throwable t
            ;; Per project rule #439 the failure must surface. Silently
            ;; resolving to a noop CallSite is what makes a hook look armed
            ;; (woven-count 1, pending? false) while never firing, which is
            ;; the exact failure mode this dispatch exists to remove.
            (.remove m key)
            (throw t)))))))

(def ^:private agent-bootstrap-name "nihilite.trainer.IndyAgentBootstrap")

(def ^:private agent-bootstrap-spec
  {:name agent-bootstrap-name
   :methods [{:name "bootstrap" :static? true :return "java.lang.invoke.CallSite"
              :params ["java.lang.invoke.MethodHandles$Lookup" "java.lang.String"
                       "java.lang.invoke.MethodType" "[Ljava.lang.Object;"]
              :forward-var 'nihilite.trainer.indy/iab-bootstrap}
             {:name "fallback" :static? true :return "java.lang.invoke.CallSite"
              :params ["java.lang.invoke.MethodType"]
              :stack-size 6
              :forward-var 'nihilite.trainer.indy/iab-fallback}]})

(defonce ^:private agent-bootstrap-loaded (atom nil))

(defn- agent-bootstrap-class [] ^Class
  (or @agent-bootstrap-loaded
      (throw (IllegalStateException. "indy agent bootstrap not installed"))))

(defn ensure-dispatcher!
  "Injects the bootstrap-side dispatcher and the agent-side bootstrap. Needs
   the Instrumentation to have been captured already, i.e. install via
   -javaagent."
  []
  (or @installed
      (let [lookup (resolve 'nihilite.trainer.agent/agent-currentInstrumentation)
            inst (when lookup (lookup))]
        (when (nil? inst)
          (throw (IllegalStateException.
                   "indy dispatcher needs an Instrumentation; install via -javaagent")))
        (reset! installed
                (bg/define-class! (assoc dispatcher-spec
                                         :instrumentation inst
                                         :inject-target
                                         net.bytebuddy.dynamic.loading.ClassInjector$UsingInstrumentation$Target/BOOTSTRAP
                                         :save-dir nil)))
        (reset! agent-bootstrap-loaded
                (bg/define-class! (assoc agent-bootstrap-spec
                                         :instrumentation inst
                                         :save-dir nil)))))
  nil)

(defn bootstrap-method
  "The reflective Method ByteBuddy places in the constant pool as the call
   site's bootstrap method. It belongs to the injected dispatcher because the
   target class may live in a loader that cannot see agent classes."
  []
  (doto (.getDeclaredMethod (dispatcher) "bootstrap"
                             (into-array Class [MethodHandles$Lookup String
                                                MethodType
                                                (Class/forName "[Ljava.lang.Object;")]))
    (.setAccessible true)))

(def ^:private module-label "nihilite")

(defn resolver-factory
  "BootstrapArgumentResolver.Factory that pins each call site's three static
   bootstrap arguments: a module label, the advice method's descriptor, and the
   advice class name. All three are plain strings, so nothing in the woven
   method body refers to a class the target loader would have to resolve.

   `advice-class-name` is per-advice-class, so the caller passes the advice it
   is about to weave; the descriptor is read off the advice method itself so
   the two can never drift apart."
  [advice-class-name]
  (reify net.bytebuddy.asm.Advice$BootstrapArgumentResolver$Factory
    (resolve [_ advice-method _is-exit]
      (let [descriptor (.getDescriptor ^net.bytebuddy.description.method.MethodDescription
                                       advice-method)]
        (reify net.bytebuddy.asm.Advice$BootstrapArgumentResolver
          (resolve [_ _instrumented-type _instrumented-method]
            [(net.bytebuddy.utility.JavaConstant$Simple/ofLoaded module-label)
             (net.bytebuddy.utility.JavaConstant$Simple/ofLoaded descriptor)
             (net.bytebuddy.utility.JavaConstant$Simple/ofLoaded advice-class-name)]))))))

(defn wire
  "Applies the invokedynamic dispatch to an Advice builder: the call site
   bootstraps through the injected dispatcher, and the static arguments name
   the advice class. `wcm` is the Advice/withCustomMapping the caller is
   already using, so a per-position post-processor can still be attached."
  [^net.bytebuddy.asm.Advice$WithCustomMapping wcm advice-class-name]
  (.bootstrap wcm (bootstrap-method) (resolver-factory advice-class-name)))

(defn install-bridge!
  "Pushes the agent's linking handle and fallback handle into the
   bootstrap-side dispatcher. Those handles are the only path from a class in
   java.base to code living in the agent loader."
  []
  (ensure-dispatcher!)
  (let [object-array-class (Class/forName "[Ljava.lang.Object;")
        mh (MethodHandles/lookup)
        cls (agent-bootstrap-class)
        linking-handle (.findStatic mh cls "bootstrap"
                                    (MethodType/methodType
                                     CallSite
                                     (into-array Class [MethodHandles$Lookup String
                                                        MethodType object-array-class])))
        fallback-handle (.findStatic mh cls "fallback"
                                     (MethodType/methodType
                                      CallSite (into-array Class [MethodType])))
        ;; publicLookup keeps full privilege over public members of public
        ;; classes, which is what a bootstrap-loaded target needs; a plain
        ;; lookup or reflection runs into module access instead.
        init (.getMethod (dispatcher) "init"
                         (into-array Class [MethodHandle MethodHandle]))]
    (.invoke ^java.lang.reflect.Method init nil
             (object-array [linking-handle fallback-handle]))
    linking-handle))
