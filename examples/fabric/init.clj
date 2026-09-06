(ns examples.fabric.init
  "Fabric runtime init for nihilite, loaded by nihilite.agent.Agent#premain via -Dnihilite.init."
  (:require [nihilite.api :as api])
  (:import [net.minecraft.network.chat Component]))

(defn- find-loaded-class
  ^Class [^String dot-name]
  (if-let [inst (nihilite.agent.Agent/currentInstrumentation)]
    (some (fn [^Class c]
            (when (and c (= dot-name (.getName c))) c))
          (.getAllLoadedClasses inst))
    (try
      (Class/forName dot-name false (ClassLoader/getSystemClassLoader))
      (catch Throwable _ nil))))

(defn get-server-instance
  []
  (try
    (let [mc-c (find-loaded-class "net.minecraft.server.MinecraftServer")
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

(def fabric-on-run-server
  (fn [_] (println :fabric-run-server-entered)))

(def fabric-on-send-system-message
  (fn [ctx]
    (let [original (:returnValue ctx)
          replacement (str original " [fabric-tag]")]
      (println :fabric-return-overrode-from original :to replacement)
      replacement)))

(def fabric-redefine-dummy-target
  (fn [args _method-name]
    (println :fabric-redefined args)
    "REDIFINED-BY-FABRIC"))

(let [entry-var #'fabric-on-run-server
      return-var #'fabric-on-send-system-message]
  (api/install!
    {:id "fabric-run-server-entry"
     :target-internal "net/minecraft/server/MinecraftServer"
     :method-name "runServer"
     :position :entry
     :arity 0
     :descriptor "()V"
     :action :observe
     :bridge (fn [ctx] (let [f @entry-var] (f ctx)))
     :note "Fabric :entry demo on MinecraftServer.runServer"})

  (api/install!
    {:id "fabric-send-system-message-return"
     :target-internal "net/minecraft/server/MinecraftServer"
     :method-name "sendSystemMessage"
     :position :return
     :arity 1
     :descriptor "(Lnet/minecraft/network/chat/Component;Z)V"
     :action :modify
     :bridge (fn [ctx] (let [f @return-var] (f ctx)))
     :note "Fabric :return demo on MinecraftServer.sendSystemMessage"}))

(println (str "[examples.fabric.init] loaded. Get server via "
               "(examples.fabric.init/get-server-instance).")
         "MinecraftServer class must be loaded before any MC hook will fire.")
(flush)
