(ns examples.fabric.init
  "Fabric runtime init for nihilite. Loaded by
   nihilite.agent.Agent#premain when
   -Dnihilite.init=examples/fabric/init.clj is set on the JVM
   command line.

   What this file does:
   1. Defines :fabric server accessors and message helpers
   2. Defines the per-runtime :fabric/adapter defrecord satisfying
      the 3 nihilite.adapter protocols with :event-factory strategy
   3. Pre-registers three HookSpec demos -- one per hook phase --
      on MinecraftServer methods:
        :entry    -> MinecraftServer.runServer (observe)
        :return   -> MinecraftServer.sendSystemMessage (mutate return)
        :redefine -> the dummy class nihilite.test.retransformDriver$DummyTarget
                     (full body substitution; demonstrates hot-rewriting)"
  (:require [nihilite.adapter :as a]
            [nihilite.registry :as reg])
  (:import [net.minecraft.server MinecraftServer]
           [net.minecraft.network.chat Component]))

(defn get-server-instance
  []
  (try
    (let [mc-c (Class/forName "net.minecraft.server.MinecraftServer"
                              false (ClassLoader/getSystemClassLoader))
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
      (walk mc-c))
    (catch Throwable _ nil)))

(defn send-system-message!
  [server msg]
  (if (nil? server)
    {:status :server-not-ready :message msg}
    (try
      (let [component (Component/literal msg)
            on-mc-thread? (.isSameThread server)
            delivered? (if on-mc-thread?
                         (do (.sendSystemMessage server component false) true)
                         (do (.execute server (fn [] (.sendSystemMessage server component false))) true))]
        {:status :ok :scheduled delivered? :message msg})
      (catch Throwable t
        {:status :errored :error (str t)}))))

(defn list-players [server]
  (vec (.getPlayers (.getPlayerList server))))

(defn schedule-on-target-thread! [server runnable]
  (.execute server ^Runnable (reify Runnable (run [_] (runnable)))))

(defrecord FabricAdapter []
  a/BootSentinel
  (wait-until-runtime-ready! [_]
    (loop [attempt 0]
      (let [found? (try
                     (Class/forName "net.minecraft.server.MinecraftServer"
                                    false (ClassLoader/getSystemClassLoader))
                     (catch Throwable _ nil))]
        (cond
          found? nil
          (> attempt 600)
          (throw (ex-info "MC never reached" {:attempt attempt}))
          :else (do (Thread/sleep 100) (recur (inc attempt)))))))
  a/TargetThreadDispatcher
  (schedule-on-target-thread! [_ runnable]
    (when-let [server (get-server-instance)]
      (.execute server ^Runnable (reify Runnable (run [_] (runnable))))))
  a/EventRegistryStrategy
  (strategy [_] :event-factory))

;; --- hook demos (one per phase) ---

(def fabric-on-run-server
  (fn [ctx] (println :fabric-run-server-entered)))

(def fabric-on-send-system-message
  (fn [ctx]
    (let [original (:returnValue ctx)
          replacement (str original " [fabric-tag]")]
      (println :fabric-return-overrode-from original :to replacement)
      replacement)))

(def fabric-redefine-dummy-target
  (fn [args _method-name]
    (println :fabric-redefined probe-args args)
    "REDIFINED-BY-FABRIC"))

(let [entry-var #'fabric-on-run-server
      return-var #'fabric-on-send-system-message
      redefine-fn #'fabric-redefine-dummy-target]
  (reg/install!
    {:id "fabric-run-server-entry"
     :target-internal "net/minecraft/server/MinecraftServer"
     :method-name "runServer"
     :position :entry
     :arity 0
     :descriptor "()V"
     :action :observe
     :bridge (fn [ctx] (let [f @entry-var] (f ctx)))
     :note "Fabric :entry demo on MinecraftServer.runServer"})

  (reg/install!
    {:id "fabric-send-system-message-return"
     :target-internal "net/minecraft/server/MinecraftServer"
     :method-name "sendSystemMessage"
     :position :return
     :arity 1
     :descriptor "(Lnet/minecraft/network/chat/Component;Z)V"
     :action :modify
     :bridge (fn [ctx] (let [f @return-var] (f ctx)))
     :note "Fabric :return demo on MinecraftServer.sendSystemMessage"})

  (reg/install!
    {:id "fabric-redefine-demo"
     :target-internal "nihilite/test/retransformDriver$DummyTarget"
     :method-name "probeRedef"
     :position :redefine
     :arity 0
     :descriptor "()Ljava/lang/String;"
     :bridge (fn [args method-name] (redefine-fn args method-name))
     :note "Fabric :redefine demo on a sentinel probeRedef method"}))

(a/install-default! :fabric/adapter (->FabricAdapter))

(println (str "[examples.fabric.init] loaded nihilite.adapter "
              "for :fabric/adapter. Get server via "
              "(examples.fabric.init/get-server-instance)."))
(flush)