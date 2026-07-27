(ns nihilite.registry
  "Generic, loader-agnostic registry of hook specs + dispatch helpers.

   Replaces the previous Java data layer
   (`nihilite.hooks.HookSpec` + `HooksRegistry` + `HookPosition`
   + `HookPhase` + `HookContext`) with Clojure defrecords + an atom-
   backed by-id index plus a by-target-indexed list of specs per
   class-internal-name. The Java agent shell now stays slim
   (Bridge.fire static glue + ASM transformer) and looks up specs
   via `Clojure.var(\"nihilite.registry\", \"matching\").invoke(...)`.

   Concurrency: install/uninstall is infrequent (operator-time);
   matching is the hot path (per class-load on JVM startup). Two
   `java.util.concurrent.ConcurrentHashMap`s back both indexes —
   `by-id` for fast unregister/lookup, `by-target` for the
   transformer's per-class-name lookup. `by-target.values()` returns
   a stable snapshot at call time; the manifest's
   Can-Retransform-Classes=false locks out later retransforms.

   Bridge contract: `Bridge.fire(id, self, args)` calls
   `dispatch` here which builds a HookContext map and invokes the
   cell-backed IFn from the spec. Errors are swallowed after
   logging; the instrumented engine never sees a Nihilite exception."
  (:require [clojure.tools.logging :as log])
  (:import [java.util.concurrent ConcurrentHashMap]))

;; position keyword set

(def ^:const ENTRY :entry)
(def ^:const EXCATCH :excatch)
(def ^:const RETURN :return)

;; defrecords

(defrecord HookSpec
  [id target-internal method-name position arity bridge note])

(defrecord HookContext
  [hookId self args phase returnValue cancelled])

;; indexes

(defonce ^:private ^ConcurrentHashMap by-id
  (ConcurrentHashMap.))

(defonce ^:private ^ConcurrentHashMap by-target
  (ConcurrentHashMap.))

(defn- bucket
  "Get-or-create the CopyOnWrite list for `target-internal`."
  ^java.util.List [t]
  (or (.get by-target t)
      (let [fresh (java.util.concurrent.CopyOnWriteArrayList.)]
        (if (nil? (.putIfAbsent by-target t fresh))
          fresh
          (.get by-target t)))))

;; install / uninstall

(defn install!
  "Add or replace a spec keyed by `:id`. Returns true on install,
   false on replace (spec was already present under that id). The
   spec is a map or HookSpec record with at minimum
   `:id :target-internal :method-name :position :arity :bridge :note`.

   Required-shape validation:
     :id                unique non-empty string
     :target-internal  JVM internal name (e.g. \"net/minecraft/server/MinecraftServer\")
     :method-name       non-empty string
     :position         one of :entry (only entry wired)
     :arity             integer ≥ 0 (or nil = any arity)
     :bridge            IFn or atom-backed delegating IFn
     :note              freeform string

   Concurrent-safe: by-id is a ConcurrentHashMap, by-target uses
   CopyOnWriteArrayList per bucket."
  [spec]
  (let [{:keys [id target-internal method-name position arity bridge note]} spec
        spec-id (str id)
        spec-target (str target-internal)
        spec-method (str method-name)
        spec-pos (if (keyword? position) position
                     (case (str position)
                       ("ENTRY" "entry") :entry
                       ("EXCATCH" "excatch") :excatch
                       ("RETURN" "return") :return
                       :entry))
        spec-arity (when arity (int arity))]
    (when (empty? spec-id)
      (throw (ex-info ":id required for HookSpec" {:spec spec})))
    (when (empty? spec-target)
      (throw (ex-info ":target-internal required for HookSpec" {:spec spec})))
    (when (empty? spec-method)
      (throw (ex-info ":method-name required for HookSpec" {:spec spec})))
    (when (and (some? arity) (or (not (integer? arity)) (neg? arity)))
      (throw (ex-info ":arity must be a non-negative integer or nil" {:spec spec})))
    (let [norm-spec (assoc spec
                          :id spec-id
                          :target-internal spec-target
                          :method-name spec-method
                          :position spec-pos
                          :arity spec-arity)
          prev (.put by-id (:id norm-spec) norm-spec)
          replaced? (some? prev)]
      (when replaced?
        (let [prev-bucket (.get by-target (:target-internal prev))]
          (when prev-bucket (.remove prev-bucket prev))))
      (.add (bucket (:target-internal norm-spec)) norm-spec)
      (if replaced?
        (do (log/info "hook replaced:" (:id norm-spec) "target=" (:target-internal norm-spec)) false)
        (do (log/info "hook registered:" (:id norm-spec)
                 "target=" (:target-internal norm-spec)
                 "method=" (:method-name norm-spec)
                 "@" (:position norm-spec)
                 (when-let [n (:note norm-spec)] (str "// " n)))
            true)))))

(defn uninstall!
  "Remove a spec by id. Returns true if removed, false if missing."
  [id]
  (when-let [removed (.remove by-id (str id))]
    (let [b (.get by-target (:target-internal removed))]
      (when b (.remove b removed))
      (when (and b (.isEmpty b)) (.remove by-target (:target-internal removed) b))
      (log/info "hook removed:" (:id removed))
      true)))

(defn clear!
  "Drop every spec. Test/diagnostic only."
  []
  (.clear by-id)
  (.clear by-target)
  nil)

;; read views (the transformer consumes these)

(defn matching
  "Return the live list of specs targeting `target-internal`. The
   returned list is a stable snapshot at call time; concurrent
   installs after this call are invisible to that snapshot."
  ^java.util.List [target-internal]
  (let [b (.get by-target target-internal)]
    (if b (vec b) [])))

(defn- bridge->ifn
  "If `bridge` is an IFn, return it as-is; otherwise nil (treat as
   inert / pre-registered-empty spec)."
  ^clojure.lang.IFn [bridge]
  (if (instance? clojure.lang.IFn bridge)
    ^clojure.lang.IFn bridge
    nil))

