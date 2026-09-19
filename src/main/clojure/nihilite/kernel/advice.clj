(ns nihilite.kernel.advice
  "ByteBuddy advice entry points generated from Clojure via bytegen.

   Four advice classes are produced at runtime by nihilite.kernel.bytegen
   (no AOT, no gen-class):
     - nihilite.kernel.HookAdvice     OnMethodEnter,  :entry  position
     - nihilite.kernel.ReturnAdvice   OnMethodExit + AssignReturned, :return
     - nihilite.kernel.ThrowAdvice    OnMethodExit + Thrown,        :throw
     - nihilite.kernel.RedefineAdvice OnMethodExit + AssignReturned, :redefine

   The generated stub bodies forward to a Clojure function in this
   namespace (hk-/rt-/th-/rd- prefix). Those functions delegate to
   nihilite.registry dispatch."

  (:require [nihilite.kernel.exceptions :as exc]
            [nihilite.kernel.annparam :as ap]
            [nihilite.kernel.bytegen :as bg]
            [clojure.tools.logging :as log])
  (:import [net.bytebuddy.implementation.bytecode.assign Assigner$Typing]))

(def ^:private advice-classes (atom {}))

(defn- lookup-spec [host-internal method-name arg-count descriptor phase]
  (let [lookup (clojure.lang.RT/var "nihilite.registry.dispatch" "lookup-spec-for-call")]
    (.invoke ^clojure.lang.IFn lookup host-internal method-name arg-count descriptor phase)))

(defn hk-onEntry
  "Forwarder body called by the generated HookAdvice.onEntry stub."
  [^String method-name ^java.lang.Class host-class ^String descriptor ^Object self ^[Object] args]
  (let [spec-id (try
                  (lookup-spec (ap/host-internal host-class) method-name
                                (if (nil? args) 0 (alength args)) descriptor "entry")
                  (catch Throwable t
                    (log/error t "entry advice lookup failed")
                    (throw (exc/advice-ex! nil t))))]
    (when (not (nil? spec-id))
      (let [dispatch (clojure.lang.RT/var "nihilite.registry.dispatch" "dispatch-for-spec")
            result (try
                     (.invoke ^clojure.lang.IFn dispatch spec-id self args)
                     (catch Throwable t
                       (log/error t "entry advice dispatch failed")
                       (throw (exc/advice-ex! spec-id t))))]
        (when (= result (clojure.lang.Keyword/intern "nihilite" "short-circuit"))
          (throw (exc/cancelled!)))
        nil))))

(defn rt-onExit
  "Forwarder body called by the generated ReturnAdvice.onExit stub. Returns
   the original value when no spec matches; otherwise delegates to
   dispatch-return."
  [^String method-name ^java.lang.Class host-class ^String descriptor
   ^Object self ^[Object] args ^Object original]
  (try
    (let [spec-id (lookup-spec (ap/host-internal host-class) method-name
                               (if (nil? args) 0 (alength args)) descriptor "return")]
      (if (nil? spec-id)
        original
        (.invoke ^clojure.lang.IFn
                 (clojure.lang.RT/var "nihilite.registry.dispatch" "dispatch-return-for-spec")
                 spec-id self args original)))
    (catch Throwable t
      (log/error t "return advice dispatch failed")
      (throw (exc/advice-ex! nil t)))))

(defn th-onThrow
  "Forwarder body called by the generated ThrowAdvice.onThrow stub."
  [^String method-name ^java.lang.Class host-class ^String descriptor
   ^Object self ^[Object] args ^Throwable thrown]
  (when-not (nil? thrown)
    (try
      (let [spec-id (lookup-spec (ap/host-internal host-class) method-name
                                 (if (nil? args) 0 (alength args)) descriptor "throw")]
        (when-not (nil? spec-id)
          (.invoke ^clojure.lang.IFn
                   (clojure.lang.RT/var "nihilite.registry.dispatch" "dispatch-throw-for-spec")
                   spec-id self args thrown)))
      (catch Throwable t
        (log/error t "throw advice dispatch failed")
        (throw (exc/advice-ex! nil t))))))

