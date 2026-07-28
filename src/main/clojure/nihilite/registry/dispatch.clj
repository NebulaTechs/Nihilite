(ns nihilite.registry.dispatch
  "Lookup + redefine-dispatcher path. Bucket walks for the
   entry/return/throw/invoke phases live in the sibling
   sub-namespaces (`dispatch.entry`, `dispatch.return`,
   `dispatch.throw`, `dispatch.invoke`); the `nihilite.registry`
   facade re-exports them directly."
  (:require [clojure.tools.logging :as log]
            [nihilite.registry.spec :as rs]
            [nihilite.registry.index :as ix]
            [nihilite.registry.dispatch.util :as du])
  (:import (nihilite.registry.spec HookEvent)))

(defn spec-bucket
  "Return the relevant spec list for a given spec: the
   `by-method` bucket if the spec has a method-key, otherwise
   the `by-target` bucket (legacy fallback)."
  [spec]
  (if-let [mk (:method-key spec)]
    (some-> (.get (ix/get-by-method) mk) seq)
    (some-> (.get (ix/get-by-target) (:target-internal spec)) seq)))

(defn lookup
  "Spec by id, or nil."
  [id]
  (.get (ix/get-by-id) (str id)))

(defn lookup-spec-for-call
  "ByteBuddy Advice entry-point helper. Given a call site's
   class-internal name, method name, parameter count, and (P0)
   JLS field descriptor, return the first matching spec's `:id`
   (string) or nil.

   P0 matching rule (4-arg form): the method-key
   `(class-internal/method-name#descriptor)` must match an entry
   in `by-method` and the first spec in that bucket whose
   `:arity` is nil or equal to `parameter-count` wins.

   Legacy fallback (3-arg form): scans `by-target` by
   `(method-name, parameter-count)` only; reserved for the
   pre-P0 paths and the `by-method` miss case."
  ([class-internal method-name parameter-count descriptor]
   (let [mk (when (and (some? descriptor) (not (empty? descriptor)))
              (rs/method-key class-internal method-name descriptor))
         mb (when mk (.get (ix/get-by-method) mk))]
     (if mb
       (let [pcnt (int parameter-count)]
         (loop [bucket (vec mb)]
           (when-let [s (first bucket)]
             (let [ar (:arity s)]
               (if (or (nil? ar) (= ar pcnt))
                 (:id s)
                 (recur (next bucket)))))))
       (lookup-spec-for-call class-internal method-name parameter-count))))
  ([class-internal method-name parameter-count]
   (let [b (.get (ix/get-by-target) class-internal)]
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
                 (recur (next bucket)))))))))))

(defn dispatch-redefine
  "ByteBuddy MethodDelegation helper. The original method body
   has been REPLACED by a static call to
   `GenericDispatcher.dispatch(hostInternal, methodName, args)`.
   We resolve the spec by class+name+arity and invoke the
   bridge fn.

   Bridge contract (unchanged from commit 1): receives positional
   args `[args method-name]`. Returns a value typed per the
   original method's declared return type, OR throws. The
   DynamicAssigner inserts the runtime CHECKCAST when the bridge
   returns Object and the target expects a narrower type."
  [host-internal method-name args]
  (try
    (let [param-count (count args)
          spec-id     (lookup-spec-for-call host-internal method-name param-count)]
      (if-let [spec (and spec-id (lookup spec-id))]
        (if-let [bridge-fn (du/safe-bridge spec)]
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
   three-argument shape `(hostInternal, methodName, args)`.
   Stores the resulting IFn in
   `nihilite.hooks.Bridge/REDISPATCHER` so
   `GenericDispatcher` can call it from rewritten bytecode.

   This sub-namespace does not `:require nihilite.hooks` to
   avoid a load-order cycle; the 0-arity form resolves the
   `Bridge/installRedefineDispatcher` symbol at call time.
   The 1-arity form accepts an explicit setter (for tests
   and alternate runtimes)."
  ([] (install-redefine-dispatcher!
        (clojure.lang.RT/var "nihilite.hooks.Bridge"
                             "installRedefineDispatcher")))
  ([setter]
   (let [dispatch-ifn
         (fn [host-internal method-name args]
           (dispatch-redefine host-internal method-name args))]
     (setter dispatch-ifn)
     :installed)))