(ns nihilite.attach
  "Attach API wrapper. Discovers agent jar via protection domain
   (no hardcoded `nihilite.jar`) then `attach + loadAgent`. Renamed
   jars work transparently."
  (:require [clojure.tools.logging :as log])
  (:import (com.sun.tools.attach VirtualMachine)
           (java.io File)
           (nihilite.agent Agent)))

(defn- agent-jar-path
  "Resolve the agent jar path from `Agent.class`'s protection domain.
   Returns nil if the class was loaded from a directory (IDE/dev) or
   the protection domain has no code source. Logs a one-line
   diagnostic on each non-success path."
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
  "Attach this nihilite agent to the JVM at `pid`. Returns the
   `VirtualMachine` descriptor; caller is responsible for
   `.detach`ing. Throws if the agent jar path cannot be resolved."
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

(defn self-pid
  "Process id of the current JVM as a string. Used by contract tests
   that self-attach; not for production use."
  []
  (let [bean (java.lang.management.ManagementFactory/getRuntimeMXBean)
        name (.getName bean)
        idx  (.indexOf name "@")]
    (subs name 0 idx)))