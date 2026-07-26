(ns nihilite.version
  "Version label for the Nihilite server. Reads Implementation-Version
   directly from the running jar's manifest when packaged, with sane
   fallbacks for unpackaged runs."
  (:require [clojure.java.io :as jio]
            [clojure.tools.logging :as log])
  (:import (java.util.jar JarFile)))

(defn- read-jar-version
  "Open the running jar via its ProtectionDomain code source location
   and read Implementation-Version from the manifest directly."
  []
  (try
    (let [clazz (Class/forName "nihilite.server.ServerMain")
          pd    (.getProtectionDomain clazz)
          cs    (when pd (.getCodeSource pd))
          loc   (when cs (.getLocation cs))
          f     (when loc (jio/file loc))]
      (when (and f (.isFile f))
        (with-open [jf (JarFile. f)]
          (when-let [m (.getManifest jf)]
            (when-let [attrs (.getMainAttributes m)]
              (.getValue attrs "Implementation-Version"))))))
    (catch Throwable t
      (log/warn t "read-jar-version failed")
      nil)))

(def version
  (or (System/getProperty "nihilite.runtime.version")
      (read-jar-version)
      "j25-1.0"))

(defn banner []
  (str "Nihilite server " version " — Clojure " (clojure-version)))