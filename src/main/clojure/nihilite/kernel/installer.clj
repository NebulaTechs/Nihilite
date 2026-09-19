(ns nihilite.kernel.installer
  "ByteBuddy AgentBuilder installation, implemented in Clojure.

   Mirrors the original nihilite.hooks.HookInstaller. One AgentBuilder
   instance is armed on the supplied Instrumentation, driven by a
   generated type-level matcher and a single composed transformer
   (both in nihilite.kernel.transformer). This file owns the AgentBuilder
   wiring and the install / uninstall / uninstall-spec! entry points that
   the registry and the agent worker call into."
  (:require [clojure.tools.logging :as log]
            [nihilite.kernel.advice :as advice])
  (:import [net.bytebuddy.matcher ElementMatchers]
           [java.lang.instrument Instrumentation]))

(def default-ignored-name-prefixes
  ["java." "javax." "jdk." "sun." "com.sun."
   "clojure." "nrepl."
   "nihilite.api." "nihilite.boot."
   "nihilite.kernel." "nihilite.transport." "nihilite.registry."])

(defn- ignored-types
  []
  (reduce (fn [m ^String n] (.or m (ElementMatchers/nameStartsWith n)))
          (ElementMatchers/none)
          default-ignored-name-prefixes))

(defn- dump-woven-class [type-desc ^bytes bytes]
  (when (and type-desc bytes
             (.contains ^String (.getName ^net.bytebuddy.description.type.TypeDescription type-desc) "DummyTarget"))
    (let [f (java.io.File. "target/nihilite/test/retransform_driver/DummyTarget_bytebuddy-output.class")]
      (when-not (.exists (.getParentFile f))
        (.mkdirs (.getParentFile f)))
      (with-open [out (java.io.FileOutputStream. f)]
        (.write out bytes))
      (.println System/err (str "DEBUG bytebuddy output for " (.getName ^net.bytebuddy.description.type.TypeDescription type-desc) " size=" (alength bytes))))))

(defn- base-builder []
  (let [retransformation (let [^net.bytebuddy.agent.builder.AgentBuilder$RedefinitionStrategy s net.bytebuddy.agent.builder.AgentBuilder$RedefinitionStrategy/RETRANSFORMATION]
                           s)
        reiterator (let [^net.bytebuddy.agent.builder.AgentBuilder$RedefinitionStrategy$DiscoveryStrategy$Reiterating d net.bytebuddy.agent.builder.AgentBuilder$RedefinitionStrategy$DiscoveryStrategy$Reiterating/INSTANCE]
                     d)
        listener (reify net.bytebuddy.agent.builder.AgentBuilder$Listener
                   (onDiscovery [_ _ _ _ _])
                   (onTransformation [_ type-desc _ _ _ dt]
                     (try
                       (dump-woven-class type-desc (.getBytes ^net.bytebuddy.dynamic.DynamicType dt))
                       (catch Throwable t
                         (.println System/err (str "DEBUG listener dump failed: " (.getMessage t))))))
                   (onIgnored [_ _ _ _ _])
                   (onError [_ _ _ _ _ e]
                     (.println System/err (str "DEBUG AgentBuilder onError " e)))
                   (onComplete [_ _ _ _ _]))
        ^net.bytebuddy.agent.builder.AgentBuilder$Default base (net.bytebuddy.agent.builder.AgentBuilder$Default.)]
    (-> base
        (.with retransformation)
        (.with reiterator)
        (.with listener)
        (.ignore (ignored-types))
        (.ignore (ElementMatchers/isSynthetic)))))

(defn- transformer-instance [class-name]
  (let [cls (Class/forName class-name)
        ctor (.getDeclaredConstructor cls (into-array Class []))]
    (.setAccessible ctor true)
    (.newInstance ctor (object-array []))))

(defn install
  "Arms the single AgentBuilder (redefine + advice composed) against the
   live JVM. No-op when inst is nil (e.g. driver/test paths without
   instrumentation)."
  [^Instrumentation inst]
  (if (nil? inst)
    (log/info "HookInstaller install skipped (no Instrumentation)")
    (try
      (advice/ensure-all!)
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
  "Used by registry/uninstall! to retransform the spec's target class.
   Looks up the spec's :target-internal from the registry (the spec id is
   NOT the class name) and retransforms that class. Returns the number
   of classes retransformed; 0 when no Instrumentation is registered."
  [^String spec-id]
  (let [inst-fn (requiring-resolve 'nihilite.kernel.agent/agent-currentInstrumentation)
        inst (when inst-fn (inst-fn))]
    (if inst
      (let [lookup-fn (requiring-resolve 'nihilite.registry/lookup)
            target (some-> (lookup-fn spec-id)
                           :target-internal
                           str)]
        (if target
          (uninstall inst target)
          (do (log/warn "HookInstaller uninstall-spec: spec id=" spec-id " not found in registry")
              0)))
      (do (log/warn "HookInstaller uninstall-spec: no Instrumentation for spec id=" spec-id)
          0))))
