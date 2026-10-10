(ns nihilite.trainer.advice
  "ByteBuddy advice entry points generated from Clojure via bytegen.

   Four advice classes are produced at runtime by nihilite.crafter.bytegen
   (no AOT, no gen-class):
     - nihilite.trainer.HookAdvice     OnMethodEnter,  :entry  position
     - nihilite.trainer.ReturnAdvice   OnMethodExit + AssignReturned, :return
     - nihilite.trainer.ThrowAdvice    OnMethodExit + Thrown,        :throw
     - nihilite.trainer.RedefineAdvice OnMethodExit + AssignReturned, :redefine

   The generated stub bodies forward to a Clojure function in this
   namespace (hk-/rt-/th-/rd- prefix). Those functions delegate to
   nihilite.builder.registry dispatch."

  (:require [nihilite.crafter.exceptions :as exc]
            [nihilite.crafter.annparam :as ap]
            [nihilite.crafter.bytegen :as bg]
            [clojure.tools.logging :as log])
  (:import [net.bytebuddy.implementation.bytecode.assign Assigner$Typing]))

(def ^:private advice-classes (atom {}))

;; True while an advice body is running on this thread.
;;
;; A bootstrap-loader target can be on the class loading path itself:
;; java.io.FileInputStream.read is how a class file gets turned into a Class,
;; and looking a class up or dispatching to a bridge both load classes. Without
;; this guard, hooking that read makes the advice re-enter itself through the
;; class loader and the stack overflows. Re-entering on the same thread is
;; never a hook the user asked to observe, so the nested advice returns
;; immediately.
(def ^:private ^ThreadLocal in-advice (ThreadLocal.))

(defn- reentrancy-guard
  "Runs body unless this thread is already inside an advice.

   Targets on the class loading path make that unavoidable: the advice body
   has to load classes (looking dispatch up through RT/var, calling the
   bridge), class loading reads .class bytes, and those bytes travel through
   the very method being hooked. The cycle is structural, not probabilistic.

   Verified on an ordinary target: the retransform driver installs a bridge
   that re-enters the hooked method and asserts the nested advice never
   dispatches.

   It does not close every cycle, and it is not always reached. A hook is
   unsafe exactly when the advice's own machinery needs the hooked method --
   String, Object identity, class loading. On those targets the cycle can
   close ABOVE this guard: the generated advice stub resolves its forwarder var
   by name on every call (bytegen/forwarder-implementation emits Var.intern),
   and touching Namespace/Var can load classes, which can read bytes through
   the hooked method. Measured: java.lang.String.length, String.hashCode and
   Object.equals overflow the stack however this flag is set.

   java.io.FileInputStream.read is the case that motivates not trusting any of
   this from the outside. Depending on classpath shape it either overflows, or
   registers with a woven count of 1 and never runs the advice at all -- the
   entry point is never reached, so this guard is never consulted. Both look
   identical through the API. nihilite.test.prod-bootstrap-driver prints the
   measurement; it is not a gate, because a coin flip is not a test."
  [body]
  (if (.get ^ThreadLocal in-advice)
    ::reentered
    (do
      (.set ^ThreadLocal in-advice true)
      (try
        (body)
        (finally
          (.remove ^ThreadLocal in-advice))))))

(defn- lookup-spec [host-internal method-name arg-count descriptor phase]
  (let [lookup (clojure.lang.RT/var "nihilite.builder.registry.dispatch" "lookup-spec-for-call")]
    (.invoke ^clojure.lang.IFn lookup host-internal method-name arg-count descriptor phase)))

(defn hk-onEntry
  "Forwarder body called by the generated HookAdvice.onEntry stub."
  [^String method-name ^java.lang.Class host-class ^String descriptor ^Object self ^[Object] args]
  (let [result (reentrancy-guard
                 (fn []
                   (let [spec-id (try
                                   (lookup-spec (ap/host-internal host-class) method-name
                                                (if (nil? args) 0 (alength args)) descriptor "entry")
                                   (catch Throwable t
                                     (log/error t "entry advice lookup failed")
                                     (throw (exc/advice-ex! nil t))))]
                     (when (not (nil? spec-id))
                       (let [dispatch (clojure.lang.RT/var "nihilite.builder.registry.dispatch" "dispatch-for-spec")
                             result (try
                                      (.invoke ^clojure.lang.IFn dispatch spec-id self args)
                                      (catch nihilite.crafter.HookCancelledException e
                                        (throw e))
                                      (catch Throwable t
                                        (log/error t "entry advice dispatch failed")
                                        (throw (exc/advice-ex! spec-id t))))]
                         (when (= result (clojure.lang.Keyword/intern "nihilite" "short-circuit"))
                           (throw (exc/cancelled!)))
                         nil)))))]
    (when (= ::reentered result) nil)))

