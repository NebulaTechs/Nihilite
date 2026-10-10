(ns nihilite.trainer.installer
  "ByteBuddy AgentBuilder installation, implemented in Clojure.

   Mirrors the original nihilite.hooks.HookInstaller. One AgentBuilder
   instance is armed on the supplied Instrumentation, driven by a
   generated type-level matcher and a single composed transformer
   (both in nihilite.trainer.transformer). This file owns the AgentBuilder
   wiring and the install / uninstall / uninstall-spec! entry points that
   the registry and the agent worker call into."
  (:require [nihilite.builder.registry :as reg]
            [nihilite.crafter.jul :as jul]
            [nihilite.trainer.advice :as advice]
            [nihilite.trainer.indy :as indy]
            [nihilite.trainer.transformer :as transformer])
  (:import [java.lang.instrument Instrumentation]))

(def ^:private installer-log (jul/logger "nihilite.trainer.installer"))


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
                      (jul/error installer-log e "AgentBuilder onError"))
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
        ;; The default ignore matcher drops every class the bootstrap loader
        ;; defined, which is where java.* lives — so without this a hook on a
        ;; JDK class registers, reports a woven count, and never fires. The
        ;; type matcher generated in this namespace is the only filter we
        ;; want, and it already rejects classes with no specs.
        (.ignore ^net.bytebuddy.matcher.ElementMatcher
                 (net.bytebuddy.matcher.ElementMatchers/none))
        (.with retransformation)
        (.with reiterator)
        (.with listener))))

(defn- transformer-instance [class-name]
  (let [cls (Class/forName class-name)
        ctor (.getDeclaredConstructor cls (into-array Class []))]
    (.setAccessible ctor true)
    (.newInstance ctor (object-array []))))

(def ^:private advice-classes
  "advice class name -> the advice method ByteBuddy will bind the call site to.
   The bootstrap method looks these up instead of resolving them, because a
   bootstrap must not trigger class loading: loading a class reads its bytes
   through java.io.FileInputStream, which is itself a hook target, so the
   nested link would recurse until the stack overflows."
  [["nihilite.trainer.HookAdvice" "onEntry"]
   ["nihilite.trainer.ReturnAdvice" "onExit"]
   ["nihilite.trainer.ThrowAdvice" "onThrow"]
   ["nihilite.trainer.RedefineAdvice" "onRedefine"]])

(defn- arm-indy!
  "Injects the bootstrap-side dispatcher, pushes the agent bridge into it, and
   resolves every advice method up front. Must run after advice/ensure-all!,
   since the advice classes have to exist before they can be preloaded."
  []
  (indy/install-bridge!)
  (doseq [[class-name method-name] advice-classes]
    (indy/preload-advice! class-name method-name))
  (jul/info installer-log "HookInstaller armed invokedynamic dispatch for"
            (count advice-classes) "advice classes")
  nil)

(defn- preload-registry-read-side!
  "The generated HookTypeMatcher answers \"does this class have a hook\" for
   every class the JVM loads, and it reaches the registry through
   `(resolve 'nihilite.builder.registry.index/revision)`.    `resolve` hands back an
   unbound var while that namespace is still evaluating, so a class loaded
   before the first install! completed made the matcher throw
   `IllegalStateException: Attempting to call unbound fn` out of
   HookTypeMatcher.matches -- the throw escapes the matcher's own try, and
   ByteBuddy's onError turns it into one log line and a silently unwoven
   class. Reproduced in a real `java -javaagent` JVM.

   Loading the read side here, before the AgentBuilder is armed, removes
   the half-loaded-registry state instead of papering over it at each use
   site. nihilite.builder.registry.index only imports java.util.concurrent, so this
   pulls in no registry mutation code and cannot cycle."
  []
  (require 'nihilite.builder.registry.index)
  nil)

(defn install
  "Arms the single AgentBuilder (redefine + advice composed) against the
   live JVM. Also publishes `inst` through
   nihilite.trainer.agent/agent-registerInstrumentation so the registry and
   the advice classes resolve the same Instrumentation regardless of
   whether arming came from premain, agentmain, or a direct caller such as
   a test driver.

   No-op when inst is nil (e.g. driver/test paths without
   instrumentation). A failure to arm propagates: swallowing it leaves the
   agent silently inert, which is far worse than a loud startup failure."
  [^Instrumentation inst]
  (if (nil? inst)
    (jul/info installer-log "HookInstaller install skipped (no Instrumentation)")
    (do
      (let [register-fn (requiring-resolve
                          'nihilite.trainer.agent/agent-registerInstrumentation)]
        (register-fn inst))
      (preload-registry-read-side!)
      (advice/ensure-all! inst)
      (arm-indy!)
      (transformer/ensure-all!)
      (let [type-matcher (transformer-instance "nihilite.trainer.HookTypeMatcher")
            combined-xform (transformer-instance "nihilite.trainer.AdviceTransformer")]
        (.installOn
         (.transform
          (.type (base-builder) type-matcher)
          combined-xform)
         inst))
      (jul/info installer-log "HookInstaller armed (byte-buddy AgentBuilder, RETRANSFORMATION, Reiterating)")
      nil)))

(defn uninstall
  "Retransforms all loaded classes whose name matches target-internal
   (slash-separated) so that ByteBuddy drops the instrumentation.
   Returns the number of classes actually retransformed.

   Every loader tier drops the advice the same way: retransforming from the
   class's original bytecode removes the woven call site, and the remaining
   hooks are re-woven by the same transform.

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
        (jul/info installer-log "HookInstaller uninstall: retransformed" dot-name))
      (count candidates))))

(defn uninstall-spec-with-target!
  "Variant of uninstall-spec! that accepts the target-internal directly,
   so registry/uninstall! can call it BEFORE removing the spec (the
   install path removes first, but uninstall needs the target-internal
   while the spec is still in the registry). Returns the count of
   classes retransformed; 0 when no Instrumentation is registered."
  [^String spec-id ^String target-internal]
  (let [inst-fn (requiring-resolve 'nihilite.trainer.agent/agent-currentInstrumentation)
        inst (when inst-fn (inst-fn))]
    (if inst
      (if target-internal
        (uninstall inst target-internal)
        (do (jul/warn installer-log "HookInstaller uninstall-spec: spec id=" spec-id " has no target-internal")
            0))
      (do (jul/warn installer-log "HookInstaller uninstall-spec: no Instrumentation for spec id=" spec-id)
          0))))

;; Publish the uninstall entry point upward. registry cannot require this
;; namespace, so the capability is handed over here at load time instead of
;; being looked up on every uninstall.
(reg/register-uninstaller! uninstall-spec-with-target!)
