(ns nihilite.trainer.transformer
  "ByteBuddy transformer + type-matcher generation, implemented in Clojure.

   Owns the generated AdviceTransformer / HookTypeMatcher classes and the
   plain-Clojure bodies their stubs forward to. The installer wires these
   into an AgentBuilder; position-bucketing lives in nihilite.trainer.bucket.

   The transformer composes both hook kinds on the same builder so
   entry/return/throw advice is not erased by redefinition: :redefine
   specs are visited via nihilite.trainer.RedefineAdvice (ByteBuddy Advice
   with @OnMethodExit + @AssignReturned) first, then the entry/return/
   throw advice is visited onto the replaced body. MethodDelegation
   (:redefine) is incompatible with RETRANSFORMATION mode under
   disableClassFormatChanges (ByteBuddy issue #1097), so this ns no
   longer uses the generic dispatcher."
  (:require [clojure.tools.logging :as log]
            [nihilite.crafter.classgen :as cg]
            [nihilite.crafter.bytegen :as bg]
            [nihilite.trainer.bucket :as bucket]
            [nihilite.trainer.indy :as indy])
  (:import [net.bytebuddy.asm Advice]
           [net.bytebuddy.dynamic ClassFileLocator$Simple]))

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
  (let [v (resolve 'nihilite.builder.registry.index/revision)]
    (when v (.invoke ^clojure.lang.IFn v))))

(defn- lookup-matching []
  (let [v (resolve 'nihilite.builder.registry.index/matching)]
    (when v ^clojure.lang.IFn v)))

(defn- hook-type-matches?
  "Clojure body of the generated HookTypeMatcher.matches stub. true when
   the registry has at least one spec for this target class. Arity 2
   because gen-class forwarding for instance methods prepends `this`.
   Cached negatively: a no-spec result is remembered until the registry
   revision changes, so the JVM's per-class load does not retrigger an
   expensive registry query on every class that has no hook.

   This must stay total. install uses .installOn, so the matcher runs for
   every class the JVM loads from arming onward, and a throw here would
   put a stack trace on the host application's class-loading path. The
   half-loaded-registry case that used to throw has its own fix --
   installer/preload-registry-read-side! requires registry.index before
   the builder is armed, rather than papering over it here.

   What this catch is for is a cause we do not know yet, and its whole
   job is to not be the reason nobody finds out: it names the class and
   logs the throwable. The failure is still one unwoven class, exactly as
   ByteBuddy's onError would report it, but it is now attributable."
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
        (catch Throwable t
          ;; Deliberately not cached: a transient failure should not
          ;; become a permanent "this class has no hook".
          (log/error t "HookTypeMatcher: registry query failed for" internal-name
                    "-- treating as no match, class left unwoven")
          (prune-cache-if-full!)
          false)))))

(defn- post-processor-factory
  []
  (let [cls (Class/forName "net.bytebuddy.asm.Advice$AssignReturned$Factory")
        ctor (.getDeclaredConstructor cls (into-array Class []))]
    (.setAccessible ctor true)
    (.newInstance ctor (object-array []))))

(defn- advice-locator
  "ClassFileLocator that serves the bytegen-generated advice classes from
   the bytes captured at injection time.

   It deliberately does NOT read them off the classpath: the advice
   classes are injected with
   ClassInjector$UsingInstrumentation/Target/SYSTEM, which packs them
   into a temporary jar, appends it, then closes and deletes it. A
   getResource lookup therefore fails with NoSuchFileException once the
   agent runs from the uberjar (no classpath entry exists at all) and
   even on the classpath path it would re-read four files on every
   weave. nil.kernel.kernel.bytegen/generated-class-bytes hands back the
   exact bytes that were injected."
  []
  (let [advice-names ["nihilite.trainer.HookAdvice"
                      "nihilite.trainer.ReturnAdvice"
                      "nihilite.trainer.ThrowAdvice"
                      "nihilite.trainer.RedefineAdvice"]
        pairs (into {}
                    (for [n advice-names
                          :let [bs (bg/generated-class-bytes n)]
                          :when bs]
                      [n bs]))]
    (ClassFileLocator$Simple. pairs)))

(defn- visit-advice [builder position-keys matcher-class-name]
  (if (seq position-keys)
    (let [matcher (bucket/matcher-for position-keys)
          wcm (indy/wire (Advice/withCustomMapping) matcher-class-name)
          ppf (post-processor-factory)
          loc (advice-locator)
          advice (.to (.with wcm ppf)
                      (Class/forName matcher-class-name)
                      loc)]
      (.visit builder (.on advice matcher)))
    builder))

(defn- visit-return-advice [builder position-keys]
  (if (seq position-keys)
    (let [matcher (bucket/matcher-for position-keys)
          wcm (indy/wire (Advice/withCustomMapping) "nihilite.trainer.ReturnAdvice")
          advice (.to (.with wcm (post-processor-factory))
                      (Class/forName "nihilite.trainer.ReturnAdvice")
                      (advice-locator))]
      (.visit builder (.on advice matcher)))
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
           b (visit-advice builder entry-b "nihilite.trainer.HookAdvice")
           b2 (visit-return-advice b return-b)]
       (visit-advice b2 throw-b "nihilite.trainer.ThrowAdvice"))
     builder))