(defn rt-onExit
  "Forwarder body called by the generated ReturnAdvice.onExit stub. Returns
   the original value when no spec matches; otherwise delegates to
   dispatch-return."
  [^String method-name ^java.lang.Class host-class ^String descriptor
   ^Object self ^[Object] args ^Object original]
  (let [result (reentrancy-guard
                 (fn []
                   (try
                     (let [spec-id (lookup-spec (ap/host-internal host-class) method-name
                                                (if (nil? args) 0 (alength args)) descriptor "return")]
                       (if (nil? spec-id)
                         original
                         (.invoke ^clojure.lang.IFn
                                  (clojure.lang.RT/var "nihilite.builder.registry.dispatch" "dispatch-return-for-spec")
                                  spec-id self args original)))
                     (catch Throwable t
                       (log/error t "return advice dispatch failed")
                       (throw (exc/advice-ex! nil t))))))]
    (if (= ::reentered result) original result)))

(defn th-onThrow
  "Forwarder body called by the generated ThrowAdvice.onThrow stub."
  [^String method-name ^java.lang.Class host-class ^String descriptor
   ^Object self ^[Object] args ^Throwable thrown]
  (when-not (nil? thrown)
    (reentrancy-guard
      (fn []
        (try
          (let [spec-id (lookup-spec (ap/host-internal host-class) method-name
                                     (if (nil? args) 0 (alength args)) descriptor "throw")]
            (when-not (nil? spec-id)
              (.invoke ^clojure.lang.IFn
                       (clojure.lang.RT/var "nihilite.builder.registry.dispatch" "dispatch-throw-for-spec")
                       spec-id self args thrown)))
          (catch Throwable t
            (log/error t "throw advice dispatch failed")
            (throw (exc/advice-ex! nil t))))))))

(defn- rd-dispatch
  "Resolves the :redefine spec and hands the call to the redefine dispatcher.
   Split out of rd-onRedefine so the reentrancy guard there stays shallow."
  [^String method-name ^java.lang.Class host-class ^String descriptor
   ^Object self ^[Object] args]
  (let [spec-id (try
                  (lookup-spec (ap/host-internal host-class) method-name
                               (if (nil? args) 0 (alength args)) descriptor "redefine")
                  (catch Throwable t
                    (log/error t "redefine advice lookup failed")
                    (throw (exc/advice-ex! nil t))))]
    (when-not (nil? spec-id)
      (try
        (let [ref-var (clojure.lang.RT/var "nihilite.builder.registry" "redefine-dispatcher-ref")
              reinstaller (some-> ^clojure.lang.Atom (deref ref-var) deref)
              host (ap/host-internal host-class)]
          (when reinstaller
            (.invoke ^clojure.lang.IFn reinstaller host method-name self args descriptor)))
        (catch Throwable t
          (log/error t "redefine advice dispatch failed")
          (throw (exc/advice-ex! spec-id t)))))))

(defn rd-onRedefine
  "Forwarder body called by the generated RedefineAdvice.onRedefine stub.

   The :redefine position WRAPS the target method, so the original body
   does not exist at runtime: there is no ctx event, and the only way to
   influence the call is to return a value. The bridge is therefore called
   as (self, args, method-name) and its return value becomes the method's
   return value. With no spec installed, or no dispatcher yet, the method
   returns null."
  [^String method-name ^java.lang.Class host-class ^String descriptor
   ^Object self ^[Object] args _return-slot]
  (let [result (reentrancy-guard
                 (fn [] (rd-dispatch method-name host-class descriptor
                                        self args)))]
    (if (= ::reentered result) nil result)))

(def ^:private origin-m-param     (delay (bg/anno net.bytebuddy.asm.Advice$Origin {:value "#m"})))
(def ^:private origin-c-param     (delay (bg/anno net.bytebuddy.asm.Advice$Origin {})))
(def ^:private origin-d-param     (delay (bg/anno net.bytebuddy.asm.Advice$Origin {:value "#d"})))
(def ^:private this-param         (delay (bg/anno net.bytebuddy.asm.Advice$This {:optional true})))
(def ^:private all-args-param     (delay (bg/anno net.bytebuddy.asm.Advice$AllArguments {})))
(def ^:private return-dyn-param   (delay (bg/anno net.bytebuddy.asm.Advice$Return
                                            {:typing Assigner$Typing/DYNAMIC})))
(def ^:private thrown-param       (delay (bg/anno net.bytebuddy.asm.Advice$Thrown {})))

(defn- enter-anno []
  (let [exc-cls (Class/forName "nihilite.crafter.HookCancelledException")]
    (bg/anno net.bytebuddy.asm.Advice$OnMethodEnter
             {:inline false
              :skipOn exc-cls})))
