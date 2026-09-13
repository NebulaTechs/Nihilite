(ns nihilite.kernel.advice
  "ByteBuddy advice entry points generated from Clojure.

   Three advice classes are produced into *compile-path* during AOT:
     - nihilite.kernel.HookAdvice    OnMethodEnter,  :entry  position
     - nihilite.kernel.ReturnAdvice   OnMethodExit + AssignReturned, :return
     - nihilite.kernel.ThrowAdvice    OnMethodExit + Thrown,        :throw

   Each generated stub forwards to a var in this namespace (hk-/rt-/th-
   prefix). Those vars delegate to nihilite.registry dispatch functions.

   The classes are emitted by invoking the private clojure.core$generate_class
   function directly, because the ns gen-class form spec rejects array
   parameter types (Object/1) required for the @AllArguments parameter."
  (:require [nihilite.kernel.classgen :as cg]
            [nihilite.kernel.exceptions :as exc]
            [nihilite.kernel.annparam :as ap]
            [clojure.tools.logging :as log]))

(defn- lookup-spec [host-internal method-name arg-count descriptor phase]
  (let [lookup (clojure.java.api.Clojure/var "nihilite.registry.dispatch" "lookup-spec-for-call")]
    (.invoke ^clojure.lang.IFn lookup host-internal method-name arg-count descriptor phase)))

(defn hk-onEntry
  [^String method-name ^java.lang.Class host-class ^String descriptor ^Object self ^[Object] args]
  (let [spec-id (try
                  (lookup-spec (ap/host-internal host-class) method-name
                                (if (nil? args) 0 (alength args)) descriptor "entry")
                  (catch Throwable t
                    (log/error t "entry advice lookup failed")
                    (throw (exc/advice-ex! nil t))))]
    (when (not (nil? spec-id))
      (let [dispatch (clojure.java.api.Clojure/var "nihilite.registry.dispatch" "dispatch-for-spec")
            result (try
                     (.invoke ^clojure.lang.IFn dispatch spec-id self args)
                     (catch Throwable t
                       (log/error t "entry advice dispatch failed")
                       (throw (exc/advice-ex! spec-id t))))]
        (when (= result (clojure.lang.Keyword/intern "nihilite" "short-circuit"))
          (throw (exc/cancelled!)))
        nil))))

(defn rt-onExit
  [^String method-name ^java.lang.Class host-class ^String descriptor
   ^Object self ^[Object] args ^Object original]
  (try
    (let [spec-id (lookup-spec (ap/host-internal host-class) method-name
                               (if (nil? args) 0 (alength args)) descriptor "return")]
      (if (nil? spec-id)
        original
        (.invoke ^clojure.lang.IFn
                 (clojure.java.api.Clojure/var "nihilite.registry.dispatch" "dispatch-return-for-spec")
                 spec-id self args original)))
    (catch Throwable t
      (log/error t "return advice dispatch failed")
      (throw (exc/advice-ex! nil t)))))

(defn th-onThrow
  [^String method-name ^java.lang.Class host-class ^String descriptor
   ^Object self ^[Object] args ^Throwable thrown]
  (when-not (nil? thrown)
    (try
      (let [spec-id (lookup-spec (ap/host-internal host-class) method-name
                                 (if (nil? args) 0 (alength args)) descriptor "throw")]
        (when-not (nil? spec-id)
          (.invoke ^clojure.lang.IFn
                   (clojure.java.api.Clojure/var "nihilite.registry.dispatch" "dispatch-throw-for-spec")
                   spec-id self args thrown)))
      (catch Throwable t
        (log/error t "throw advice dispatch failed")
        (throw (exc/advice-ex! nil t))))))

(defn- origin-param [value]
  (with-meta (symbol "String")
              (read-string (str "{net.bytebuddy.asm.Advice$Origin \"" value "\"}"))))

(defn- origin-class-param []
  (with-meta (symbol "Class")
              (read-string "{net.bytebuddy.asm.Advice$Origin {}}")))

(defn- this-param []
  (with-meta (symbol "Object")
              (read-string "{net.bytebuddy.asm.Advice$This {:optional true}}")))

(defn- all-args-param []
  (with-meta (symbol "Object/1")
              (read-string "{net.bytebuddy.asm.Advice$AllArguments {}}")))

(defn- return-dynamic-param []
  (with-meta (symbol "Object")
              (read-string
               "{net.bytebuddy.asm.Advice$Return {:typing
                                                   net.bytebuddy.implementation.bytecode.assign.Assigner$Typing/DYNAMIC}}")))

(defn- thrown-param []
  (with-meta (symbol "Throwable")
              (read-string "{net.bytebuddy.asm.Advice$Thrown {}}")))

(defn- gen-hook-advice! []
  (let [mname (with-meta (symbol "onEntry")
                (read-string "{net.bytebuddy.asm.Advice$OnMethodEnter {:inline false}}"))
        pclasses [(origin-param "#m")
                   (origin-class-param)
                   (origin-param "#d")
                  (this-param)
                  (all-args-param)]
        msig (with-meta (vector mname pclasses (symbol "Object")) {:static true})]
    (cg/generate-class-bytes!
     {:name "nihilite.kernel.HookAdvice"
      :prefix "hk-"
      :impl-ns "nihilite.kernel.advice"
      :main false
      :methods [msig]})))

(defn- gen-return-advice! []
  (let [mname (with-meta (symbol "onExit")
                (read-string
                 "{net.bytebuddy.asm.Advice$AssignReturned/ToReturned
                    {:typing
                     net.bytebuddy.implementation.bytecode.assign.Assigner$Typing/DYNAMIC}
                    net.bytebuddy.asm.Advice$OnMethodExit
                    {:inline false
                     :onThrowable Throwable
                     :suppress Throwable}}"))
        pclasses [(origin-param "#m")
                   (origin-class-param)
                   (origin-param "#d")
                  (this-param)
                  (all-args-param)
                  (return-dynamic-param)]
        msig (with-meta (vector mname pclasses (symbol "Object")) {:static true})]
    (cg/generate-class-bytes!
     {:name "nihilite.kernel.ReturnAdvice"
      :prefix "rt-"
      :impl-ns "nihilite.kernel.advice"
      :main false
      :methods [msig]})))

(defn- gen-throw-advice! []
  (let [mname (with-meta (symbol "onThrow")
                (read-string
                 "{net.bytebuddy.asm.Advice$OnMethodExit
                    {:inline false
                     :onThrowable Throwable
                     :suppress Throwable}}"))
        pclasses [(origin-param "#m")
                   (origin-class-param)
                   (origin-param "#d")
                  (this-param)
                  (all-args-param)
                  (thrown-param)]
        msig (with-meta (vector mname pclasses (symbol "void")) {:static true})]
    (cg/generate-class-bytes!
     {:name "nihilite.kernel.ThrowAdvice"
      :prefix "th-"
      :impl-ns "nihilite.kernel.advice"
      :main false
      :methods [msig]})))

(defn gen-all!
  "Generates HookAdvice, ReturnAdvice and ThrowAdvice into *compile-path*.
   Intended to run during AOT compilation of this namespace; it is a no-op
   outside of a compile because writeClassFile only writes when
   *compile-files* is set."
  []
  (gen-hook-advice!)
  (gen-return-advice!)
  (gen-throw-advice!)
   nil)

(when *compile-files*
  (gen-all!))
