(ns nihilite.kernel.installer
   "ByteBuddy AgentBuilder installation, implemented in Clojure.

    Mirrors the original nihilite.hooks.HookInstaller. One AgentBuilder
    instance is armed on the supplied Instrumentation, driven by a
    generated type-level matcher (HookTypeMatcher, produced because
    ElementMatcher.Junction is a generic interface reify cannot
    implement). The single transformer composes both hook kinds on the
    same builder: :redefine specs are delegated to the generated
    GenericDispatcher first (replacing the method body), then
    entry/return/throw advice is visited onto the replaced body.
    Composing in one transformer keeps advice from being erased by
    redefinition, per the ByteBuddy-recommended order (raphw/byte-buddy#1097).

    collect-buckets / matcher-for / apply-advice-transformer /
    apply-redefine-transformer / hook-type-matches? are plain Clojure
    functions; the transformer and type-matcher objects are thin
    generated classes whose bodies forward to those vars."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [nihilite.kernel.dispatcher :as disp])
  (:import [net.bytebuddy.asm Advice]
           [net.bytebuddy.implementation MethodDelegation]
           [net.bytebuddy.matcher ElementMatchers]
           [java.lang.instrument Instrumentation]))

(defn- lookup-matching []
  (let [v (resolve 'nihilite.registry/matching)]
    (when v ^clojure.lang.IFn v)))

(defn- spec-field [spec key]
  (let [entry (first (filter (fn [^java.util.Map$Entry e]
                               (= key (.getKey e)))
                             (seq (.entrySet ^java.util.Map spec))))]
    (when entry
      (let [v (.getValue entry)]
        (when v (str v))))))

(defn- collect-buckets
  "Maps the specs registered for a target class to the four position
   buckets ByteBuddy needs. Returns nil when the target has no specs
   (nothing to do) so the transformer leaves the builder untouched."
  [^net.bytebuddy.description.type.TypeDescription type-description]
  (when-let [specs (some-> (lookup-matching)
                           (.invoke ^java.lang.String (.getInternalName type-description))
                           (some-> (vec)))]
    (when (seq specs)
      (let [by-position (reduce
                         (fn [acc spec]
                           (let [p    (spec-field spec :position)
                                 name (spec-field spec :method-name)]
                             (when (and p name)
                               (let [desc (spec-field spec :source-descriptor)
                                     bucket-key (keyword (str p))
                                     key [name desc]]
                                 (assoc-in acc [bucket-key]
                                           (conj (or (get-in acc [bucket-key]) #{}) key))))))
                         {:entry #{} :return #{} :throw #{} :redefine #{}}
                         specs)]
        (when (some identity (vals by-position))
          by-position)))))

(defn- matcher-for
  "Builds the ElementMatcher.Junction over the methods in one position
   bucket: any of the (name, optional descriptor) pairs, excluding
   constructors and type initializers."
  [keys]
  (if (empty? keys)
    nil
    (let [matcher (reduce
                   (fn [acc [name desc]]
                     (let [named (ElementMatchers/named name)]
                       (if (and desc (not (str/blank? desc)))
                         (.or acc (.and named (ElementMatchers/hasDescriptor desc)))
                         (.or acc named))))
                   (ElementMatchers/none)
                   keys)]
      (.and (.and matcher (ElementMatchers/not (ElementMatchers/isConstructor)))
            (ElementMatchers/not (ElementMatchers/isTypeInitializer))))))

(defn- post-processor-factory
  "A new Advice.AssignReturned.Factory, resolved reflectively because the
   fully-qualified nested-class form is not accepted by the reader in
   every tooling context."
  []
  (let [cls (Class/forName "net.bytebuddy.asm.Advice$AssignReturned$Factory")]
    (.newInstance cls (object-array []))))

(defn- visit-advice [builder position-keys matcher-class-name]
  (if (seq position-keys)
    (let [matcher (matcher-for position-keys)]
      (.visit builder (.on (.to (Advice/to (Class/forName matcher-class-name)) matcher))))
    builder))

(defn- visit-return-advice [builder position-keys]
  (if (seq position-keys)
    (let [matcher (matcher-for position-keys)]
      (.visit
       builder
       (.on
        (.with
         (.withCustomMapping (Advice/withCustomMapping))
         (post-processor-factory))
        matcher)))
    builder))

(defn- apply-advice-transformer
  "Clojure body of the generated advice-transformer stub. Visits the
   entry/return/throw advice positions for the target class when specs
   exist. The return position uses withCustomMapping + AssignReturned so
   ReturnAdvice's @AssignReturned.ToReturned annotation is honored.
   gen-class forwarding for instance methods prepends `this`, so this
   arity is 6."
  [_this
   ^net.bytebuddy.dynamic.DynamicType$Builder builder
   ^net.bytebuddy.description.type.TypeDescription type-description
   ^java.lang.ClassLoader _class-loader
   ^net.bytebuddy.utility.JavaModule _module
   ^java.security.ProtectionDomain _protection-domain]
  (if-let [buckets (collect-buckets type-description)]
    (let [entry-b   (:entry buckets)
          return-b  (:return buckets)
          throw-b   (:throw buckets)
          b (visit-advice builder entry-b "nihilite.kernel.HookAdvice")
          b2 (visit-return-advice b return-b)]
      (visit-advice b2 throw-b "nihilite.kernel.ThrowAdvice"))
    builder))

(defn- apply-redefine-transformer
  "Clojure body of the generated redefine-transformer stub. Delegates
   matching :redefine methods to the generated GenericDispatcher with the
   DynamicAssigner. Arity 6 because gen-class forwarding for instance
   methods prepends `this`."
  [_this
   ^net.bytebuddy.dynamic.DynamicType$Builder builder
   ^net.bytebuddy.description.type.TypeDescription type-description
   ^java.lang.ClassLoader _class-loader
   ^net.bytebuddy.utility.JavaModule _module
   ^java.security.ProtectionDomain _protection-domain]
  (if-let [buckets (collect-buckets type-description)]
    (let [redefine-keys (:redefine buckets)]
      (if (seq redefine-keys)
        (let [matcher (matcher-for redefine-keys)
              assigner (deref disp/instance)]
          (.intercept
           (.method builder matcher)
           (.withAssigner
            (MethodDelegation/to (Class/forName "nihilite.kernel.GenericDispatcher"))
            assigner)))
        builder))
    builder))

(defn- hook-type-matches?
  "Clojure body of the generated HookTypeMatcher.matches stub. true when
   the registry has at least one spec for this target class. Arity 2
   because gen-class forwarding for instance methods prepends `this`."
  [_this ^net.bytebuddy.description.type.TypeDescription type-description]
  (try
    (let [raw (.invoke (lookup-matching) (.getInternalName type-description))]
      (and (instance? java.util.List raw) (not (.isEmpty ^java.util.List raw))))
    (catch Throwable _
      false)))


(defn- generate-class-bytes! [options]
  (let [generate-class (Class/forName "clojure.core$generate_class")
        invoke-static (.getDeclaredMethod generate-class "invokeStatic"
                                         (into-array Class [Object]))]
    (.setAccessible invoke-static true)
    (let [[cname bytecode] (.invoke invoke-static nil (object-array [options]))]
      (clojure.lang.Compiler/writeClassFile cname bytecode)
      cname)))

(defn at-equals [_ other] (instance? net.bytebuddy.agent.builder.AgentBuilder$Transformer other))
(defn at-toString [_] "nihilite.kernel.AdviceTransformer")
(defn at-hashCode [self] (System/identityHashCode self))
(defn at-clone [_]
  (throw (UnsupportedOperationException. "nihilite.kernel.AdviceTransformer/clone not supported")))

(defn hm-equals [_ other] (instance? net.bytebuddy.matcher.ElementMatcher other))
(defn hm-toString [_] "nihilite.kernel.HookTypeMatcher")
(defn hm-hashCode [self] (System/identityHashCode self))
(defn hm-clone [_]
  (throw (UnsupportedOperationException. "nihilite.kernel.HookTypeMatcher/clone not supported")))

(defn hm-matches
  "gen-class forwarding stub for HookTypeMatcher.matches(T).
   gen-class prepends `this` to instance method args (so arity 2)."
  [this type-description]
  (hook-type-matches? this type-description))

(defn at-transform
  "gen-class forwarding stub for AdviceTransformer.transform. Arity 6
   because gen-class prepends `this`.

   Composes redefine + advice in a single transformer so advice is not
   erased by redefinition: MethodDelegation (:redefine) replaces the
   method body first, then the entry/return/throw advice is visited onto
   the replaced body. This is the ByteBuddy-recommended order when both
   are applied to the same target method (see raphw/byte-buddy#1097)."
  [this builder type-description class-loader module protection-domain]
  (let [b1 (apply-redefine-transformer this builder type-description class-loader module protection-domain)
        b2 (apply-advice-transformer this b1 type-description class-loader module protection-domain)]
    b2))

(defn- gen-transformer! [class-name prefix]
  (let [iface (Class/forName "net.bytebuddy.agent.builder.AgentBuilder$Transformer")]
    (generate-class-bytes!
     {:name class-name
      :prefix prefix
      :impl-ns "nihilite.kernel.installer"
      :main false
      :implements [iface]
      :methods []})))

(defn- gen-type-matcher! 
  "Generates nihilite.kernel.HookTypeMatcher implementing
   net.bytebuddy.matcher.ElementMatcher. The matches(T) method is
   auto-emitted by gen-class (no :methods clause); the generated stub
   forwards to hook-type-matches? in this namespace."
  [] (let [iface (Class/forName "net.bytebuddy.matcher.ElementMatcher")]
    (generate-class-bytes!
     {:name "nihilite.kernel.HookTypeMatcher"
      :prefix "hm-"
      :impl-ns "nihilite.kernel.installer"
      :main false
      :implements [iface]
      :methods []})))

(defn gen-all!
  "Generates HookTypeMatcher and AdviceTransformer into *compile-path*.
   The AdviceTransformer is the single combined transformer (redefine
   first, then advice). Intended to run during AOT compilation of this
   namespace; a no-op outside of a compile because writeClassFile only
   writes when *compile-files* is set."
  []
  (gen-type-matcher!)
  (gen-transformer! "nihilite.kernel.AdviceTransformer" "at-")
  nil)


(defn- base-builder []
  (let [retransformation (let [^net.bytebuddy.agent.builder.AgentBuilder$RedefinitionStrategy s net.bytebuddy.agent.builder.AgentBuilder$RedefinitionStrategy/RETRANSFORMATION]
                          s)
        reiterator (let [^net.bytebuddy.agent.builder.AgentBuilder$RedefinitionStrategy$DiscoveryStrategy$Reiterating d net.bytebuddy.agent.builder.AgentBuilder$RedefinitionStrategy$DiscoveryStrategy$Reiterating/INSTANCE]
                    d)
        ignore (-> (ElementMatchers/nameStartsWith "java.")
                    (.or (ElementMatchers/nameStartsWith "javax."))
                    (.or (ElementMatchers/nameStartsWith "jdk."))
                    (.or (ElementMatchers/nameStartsWith "sun."))
                    (.or (ElementMatchers/nameStartsWith "com.sun."))
                    (.or (ElementMatchers/nameStartsWith "clojure."))
                    (.or (ElementMatchers/nameStartsWith "nrepl."))
                    (.or (ElementMatchers/nameStartsWith "nihilite.agent."))
                    (.or (ElementMatchers/nameStartsWith "nihilite.hooks."))
                    (.or (ElementMatchers/nameStartsWith "nihilite.boot."))
                    (.or (ElementMatchers/nameStartsWith "nihilite.transport."))
                    (.or (ElementMatchers/nameStartsWith "nihilite.registry."))
                    (.or (ElementMatchers/isSynthetic)))
        ^net.bytebuddy.agent.builder.AgentBuilder$Default base (net.bytebuddy.agent.builder.AgentBuilder$Default.)]
    (-> base
        (.disableClassFormatChanges)
        (.with retransformation)
        (.with reiterator)
        (.ignore ignore))))

(defn- transformer-instance [class-name]
  (let [cls (Class/forName class-name)]
    (.newInstance cls (object-array []))))

(defn install
  "Arms the single AgentBuilder (redefine + advice composed) against the
   live JVM. No-op when inst is nil (e.g. driver/test paths without
   instrumentation)."
  [^Instrumentation inst]
  (if (nil? inst)
    (log/info "HookInstaller install skipped (no Instrumentation)")
    (try
      (let [type-matcher (transformer-instance "nihilite.kernel.HookTypeMatcher")
            combined-xform (transformer-instance "nihilite.kernel.AdviceTransformer")]
        (.installOn
         (.transform
          (.type (base-builder) type-matcher)
          combined-xform)
         inst)
        (log/info "HookInstaller armed (byte-buddy AgentBuilder, RETRANSFORMATION, Reiterating)"))
      (catch Throwable t
        (log/error t "HookInstaller install failed")
        (.printStackTrace t)))))

(defn uninstall
  "Retransforms all loaded classes whose name matches target-internal
   (slash-separated) so that ByteBuddy drops the instrumentation.
   Returns the number of classes actually retransformed."
  [^Instrumentation inst ^java.lang.String target-internal]
  (if (or (nil? inst) (nil? target-internal))
    0
    (let [dot-name (.replace target-internal "/" ".")
          count (atom 0)]
      (doseq [^java.lang.Class loaded (.getAllLoadedClasses inst)]
        (when (and loaded
                   (= dot-name (.getName loaded))
                   (.isModifiableClass inst loaded))
          (try
            (.retransformClasses inst (into-array Class [loaded]))
            (swap! count inc)
            (log/info "HookInstaller uninstall: retransformed" dot-name)
            (catch java.lang.instrument.UnmodifiableClassException _
              (log/warn (str "HookInstaller uninstall: cannot retransform " dot-name
                             " (loader=" (.getClassLoader loaded) ")")))
            (catch Throwable _
              (log/warn (str "HookInstaller uninstall: retransform failed for " dot-name
                             " (loader=" (.getClassLoader loaded) ")"))))))
      @count)))

(defn uninstall-spec!
  "Stub used by registry.clj. Uninstalls the instrumented target class
   whose internal name matches the spec id (registry-side hook), or
   falls back to a no-op when no Instrumentation is registered."
  [^String spec-id]
  (let [inst-fn (requiring-resolve 'nihilite.kernel.agent/agent-currentInstrumentation)
        inst (when inst-fn (inst-fn))]
    (if inst
      (uninstall inst spec-id)
      (do (log/warn "HookInstaller uninstall-spec: no Instrumentation for spec id=" spec-id)
          0))))

(when *compile-files*
  (gen-all!))