(defn lookup
  "Spec by id, or nil."
  [id]
  (.get by-id (str id)))

(defn lookup-spec-for-call
  "ByteBuddy Advice entry-point helper. Given a call site's
   class-internal name, method name, and parameter count, return
   the first matching spec's `:id` (string) or nil.

   Matching rule: spec's `:method-name` must equal `method-name`
   AND (spec's `:arity` is nil [match any] OR spec's `:arity`
   equals `parameter-count`). Return type is intentionally NOT
   compared -- multiple overloads of the same name with the same
   arity share the same advice entry; dispatcher routes on full
   descriptor if needed.

   Used by `nihilite.hooks.HookAdvice.onEntry` from inlined advice
   bytecode. Per-class-list allocation is avoided by going through
   the existing `by-target` index. Returns nil if no match."
  [class-internal method-name parameter-count]
  (let [b (.get by-target class-internal)]
    (when b
      (let [iname (str method-name)
            pcnt  (int parameter-count)]
        (loop [bucket (vec b)]
          (when-let [s (first bucket)]
            (let [mn (:method-name s)
                  ar (:arity s)]
              (if (and (= mn iname)
                       (or (nil? ar) (= ar pcnt)))
                (:id s)
                (recur (next bucket))))))))))

(defn dispatch-for-spec
  "ByteBuddy Advice entry-point helper. Direct-dispatch variant
   of `dispatch`: given an already-resolved spec-id, build a
   HookContext and invoke the spec's `bridge` IFn. Skips the
   spec-id -> spec map lookup that `dispatch` does (since
   `lookup-spec-for-call` already resolved it).

   Errors are swallowed and logged -- the instrumented engine
   must never see a Nihilite exception.

   Tags ctx.phase = :entry so Clojure-side handlers can dispatch
   on phase."
  [spec-id self args]
  (try
    (when-let [spec (lookup spec-id)]
      (when-let [bridge-fn (bridge->ifn (:bridge spec))]
        (let [ctx (->HookContext spec-id self
                                (or args (object-array 0))
                                ENTRY
                                nil
                                false)]
          (try
            (bridge-fn ctx)
            (catch Throwable t
              (log/error t "bridge fire failed (id=" spec-id ")"))))))
    (catch Throwable t
      (try (log/error t "registry dispatch-for-spec failed (id=" spec-id ")")
           (catch Throwable _)))))

(defn dispatch-return-for-spec
  "ByteBuddy Advice OnMethodExit helper. Builds a HookContext tagged
   phase=:return and invokes the spec's `bridge` IFn with the
   context. Returns the bridge fn's return value (used to overwrite
   the JVM return slot via @Advice.Return(readOnly=false)).

   Bridge contract: receives `ctx` with `:returnValue` already
   populated; may return a new value (typed per the host method's
   return type; mismatches throw ClassCastException at JVM level)
   or return nil/ctx.returnValue to leave the original unchanged."
  [spec-id self args current-return]
  (try
    (when-let [spec (lookup spec-id)]
      (when-let [bridge-fn (bridge->ifn (:bridge spec))]
        (let [ctx (->HookContext spec-id self
                                (or args (object-array 0))
                                RETURN
                                current-return
                                false)]
          (try
            (let [r (bridge-fn ctx)]
              (cond
                (nil? r) current-return
                (identical? r ctx) current-return
                :else r))
            (catch Throwable t
              (log/error t "bridge return-fire failed (id=" spec-id ")")
              current-return)))))
    (catch Throwable t
      (try (log/error t "registry dispatch-return-for-spec failed (id=" spec-id ")")
           (catch Throwable _))
      current-return)))

