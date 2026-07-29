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
  "Return relevant spec list: by-method bucket if method-key, else by-target (legacy)."
  [spec]
  (if-let [mk (:method-key spec)]
    (some-> (.get (ix/get-by-method) mk) seq)
    (some-> (.get (ix/get-by-target) (:target-internal spec)) seq)))

(defn lookup
  "Spec by id, or nil."
  [id]
  (.get (ix/get-by-id) (str id)))

(defn lookup-spec-for-call
  "ByteBuddy Advice helper. Returns first matching spec :id or nil. P0: by-method; legacy: by-target."
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
  "ByteBuddy MethodDelegation helper. Resolve spec by class+name+arity, invoke bridge fn."
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
  "Wrap dispatch-redefine in IFn, store in Bridge/REDISPATCHER. Avoids load-order cycle."
  ([] (install-redefine-dispatcher!
        (clojure.lang.RT/var "nihilite.hooks.Bridge"
                             "installRedefineDispatcher")))
  ([setter]
   (let [dispatch-ifn
         (fn [host-internal method-name args]
           (dispatch-redefine host-internal method-name args))]
     (setter dispatch-ifn)
     :installed)))