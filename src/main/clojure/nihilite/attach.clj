(ns nihilite.attach
   "Attach API. Discovers agent jar via protection domain; renamed jars work transparently.
    agent-jar-path nils on directory/IDE load; attach-to! caller responsible for .detach."
  (:require [clojure.tools.logging :as log])
  (:import (com.sun.tools.attach VirtualMachine)
           (java.io File)
           (nihilite.agent Agent)))

(defn- agent-jar-path
  ^File []
  (let [pd (.getProtectionDomain (class Agent))
        cs (when pd (.getCodeSource pd))
        loc (when cs (.getLocation cs))]
    (cond
      (nil? loc)
      (do (log/warn "[Nihilite-attach] no code source for Agent class; "
                    "loadAgent unavailable")
          nil)

      (instance? File loc)
      (if (.isFile ^File loc) loc
          (do (log/warn "[Nihilite-attach] agent loaded from directory: " loc)
              nil))

      (instance? java.net.URL loc)
      (let [u ^java.net.URL loc]
        (if (= "file" (.getProtocol u))
          (File. (.getPath u))
          (do (log/warn "[Nihilite-attach] agent code source is non-file URL: " u)
              nil)))

      :else
      (do (log/warn "[Nihilite-attach] unexpected code source type: " (class loc))
          nil))))

(defn attach-to!
  ^VirtualMachine [pid]
  (let [jar (agent-jar-path)]
    (when (nil? jar)
      (throw (IllegalStateException.
              "[Nihilite-attach] cannot resolve agent jar path")))
    (log/info "[Nihilite-attach] attaching to pid=" pid
              " via agent jar=" (.getAbsolutePath ^File jar))
    (let [vm (.attach VirtualMachine (str pid))]
      (try
        (.loadAgent vm (.getAbsolutePath ^File jar))
        vm
        (catch Throwable t
          (.detach vm)
          (throw t))))))