;; Probe init script. Loaded through -Dnihilite.init, inside a real JVM that
;; Nihilite was attached to with -javaagent.
;;
;; Four things are measured, each printed as one PROBE line:
;;
;;   loader-class   what Compiler/LOADER actually is. worker.clj binds it to a
;;                  DynamicClassLoader, and this records what that resolves to.
;;   addurl         whether addURL works on it. add-libs is not in clojure.core,
;;                  but DynamicClassLoader extends URLClassLoader, so a user can
;;                  ship their own jars the same way core.async does.
;;   host-ns        whether a namespace that exists only on the added path can be
;;                  required -- i.e. whether init can reach code Nihilite knows
;;                  nothing about.
;;   service        a plain loopback echo service, and whether a client in
;;                  another process can reach it.
;;
;; Nothing here uses NREPL, a middleware, or anything from Nihilite's transport.
;; That is the point: if this works, a user who wants a control plane brings
;; their own, and Nihilite does not have to open a port.
;;
;; The host directory arrives as a system property rather than being interpolated
;; into this file, so this file stays readable Clojure instead of an escaped
;; string literal.

(ns probe.init-service
  "Probe init script, evaluated by the agent startup path via -Dnihilite.init."
  (:require [nihilite.api :as api]))

(def host-dir (System/getProperty "nihilite.probe.hostdir"))

(defn- describe [label x]
  (println "PROBE loader" label
           (try
             (str (.getName (class x))
                  (if (instance? java.net.URLClassLoader x) " url-classloader" ""))
             (catch Throwable t (str "throw " (.getMessage t))))))

;; worker.clj binds Compiler/LOADER to a DynamicClassLoader, but inside this
;; eval context the same symbol reads back as something else. Every candidate
;; accessor is printed, because which one a user can reach decides whether
;; they can ship their own code at all.
(describe "Compiler/LOADER" clojure.lang.Compiler/LOADER)
(describe "Compiler/LOADER-deref" (deref clojure.lang.Compiler/LOADER))
(describe "RT/baseLoader" (clojure.lang.RT/baseLoader))
(describe "thread-context" (.getContextClassLoader (Thread/currentThread)))
(describe "RT/makeClassLoader" (clojure.lang.RT/makeClassLoader))

(let [loader (deref clojure.lang.Compiler/LOADER)
      base   (clojure.lang.RT/baseLoader)
      dir-url (.toURL (.toURI (java.io.File. ^String host-dir)))]
  (println "PROBE addurl-compiler"
           (try
             (.addURL ^java.net.URLClassLoader loader dir-url)
             "ok"
             (catch Throwable t (str "fail " (.getMessage t)))))

  (println "PROBE addurl-base"
           (try
             (.addURL ^java.net.URLClassLoader base dir-url)
             "ok"
             (catch Throwable t (str "fail " (.getMessage t)))))

  (println "PROBE getresource"
           (try
             (str (.getResource ^java.net.URLClassLoader base "probe/hostapp/ns.clj"))
             (catch Throwable t (str "fail " (.getMessage t)))))

  (println "PROBE rt-baseLoader-is"
           (str (identical? (clojure.lang.RT/baseLoader) base)))

  (println "PROBE host-ns"
           (try
             (require 'probe.hostapp.ns)
             (str "ok " (pr-str (resolve 'probe.hostapp.ns/marker)))
             (catch Throwable t (str "fail " (.getMessage t)))))

  (println "PROBE install-hook"
           (try
             ((resolve 'probe.hostapp.ns/install-a-hook!))
             (str "ok " (pr-str (:fired (api/install-status! "probe-hook"))))
             (catch Throwable t (str "fail " (.getMessage t))))))

(let [ss (java.net.ServerSocket. 0 1 (java.net.InetAddress/getByName "127.0.0.1"))
      t  (Thread.
           ^Runnable
           (fn []
             (try
               (loop []
                 (let [sock (.accept ss)]
                   (try
                     (let [in (.getInputStream sock)
                           b  (.read in)]
                       (when (>= b 0)
                         (let [out (.getOutputStream sock)]
                           (.write out (unchecked-inc (int b)))
                           (.flush out))))
                     (catch Exception _ nil)
                     (finally (.close sock))))
                 (recur))
               (catch Exception _ nil)))
           "init-service-echo")]
  (.setDaemon t true)
  (.start t)
  (println "PROBE service-port" (.getLocalPort ss))
  (flush))

(println "PROBE init-done")
(flush)