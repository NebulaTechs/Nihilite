(ns nihilite.attach
   "Attach API. Discovers agent jar via protection domain; renamed jars work transparently.
    agent-jar-path nils on directory/IDE load; attach-to! caller responsible for .detach.

    VirtualMachine (com.sun.tools.attach) is referenced only inside attach-to! so the
    rest of nihilite loads cleanly on minimal JVMs that lack tools.jar."
  (:require [clojure.tools.logging :as log])
  (:import (java.io File)
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
  [pid]
  (let [jar (agent-jar-path)]
    (when (nil? jar)
      (throw (IllegalStateException.
              "[Nihilite-attach] cannot resolve agent jar path")))
    (log/info "[Nihilite-attach] attaching to pid=" pid
              " via agent jar=" (.getAbsolutePath ^File jar))
    (let [VirtualMachine (Class/forName "com.sun.tools.attach.VirtualMachine")
          vm (.invoke (.getMethod VirtualMachine "attach" (into-array Class [(Class/forName "java.lang.String")]))
                      VirtualMachine
                      (object-array [(str pid)]))]
      (try
        (.invoke (.getMethod VirtualMachine "loadAgent" (into-array Class [(Class/forName "java.lang.String")]))
                 vm
                 (object-array [(.getAbsolutePath ^File jar)]))
        vm
        (catch Throwable t
          (.invoke (.getMethod VirtualMachine "detach" (into-array Class []))
                   vm
                   (object-array []))
          (throw t))))))