(defn dispatch-redefine
  "ByteBuddy MethodDelegation helper. Original method body has been
   REPLACED by a static call to GenericDispatcher.dispatch(hostInternal,
   methodName, args). We look up the spec by class+name+arity and
   invoke the bridge fn.

   Bridge contract: receives positional args `[args method-name]`.
   Bridge fn must return a value typed per the original method's
   declared return type, OR throw (Throwable propagates to the
   engine). The DynamicAssigner configured in HookInstaller inserts
   the runtime CHECKCAST when the bridge returns Object and the target
   expects a narrower type."
  [host-internal method-name args]
  (try
    (let [param-count (count args)
          spec-id (lookup-spec-for-call host-internal method-name param-count)]
      (if-let [spec (and spec-id (lookup spec-id))]
        (if-let [bridge-fn (bridge->ifn (:bridge spec))]
          (try
            (bridge-fn args method-name)
            (catch Throwable t
              (log/error t "bridge redefine-fire failed (id=" spec-id ")")
              (throw t)))
          (throw (IllegalStateException.
                 (str "no bridge fn for spec id " spec-id))))
        (throw (IllegalStateException.
               (str "no spec for " host-internal "/" method-name "/" param-count)))))
    (catch Throwable t
      (throw t))))

(defn install-redefine-dispatcher!
  "Called by AgentWorker after Clojure runtime is up. Wraps
   `dispatch-redefine` in an IFn taking the MethodDelegation's
   three-argument shape `(hostInternal, methodName, args)`. Stored
   in Bridge.REDISPATCHER so GenericDispatcher can call it from
   rewritten bytecode."
  []
  (let [dispatch-ifn
        (fn [host-internal method-name args]
          (dispatch-redefine host-internal method-name args))]
    (nihilite.hooks.Bridge/installRedefineDispatcher dispatch-ifn)
    :installed))

(defn snapshot
  "Defensive copy of all (id → spec) pairs. Diagnostic."
  []
  (let [m (java.util.HashMap.)] (.putAll m by-id) m))

(defn list-ids
  "Sorted seq of registered spec ids."
  []
  (sort (vec (.keySet by-id))))

;; dispatch (Bridge.fire targets this)

(defn dispatch
  "Worker for `Bridge.fire(id, self, args)`. Builds a HookContext
   map, looks up the spec by id, invokes the spec's `bridge` IFn
   with the context. Errors are swallowed after logging.

   Tags ctx.phase = :entry so Clojure-side handlers can dispatch
   on phase."
  [id self args]
  (try
    (when-let [spec (lookup id)]
      (when-let [bridge-fn (bridge->ifn (:bridge spec))]
        (let [ctx (->HookContext id self
                                (or args (object-array 0))
                                ENTRY
                                nil
                                false)]
          (try
            (bridge-fn ctx)
            (catch Throwable t
              (log/error t "bridge fire failed (id=" id ")"))))))
    (catch Throwable t
      (try (log/error t "registry dispatch failed (id=" id ")")
           (catch Throwable _)))))

;; ctx accessors (used by Clojure-side handlers)

(defn ctx-self
  "The receiver of the instrumented call, or nil."
  [ctx]
  (when (and ctx (instance? HookContext ctx))
    (:self ctx)))

(defn ctx-arg
  "The `n`-th argument passed to the instrumented method, or nil
   if out of range."
  [ctx n]
  (when (and ctx (instance? HookContext ctx))
    (let [args (.-args ^HookContext ctx)]
      (when (and args (>= n 0) (< n (alength args)))
        (aget args (int n))))))

(defn ctx-argc
  "Number of arguments captured in ctx. Always ≥ 0."
  [ctx]
  (when (and ctx (instance? HookContext ctx))
    (let [args (.-args ^HookContext ctx)]
      (if args (alength args) 0))))

(defn ctx-return
  "The return value (populated only at :return phase; nil at
   :entry)."
  [ctx]
  (when (and ctx (instance? HookContext ctx))
    (.-returnValue ^HookContext ctx)))

(defn ctx-phase
  "The phase keyword."
  [ctx]
  (when (and ctx (instance? HookContext ctx))
    (let [^HookContext c ctx] (.-phase c))))

(defn ctx-cancel!
  "Mark ctx as cancelled (ENTRY veto knob)."
  [ctx value]
  (when (and ctx (instance? HookContext ctx))
    (set! (.-cancelled ^HookContext ctx) (boolean value))))

(defn ctx-cancelled?
  "True if ctx has been cancelled."
  [ctx]
  (and ctx (instance? HookContext ctx) (.-cancelled ^HookContext ctx)))

(defn spec
  "Convenience constructor mirroring the Java HookSpec(...) shape.
   Returns a HookSpec record."
  [id target-internal method-name position arity bridge note]
  (->HookSpec (str id) (str target-internal) (str method-name)
              (if (keyword? position) position
                  (case (str position)
                    ("ENTRY" "entry") ENTRY
                    ("EXCATCH" "excatch") EXCATCH
                    ("RETURN" "return") RETURN
                    (or position ENTRY)))
              (when arity (int arity))
              bridge
              (str note)))