(defn rd-onRedefine
  "Forwarder body called by the generated RedefineAdvice.onRedefine stub.
   Returning the original value leaves the target method body untouched."
  [^String method-name ^java.lang.Class host-class ^String descriptor
   ^Object self ^[Object] args ^Object original]
  (let [spec-id (try
                  (lookup-spec (ap/host-internal host-class) method-name
                                (if (nil? args) 0 (alength args)) descriptor "redefine")
                  (catch Throwable t
                    (log/error t "redefine advice lookup failed")
                    (throw (exc/advice-ex! nil t))))]
    (if (nil? spec-id)
      original
      (try
        (let [reinstaller-var (clojure.lang.RT/var "nihilite.registry.dispatch" "redefine-dispatcher")
              reinstaller (deref ^clojure.lang.Atom reinstaller-var)
              host (ap/host-internal host-class)]
          (if (nil? reinstaller)
            original
            (let [result (.invoke ^clojure.lang.IFn reinstaller host method-name self args descriptor)]
              (if (nil? result) original result))))
        (catch Throwable t
          (log/error t "redefine advice dispatch failed")
          (throw (exc/advice-ex! spec-id t)))))))

(def ^:private origin-m-param     (delay (bg/anno net.bytebuddy.asm.Advice$Origin {:value "#m"})))
(def ^:private origin-c-param     (delay (bg/anno net.bytebuddy.asm.Advice$Origin {})))
(def ^:private origin-d-param     (delay (bg/anno net.bytebuddy.asm.Advice$Origin {:value "#d"})))
(def ^:private this-param         (delay (bg/anno net.bytebuddy.asm.Advice$This {:optional true})))
(def ^:private all-args-param     (delay (bg/anno net.bytebuddy.asm.Advice$AllArguments {})))
(def ^:private return-dyn-param   (delay (bg/anno net.bytebuddy.asm.Advice$Return
                                            {:typing Assigner$Typing/DYNAMIC})))
(def ^:private thrown-param       (delay (bg/anno net.bytebuddy.asm.Advice$Thrown {})))

(def ^:private on-enter-anno      (delay (bg/anno net.bytebuddy.asm.Advice$OnMethodEnter {})))
(def ^:private on-exit-anno       (delay (bg/anno net.bytebuddy.asm.Advice$OnMethodExit
                                            {:onThrowable Throwable
                                             :suppress Throwable})))
(def ^:private on-exit-noop-anno  (delay (bg/anno net.bytebuddy.asm.Advice$OnMethodExit {})))
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
     :return "void"
     :params common-params
     :param-annos common-param-annos
     :method-annos [@on-enter-anno]}]})

(def ^:private return-spec
  {:methods
   [{:name "onExit"
     :static? true
     :return "java.lang.Object"
     :params (conj (vec common-params) "java.lang.Object")
     :param-annos (conj (vec common-param-annos) @return-dyn-param)
     :method-annos [@to-return-anno @on-exit-anno]}]})

(def ^:private throw-spec
  {:methods
   [{:name "onThrow"
     :static? true
     :return "void"
     :params (conj (vec common-params) "java.lang.Throwable")
     :param-annos (conj (vec common-param-annos) @thrown-param)
     :method-annos [@on-exit-anno]
     :throws ["java.lang.Throwable"]}]})

(def ^:private redefine-spec
  {:methods
   [{:name "onRedefine"
     :static? true
     :return "java.lang.Object"
     :params (conj (vec common-params) "java.lang.Object")
     :param-annos (conj (vec common-param-annos) @return-dyn-param)
     :method-annos [@to-return-anno @on-exit-noop-anno]}]})

(defn- ensure-class! [class-name spec]
  (or (@advice-classes class-name)
      (let [loaded (bg/define-class! (assoc spec :name class-name))]
        (swap! advice-classes assoc class-name loaded)
        loaded)))

(defn ensure-hook-advice! []
  (ensure-class! "nihilite.kernel.HookAdvice" hook-spec))

(defn ensure-return-advice! []
  (ensure-class! "nihilite.kernel.ReturnAdvice" return-spec))

(defn ensure-throw-advice! []
  (ensure-class! "nihilite.kernel.ThrowAdvice" throw-spec))

(defn ensure-redefine-advice! []
  (ensure-class! "nihilite.kernel.RedefineAdvice" redefine-spec))

(defn ensure-all! []
  (ensure-hook-advice!)
  (ensure-return-advice!)
  (ensure-throw-advice!)
  (ensure-redefine-advice!))