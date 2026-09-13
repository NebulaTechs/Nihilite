(ns nihilite.kernel.transformer
  "ByteBuddy transformer + type-matcher generation, implemented in Clojure.

   Owns the generated AdviceTransformer / HookTypeMatcher classes and the
   plain-Clojure bodies their stubs forward to. The installer wires these
   into an AgentBuilder; position-bucketing lives in nihilite.kernel.bucket.

   The transformer composes both hook kinds on the same builder so advice
   is not erased by redefinition: :redefine specs are delegated to the
   generated GenericDispatcher first (replacing the method body), then
   entry/return/throw advice is visited onto the replaced body. This is the
   ByteBuddy-recommended order when both apply to the same method
   (raphw/byte-buddy#1097)."
  (:require [nihilite.kernel.classgen :as cg]
            [nihilite.kernel.dispatcher :as disp]
            [nihilite.kernel.bucket :as bucket])
  (:import [net.bytebuddy.asm Advice]
           [net.bytebuddy.implementation MethodDelegation]))

(def ^:private ^java.util.concurrent.ConcurrentHashMap ^:no-doc match-cache
  "Negative-result cache for HookTypeMatcher.matches. Entries are
   [revision, match?]; an entry is reused only while registry/revision
   matches. Cache is bounded to limit growth on long-running agents."
  (java.util.concurrent.ConcurrentHashMap.))

(def ^:private ^long cache-cap 4096)

(defn- prune-cache-if-full! []
  (when (> (.size ^java.util.concurrent.ConcurrentHashMap match-cache)
           ^long cache-cap)
    (.clear ^java.util.concurrent.ConcurrentHashMap match-cache)))

(defn- registry-revision []
  (let [v (resolve 'nihilite.registry/revision)]
    (when v (.invoke ^clojure.lang.IFn v))))

(defn- lookup-matching []
  (let [v (resolve 'nihilite.registry/matching)]
    (when v ^clojure.lang.IFn v)))

(defn- hook-type-matches?
  "Clojure body of the generated HookTypeMatcher.matches stub. true when
   the registry has at least one spec for this target class. Arity 2
   because gen-class forwarding for instance methods prepends `this`.
   Cached negatively: a no-spec result is remembered until the registry
   revision changes, so the JVM's per-class load does not retrigger an
   expensive registry query on every class that has no hook."
  [_this ^net.bytebuddy.description.type.TypeDescription type-description]
  (let [internal-name (.getInternalName type-description)
        rev (registry-revision)
        cached (.get ^java.util.concurrent.ConcurrentHashMap match-cache internal-name)]
    (if (and cached (= rev (first cached)))
      (second cached)
      (try
        (let [raw (.invoke (lookup-matching) internal-name)
              match? (and (instance? java.util.List raw)
                          (not (.isEmpty ^java.util.List raw)))]
          (when-not match?
            (.put ^java.util.concurrent.ConcurrentHashMap match-cache internal-name [rev false])
            (prune-cache-if-full!))
          match?)
        (catch Throwable _
          (prune-cache-if-full!)
          false)))))

(defn- visit-advice [builder position-keys matcher-class-name]
  (if (seq position-keys)
    (let [matcher (bucket/matcher-for position-keys)]
      (.visit builder (.on (.to (Advice/to (Class/forName matcher-class-name)) matcher))))
    builder))

(defn- post-processor-factory
  []
  (let [cls (Class/forName "net.bytebuddy.asm.Advice$AssignReturned$Factory")]
    (.newInstance cls (object-array []))))

(defn- visit-return-advice [builder position-keys]
  (if (seq position-keys)
    (let [matcher (bucket/matcher-for position-keys)]
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
  (if-let [buckets (bucket/collect-buckets type-description)]
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
  (if-let [buckets (bucket/collect-buckets type-description)]
    (let [redefine-keys (:redefine buckets)]
      (if (seq redefine-keys)
        (let [matcher (bucket/matcher-for redefine-keys)
              assigner (deref disp/instance)]
          (.intercept
           (.method builder matcher)
           (.withAssigner
            (MethodDelegation/to (Class/forName "nihilite.kernel.GenericDispatcher"))
            assigner)))
        builder))
    builder))


(defn at-equals [self other] (identical? self other))
(defn at-toString [_] "nihilite.kernel.AdviceTransformer")
(defn at-hashCode [self] (System/identityHashCode self))
(defn at-clone [_]
  (throw (UnsupportedOperationException. "nihilite.kernel.AdviceTransformer/clone not supported")))

(defn hm-equals [self other] (identical? self other))
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
   the replaced body."
  [this builder type-description class-loader module protection-domain]
  (let [b1 (apply-redefine-transformer this builder type-description class-loader module protection-domain)
        b2 (apply-advice-transformer this b1 type-description class-loader module protection-domain)]
    b2))

(defn- gen-transformer! [class-name prefix]
  (let [iface (Class/forName "net.bytebuddy.agent.builder.AgentBuilder$Transformer")]
    (cg/generate-class-bytes!
     {:name class-name
      :prefix prefix
      :impl-ns "nihilite.kernel.transformer"
      :main false
      :implements [iface]
      :methods []})))

(defn- gen-type-matcher!
  "Generates nihilite.kernel.HookTypeMatcher implementing
   net.bytebuddy.matcher.ElementMatcher. The matches(T) method is
   auto-emitted by gen-class (no :methods clause); the generated stub
   forwards to hook-type-matches? in this namespace."
  []
  (let [iface (Class/forName "net.bytebuddy.matcher.ElementMatcher")]
    (cg/generate-class-bytes!
     {:name "nihilite.kernel.HookTypeMatcher"
      :prefix "hm-"
      :impl-ns "nihilite.kernel.transformer"
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

(when *compile-files*
  (gen-all!))