(def ^:private on-exit-anno       (delay (bg/anno net.bytebuddy.asm.Advice$OnMethodExit
                                                  {:inline false
                                                   :onThrowable Throwable
                                                   :suppress Throwable})))
(def ^:private on-exit-noop-anno  (delay (bg/anno net.bytebuddy.asm.Advice$OnMethodExit
                                                  {:inline false})))
(def ^:private to-return-anno     (delay (bg/anno net.bytebuddy.asm.Advice$AssignReturned$ToReturned
                                            {:typing Assigner$Typing/DYNAMIC})))

(def ^:private common-params
  ["java.lang.String" "java.lang.Class" "java.lang.String"
   "java.lang.Object" "[Ljava.lang.Object;"])

(def ^:private common-param-annos
  [@origin-m-param @origin-c-param @origin-d-param @this-param @all-args-param])

(def ^:private hook-spec
  {:methods
   [{:name "onEntry"
     :static? true
     :return "java.lang.Object"
     :params common-params
     :param-annos common-param-annos
     :method-annos [(enter-anno)]
     :forward-var 'nihilite.trainer.advice/hk-onEntry}]})

(def ^:private return-spec
  {:methods
   [{:name "onExit"
     :static? true
     :return "java.lang.Object"
     :params (conj (vec common-params) "java.lang.Object")
     :param-annos (conj (vec common-param-annos) @return-dyn-param)
     :method-annos [@to-return-anno @on-exit-anno]
     :forward-var 'nihilite.trainer.advice/rt-onExit}]})

;; onThrow returns Object rather than void on purpose. A void advice under
;; the invokedynamic dispatch does not bind: the woven call site ends up with
;; no target the bootstrap can point at, so the :throw advice silently never
;; runs. Nothing reads the return value here — @Advice$Thrown and
;; @Advice@AllArguments carry every input the position needs — so the
;; Object return costs nothing and keeps the position working.
(def ^:private throw-spec
  {:methods
   [{:name "onThrow"
     :static? true
     :return "java.lang.Object"
     :params (conj (vec common-params) "java.lang.Throwable")
     :param-annos (conj (vec common-param-annos) @thrown-param)
     :method-annos [@on-exit-anno]
     :throws ["java.lang.Throwable"]
     :forward-var 'nihilite.trainer.advice/th-onThrow}]})

(def ^:private redefine-spec
  "The :redefine advice.

   Woven via Advice.wrap, which REPLACES the method body rather than
   instrumenting it — the original body does not run at all.

   Because the body is gone there is no ORIGINAL return value to inspect,
   but @Advice.Return is a PARAMETER-targeted annotation (its @Target is
   PARAMETER, so it cannot be applied to the method), so the advice binds
   the return slot as its own value and @Advice.AssignReturned.ToReturned
   writes that value into the target method's return slot. Net effect: the
   bridge's return value becomes the method's return value."
  {:methods
   [{:name "onRedefine"
     :static? true
     :return "java.lang.Object"
     :params (conj (vec common-params) "java.lang.Object")
     :param-annos (conj (vec common-param-annos) @return-dyn-param)
     :method-annos [@to-return-anno @on-exit-noop-anno]
     :forward-var 'nihilite.trainer.advice/rd-onRedefine}]})

(defn- ensure-class! [class-name spec inst]
  (or (@advice-classes class-name)
      (let [loaded (bg/define-class! (assoc spec :name class-name :instrumentation inst))]
        (swap! advice-classes assoc class-name loaded)
        loaded)))

(defn- ensure-hook-advice! [inst]
  (ensure-class! "nihilite.trainer.HookAdvice" hook-spec inst))

(defn- ensure-return-advice! [inst]
  (ensure-class! "nihilite.trainer.ReturnAdvice" return-spec inst))

(defn- ensure-throw-advice! [inst]
  (ensure-class! "nihilite.trainer.ThrowAdvice" throw-spec inst))

(defn- ensure-redefine-advice! [inst]
  (ensure-class! "nihilite.trainer.RedefineAdvice" redefine-spec inst))

(defn ensure-all!
  "Generates all four advice classes into the system classloader.
   The 0-arg form resolves Instrumentation from the agent (premain/agentmain
   or a dynamically-attached ByteBuddyAgent). The 1-arg form accepts an
   explicit Instrumentation from the caller (e.g. the driver)."
  ([] (ensure-all! (when-let [v (resolve 'nihilite.trainer.agent/agent-currentInstrumentation)]
                     (.invoke ^clojure.lang.IFn v))))
  ([^java.lang.instrument.Instrumentation inst]
   (ensure-hook-advice! inst)
   (ensure-return-advice! inst)
   (ensure-throw-advice! inst)
   (ensure-redefine-advice! inst)
   nil))