(defn- wrap-redefine
  "True method-body replacement for the :redefine position.

   `Advice.wrap(Implementation)` returns an Implementation whose body is
   only the advice; applying it with `.method(matcher).intercept(...)`
   REPLACES the matched methods rather than instrumenting them, so the
   original body does not run. The delegation target is StubMethod, i.e.
   the original code simply ceases to exist.

   This is deliberately different from `visit-advice`, which instruments
   the existing body in place — :entry / :return / :throw all need the
   original body to run.

   Ordering: AgentBuilder applies visitors in registration order and
   `at-transform` registers this one first, so a :redefine spec replaces
   the body before the entry/return/throw advice is instrumented onto the
   replacement."
  [builder position-keys]
  (if (seq position-keys)
    (let [matcher (bucket/matcher-for position-keys)
          wcm (indy/wire (Advice/withCustomMapping) "nihilite.trainer.RedefineAdvice")
          ppf (post-processor-factory)
          loc (advice-locator)
          advice (.to (.with wcm ppf)
                      (Class/forName "nihilite.trainer.RedefineAdvice")
                      loc)
          replacement (.wrap advice
                             ^net.bytebuddy.implementation.Implementation
                             net.bytebuddy.implementation.StubMethod/INSTANCE)]
      (.intercept (.method builder matcher) replacement))
    builder))

(defn- apply-redefine-transformer
  "Clojure body of the generated redefine-transformer stub. Uses Advice
   (not MethodDelegation) for :redefine so it composes with retransform
   mode, and wraps rather than visits so the original method body is
   genuinely replaced. Arity 6 because gen-class forwarding for instance
   methods prepends `this`."
  [_this
   ^net.bytebuddy.dynamic.DynamicType$Builder builder
   ^net.bytebuddy.description.type.TypeDescription type-description
   ^java.lang.ClassLoader _class-loader
   ^net.bytebuddy.utility.JavaModule _module
   ^java.security.ProtectionDomain _protection-domain]
  (let [buckets (bucket/collect-buckets type-description)]
    (if-let [b buckets]
      (wrap-redefine builder (:redefine b))
      builder)))

(defn at-equals [self other] (identical? self other))
(defn at-toString [_] "nihilite.trainer.AdviceTransformer")
(defn at-hashCode [self] (System/identityHashCode self))
(defn at-clone [_]
  (throw (UnsupportedOperationException. "nihilite.trainer.AdviceTransformer/clone not supported")))

(defn hm-equals [self other] (identical? self other))
(defn hm-toString [_] "nihilite.trainer.HookTypeMatcher")
(defn hm-hashCode [self] (System/identityHashCode self))
(defn hm-clone [_]
  (throw (UnsupportedOperationException. "nihilite.trainer.HookTypeMatcher/clone not supported")))

(defn hm-matches
  "gen-class forwarding stub for HookTypeMatcher.matches(T).
   gen-class prepends `this` to instance method args (so arity 2)."
  [this type-description]
  (hook-type-matches? this type-description))

(defn at-transform
  "gen-class forwarding stub for AdviceTransformer.transform. Arity 6
   because gen-class prepends `this`.

   Composes redefine + advice in a single transformer. The :redefine
   position WRAPS the method (Advice.wrap + StubMethod) so the original
   body is replaced outright, and the entry/return/throw advice is then
   instrumented onto whatever body remains.

   The redefine call comes first deliberately. Reversing the two calls was
   measured to produce identical results, so the order here is not what
   makes the co-located hooks work — ByteBuddy applies
   `method(...).intercept(...)` and `visit(...)` in its own fixed order.
   Keeping redefine first simply mirrors the intended semantics and avoids
   depending on that detail."
  [this builder type-description class-loader module protection-domain]
  (let [b1 (apply-redefine-transformer this builder type-description class-loader module protection-domain)
        b2 (apply-advice-transformer this b1 type-description class-loader module protection-domain)]
    b2))

(defn- gen-transformer! [class-name prefix]
  (let [iface (Class/forName "net.bytebuddy.agent.builder.AgentBuilder$Transformer")]
    (cg/generate-class-bytes!
     {:name class-name
      :prefix prefix
      :impl-ns "nihilite.trainer.transformer"
      :main false
      :implements [iface]
      :methods []})))

(defn- gen-type-matcher!
  "Generates nihilite.trainer.HookTypeMatcher implementing
   net.bytebuddy.matcher.ElementMatcher. The matches(T) method is
   auto-emitted by gen-class (no :methods clause); the generated stub
   forwards to hook-type-matches? in this namespace."
  []
  (let [iface (Class/forName "net.bytebuddy.matcher.ElementMatcher")]
    (cg/generate-class-bytes!
     {:name "nihilite.trainer.HookTypeMatcher"
      :prefix "hm-"
      :impl-ns "nihilite.trainer.transformer"
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
  (gen-transformer! "nihilite.trainer.AdviceTransformer" "at-")
  nil)

(defn ensure-all!
  "Same as gen-all! but invoked at runtime (e.g. from the installer).
   Binds *compile-files* true and *compile-path* to target/classes so the
   reflection-driven clojure.core$generate_class path writes .class files
   that the JVM can load via the existing classpath."
  []
  (binding [*compile-files* true
            *compile-path* "target/classes"]
    (gen-all!))
  nil)

(when *compile-files*
  (gen-all!))
