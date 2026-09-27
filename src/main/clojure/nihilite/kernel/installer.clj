(ns nihilite.kernel.installer
  "ByteBuddy AgentBuilder installation, implemented in Clojure.

   Mirrors the original nihilite.hooks.HookInstaller. One AgentBuilder
   instance is armed on the supplied Instrumentation, driven by a
   generated type-level matcher and a single composed transformer
   (both in nihilite.kernel.transformer). This file owns the AgentBuilder
   wiring and the install / uninstall / uninstall-spec! entry points that
   the registry and the agent worker call into."
  (:require [nihilite.kernel.advice :as advice]
            [nihilite.kernel.transformer :as transformer])
  (:import [java.lang.instrument Instrumentation]
            [java.util.logging Logger]))

(def ^:private installer-log (Logger/getLogger "nihilite.kernel.installer"))

(defn- log-info [& msgs] (.info installer-log (apply str msgs)))
(defn- log-warn [& msgs] (.warning installer-log (apply str msgs)))
(defn- log-error [t & msgs]
  (let [msg (apply str msgs)]
    (.log installer-log java.util.logging.Level/SEVERE msg t)))

(defn- base-builder []
  (let [retransformation (let [^net.bytebuddy.agent.builder.AgentBuilder$RedefinitionStrategy s net.bytebuddy.agent.builder.AgentBuilder$RedefinitionStrategy/RETRANSFORMATION]
                           s)
        reiterator (let [^net.bytebuddy.agent.builder.AgentBuilder$RedefinitionStrategy$DiscoveryStrategy$Reiterating d net.bytebuddy.agent.builder.AgentBuilder$RedefinitionStrategy$DiscoveryStrategy$Reiterating/INSTANCE]
                     d)
        listener (reify net.bytebuddy.agent.builder.AgentBuilder$Listener
                    (onDiscovery [_ _ _ _ _])
                    (onTransformation [_ _ _ _ _ _])
                    (onIgnored [_ _ _ _ _])
                    (onError [_ _ _ _ _ e]
                      (log-error e "AgentBuilder onError"))
                    (onComplete [_ _ _ _ _]))
        ^net.bytebuddy.agent.builder.AgentBuilder$Default base (net.bytebuddy.agent.builder.AgentBuilder$Default.)]
    (-> base
        ;; Without this, ByteBuddy is free to add members the transformer
        ;; introduces, and the JVM rejects the resulting retransform with
        ;; "class redefinition failed: attempted to add a method" — the
        ;; retransform contract forbids schema changes. Isolated repro
        ;; (no Nihilite code, same target class, same advice shape):
        ;;   with    disableClassFormatChanges -> retransform OK, advice fires
        ;;   without disableClassFormatChanges -> attempted to add a method
        (.disableClassFormatChanges)
        (.with retransformation)
        (.with reiterator)
        (.with listener))))

(defn- transformer-instance [class-name]
  (let [cls (Class/forName class-name)
        ctor (.getDeclaredConstructor cls (into-array Class []))]
    (.setAccessible ctor true)
    (.newInstance ctor (object-array []))))

(defn install
  "Arms the single AgentBuilder (redefine + advice composed) against the
   live JVM. Also publishes `inst` through
   nihilite.kernel.agent/agent-registerInstrumentation so the registry and
   the advice classes resolve the same Instrumentation regardless of
   whether arming came from premain, agentmain, or a direct caller such as
   a test driver.

   No-op when inst is nil (e.g. driver/test paths without
   instrumentation). A failure to arm propagates: swallowing it leaves the
   agent silently inert, which is far worse than a loud startup failure."
  [^Instrumentation inst]
  (if (nil? inst)
    (log-info "HookInstaller install skipped (no Instrumentation)")
    (do
      (let [register-fn (requiring-resolve
                          'nihilite.kernel.agent/agent-registerInstrumentation)]
        (register-fn inst))
      (advice/ensure-all! inst)
      (transformer/ensure-all!)
      (let [type-matcher (transformer-instance "nihilite.kernel.HookTypeMatcher")
            combined-xform (transformer-instance "nihilite.kernel.AdviceTransformer")]
        (.installOn
         (.transform
          (.type (base-builder) type-matcher)
          combined-xform)
         inst))
      (log-info "HookInstaller armed (byte-buddy AgentBuilder, RETRANSFORMATION, Reiterating)")
      nil)))

(defn uninstall
  "Retransforms all loaded classes whose name matches target-internal
   (slash-separated) so that ByteBuddy drops the instrumentation.
   Returns the number of classes actually retransformed.

   Same classloader boundary as retransform-loaded-matching!: only classes
   the app classloader loaded can drop the advice, because the advice is
   woven as an external reference to a system-loader class.

   A retransform failure propagates; it is not logged and swallowed."
  [^Instrumentation inst ^java.lang.String target-internal]
  (if (or (nil? inst) (nil? target-internal))
    0
    (let [dot-name (.replace target-internal "/" ".")
          candidates (filterv (fn [^java.lang.Class loaded]
                                (and loaded
                                     (= dot-name (.getName loaded))
                                     (.isModifiableClass inst loaded)))
                              (.getAllLoadedClasses inst))]
      (when (seq candidates)
        (.retransformClasses inst (into-array Class candidates))
        (log-info "HookInstaller uninstall: retransformed" dot-name))
      (count candidates))))

(defn uninstall-spec-with-target!
  "Variant of uninstall-spec! that accepts the target-internal directly,
   so registry/uninstall! can call it BEFORE removing the spec (the
   install path removes first, but uninstall needs the target-internal
   while the spec is still in the registry). Returns the count of
   classes retransformed; 0 when no Instrumentation is registered."
  [^String spec-id ^String target-internal]
  (let [inst-fn (requiring-resolve 'nihilite.kernel.agent/agent-currentInstrumentation)
        inst (when inst-fn (inst-fn))]
    (if inst
      (if target-internal
        (uninstall inst target-internal)
        (do (log-warn "HookInstaller uninstall-spec: spec id=" spec-id " has no target-internal")
            0))
      (do (log-warn "HookInstaller uninstall-spec: no Instrumentation for spec id=" spec-id)
          0))))
