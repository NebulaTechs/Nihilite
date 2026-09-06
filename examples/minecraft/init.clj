(ns examples.minecraft.init
  "Minecraft vanilla runtime init for nihilite, loaded via -Dnihilite.init=examples/minecraft/init.clj."
  (:require [nihilite.api :as api])
  (:import [net.minecraft.server MinecraftServer]
           [net.minecraft.network.chat Component]))

(defn- find-loaded-class
  ^Class [^String dot-name]
  (if-let [inst (nihilite.agent.Agent/currentInstrumentation)]
    (some (fn [^Class c]
            (when (and c (= dot-name (.getName c))) c))
          (.getAllLoadedClasses inst))
    (Class/forName dot-name)))

(defn get-server-instance
  []
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
    (walk mc-c)))

(defn send-system-message!
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
   [^MinecraftServer server]
   (vec (.getPlayers (.getPlayerList server))))

(defn schedule-on-target-thread!
  [^MinecraftServer server runnable]
  (.execute server ^Runnable
            (reify Runnable (run [_] (runnable)))))

(def minecraft-handler
  (fn [ctx] (println :default-system-message ctx)))

(let [handler-var #'minecraft-handler]
  (api/install!
    {:id "default-event"
     :target-internal "net/minecraft/server/MinecraftServer"
     :method-name "sendSystemMessage"
     :position :entry
     :arity 1
     :descriptor "(Lnet/minecraft/network/chat/Component;Z)V"
     :action :observe
     :bridge (fn [ctx]
               (let [f @handler-var]
                  (f ctx)))
     :note "Default MC sendSystemMessage event — bridge forwards to minecraft-handler; alter-var-root for live rewrite."}))

(println (str "[examples.minecraft.init] loaded. Get server via "
              "(examples.minecraft.init/get-server-instance).")
         "MinecraftServer class must be loaded before any MC hook will fire.")
(flush)
