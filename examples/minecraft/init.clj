(ns examples.minecraft.init
  "Minecraft runtime init for nihilite. Loaded by
   nihilite.agent.Agent#premain when
   -Dnihilite.init=examples/minecraft/init.clj is set on the JVM
   command line. This file is the ONLY place in the runtime that
   imports net.minecraft.* classes.

   What this file does:
   1. Defines :minecraft/vanilla server accessors and message helpers
   2. Defines the per-runtime :minecraft/vanilla adapter (a
      defrecord satisfying the 3 nihilite.adapter protocols)
   3. Pre-registers a :default-event HookSpec for
      MinecraftServer.sendSystemMessage with a delegating IFn
      that forwards to minecraft.handlers/on-system-message on
      every dispatch (live-rewrite via alter-var-root)
   4. Calls (nihilite.adapter/install-default! :minecraft/vanilla
      record) so the agent's worker can call
      wait-until-runtime-ready! without any MC-specific code
   5. Prints a banner so smoke captures can verify it loaded

   After this init file loads (synchronously, before addTransformer),
   the user can from nREPL evaluate:

     (examples.minecraft.init/send-system-message!
       (examples.minecraft.init/get-server-instance)
       \"hi\")

    and the message lands in MC's logs/latest.log. The forward
    path uses no bytecode hook — :default-event is for
    observability / interception only."
  (:require [nihilite.adapter :as a]
            [nihilite.registry :as reg])
  (:import [net.minecraft.server MinecraftServer]
           [net.minecraft.network.chat Component]))

;; ---------------------------------------------------------------------------
;; Per-runtime server accessors and message helpers for :minecraft/vanilla.
;; ---------------------------------------------------------------------------

(defn get-server-instance
  ;; Reflect for the running vanilla-MC singleton. Mojang 26.1
  ;; stores the singleton in a static field of a subclass
  ;; (e.g. DedicatedServer), NOT on MinecraftServer itself, and
  ;; the field name varies across versions. We walk the class
  ;; hierarchy and search for any static field whose value
  ;; instanceof MinecraftServer. Returns nil if MC's main thread
  ;; has not yet initialized the singleton.
  []
  (let [mc-c (Class/forName "net.minecraft.server.MinecraftServer")
        find-on-class (fn find-on-class [c]
                        (some (fn [field]
                                (let [mods (.getModifiers field)]
                                  (when (java.lang.reflect.Modifier/isStatic mods)
                                    (.setAccessible field true)
                                    (try (let [v (.get field nil)]
                                           (when (instance? mc-c v) v))
                                         (catch Throwable _ nil)))))
                              (.getDeclaredFields c)))
        walk (fn walk [c]
               (or (find-on-class c)
                   (when-let [parent (.getSuperclass c)]
                     (walk parent))))]
    (walk mc-c)))

(defn send-system-message!
  ;; Three distinguishable outcomes: :server-not-ready (no NPE),
  ;; :errored (reflection or thread threw), :ok (live delivery).
  ;; A pre-maturity nil server means :server-not-ready; a real
  ;; throw falls to :errored with throwable + stack.
  [^MinecraftServer server ^String msg]
  (if (nil? server)
    {:scheduled false
     :status   :server-not-ready
     :message  msg}
    (try
      (let [component (Component/literal msg)
            on-mc-thread? (.isSameThread server)
            delivered? (if on-mc-thread?
                         (do (.sendSystemMessage server component false) true)
                         (do (.execute server (fn [] (.sendSystemMessage server component false))) true))]
        {:scheduled delivered?
         :status   :ok
         :thread   (if on-mc-thread? "main" "nrepl")
         :message  msg})
      (catch Throwable t
        {:scheduled false
         :status   :errored
         :throwable-class (.getName (class t))
         :message  (.getMessage t)
         :error    (str t)}))))

(defn list-players
  ;; Return a vector of currently connected ServerPlayer instances.
  [^MinecraftServer server]
  (vec (.getPlayers (.getPlayerList server))))

(defn schedule-on-target-thread!
  ;; Submit a no-arg thunk to MC's main thread (.execute on the
  ;; MinecraftServer instance). Non-blocking from the caller's side.
  [^MinecraftServer server runnable]
  (.execute server ^Runnable
            (reify Runnable (run [_] (runnable)))))

;; ---------------------------------------------------------------------------
;; Per-runtime adapter.
;;
;; The agent's worker calls (nihilite.adapter/default-adapter) and
;; (wait-until-runtime-ready! adapter) — no MC-specific code on
;; the Java side. Future runtimes (Spigot, Paper, Fabric, generic
;; JRE) install their own adapter via install-default!.
;; ---------------------------------------------------------------------------

(defrecord MinecraftVanillaAdapter []
  a/BootSentinel
  (wait-until-runtime-ready! [_]
    (loop [attempt 0]
      (let [found? (try
                     (Class/forName "net.minecraft.server.MinecraftServer"
                                    false (ClassLoader/getSystemClassLoader))
                     (catch Throwable _ nil))]
        (cond
          found?
          nil
          (> attempt 600)
          (throw (ex-info "MinecraftServer class never reached after 60s"
                          {:nihilite/kind :nihilite/timeout
                           :attempt attempt}))
          :else
          (do (Thread/sleep 100)
              (recur (inc attempt)))))))
  a/TargetThreadDispatcher
  (schedule-on-target-thread! [_ runnable]
    (when-let [server (get-server-instance)]
      (.execute server ^Runnable
                (reify Runnable (run [_] (runnable))))))
  a/EventRegistryStrategy
  (strategy [_] :direct-transformer))

;; ---------------------------------------------------------------------------
;; Pre-registered :default-event HookSpec.
;;
;; The bridge fn reads minecraft.handlers/on-system-message on every
;; dispatch. The user can (alter-var-root #'minecraft.handlers/on-system-message
;; ...) to swap implementations live — the next MC event will go
;; through the new body. Live-rewrite plumbed through the
;; per-runtime init file.
;; ---------------------------------------------------------------------------

(def minecraft-handler
  ;; Default handler for MinecraftServer.sendSystemMessage. The
  ;; pre-registered :default-event HookSpec's bridge fn forwards
  ;; to this var on every dispatch. Override via
  ;; (alter-var-root #'minecraft-handler ...) to swap implementations
  ;; live — the next MC event sees the new body.
  (fn [ctx] (println :default-system-message ctx)))

(let [handler-var #'minecraft-handler]
  (reg/install!
    {:id "default-event"
     :target-internal "net/minecraft/server/MinecraftServer"
     :method-name "sendSystemMessage"
     :position :entry
     :arity 1
     :bridge (fn [ctx]
               (let [f @handler-var]
                 (f ctx)))
     :note "Default MC sendSystemMessage event — bridge forwards to
            minecraft-handler; alter-var-root for live rewrite."}))

;; ---------------------------------------------------------------------------
;; Install the adapter as the default; print the banner.
;; ---------------------------------------------------------------------------

(a/install-default! :minecraft/vanilla (->MinecraftVanillaAdapter))

(println (str "[examples.minecraft.init] loaded nihilite.adapter "
              "for :minecraft/vanilla. Get server via "
              "(examples.minecraft.init/get-server-instance)."))
(flush)
