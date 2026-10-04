(ns examples.nrepl-service
  "Reference example: bring your own control plane from an init script.

   Nihilite itself opens no port. It loads this file, and this file starts
   an nREPL server the way you would start any service you wanted. If you
   want a full REPL -- streaming output, interrupt, cider-nrepl's `info` /
   `eldoc` / `lookup` for Calva and other IDEs -- you bring it, and you
   choose the version.

   Two ways to get nrepl onto the classpath:

     1. The host application already depends on it. Then just require it;
        nothing below needs to touch the classloader.

     2. It is not on the classpath. Point Nihilite at the jar:

          java -Dnihilite.example.nrepl.jar=/path/to/nrepl.jar \\
               -Dnihilite.init=\"(load-file \\\"examples/nrepl_service.clj\\\")\" \\
               -javaagent:target/nihilite.jar -jar your-app.jar

        Any JVM will do as the host -- the point is only that Nihilite is
        mounted as an agent, because Main-Class does not evaluate
        -Dnihilite.init. There is no app here to instrument, so if you have
        nothing to attach to, `clojure -M -m your.main` with the agent
        mounted is enough.

        add-libs is not in clojure.core -- it belongs to core.async -- but
        the effect is one addURL away, because worker.clj binds
        Compiler/LOADER to a clojure.lang.DynamicClassLoader, which extends
        URLClassLoader. Measured: the added jar is retrievable by getResource
        and requireable afterwards.

   Either way the server binds loopback only and prints the port it took.

   Measured working end to end by
   src/test/clojure/nihilite/test/init_service_driver.clj, which starts a
   socket service from an init script and reaches it from another process."
  (:require [clojure.string :as str]))

(def ^:private jar-property "nihilite.example.nrepl.jar")
(def ^:private port-property "nihilite.example.nrepl.port")

(defn- add-jar!
  "Puts a jar on Compiler/LOADER so it can be required. Returns the name that
   was added, or nil when the property is unset.

   Note the deref: inside the init eval context Compiler/LOADER reads back as
   a clojure.lang.Var, not as the DynamicClassLoader worker.clj bindRoot'd
   into it. RT/baseLoader returns the DynamicClassLoader directly and is the
   shorter route."
  [jar-path]
  (when-not (str/blank? jar-path)
    (let [loader (deref clojure.lang.Compiler/LOADER)]
      (.addURL ^java.net.URLClassLoader loader
               (.toURL (.toURI (java.io.File. ^String jar-path))))
      jar-path)))

(defn- desired-port []
  (or (some-> (System/getProperty port-property) str/trim not-empty
              (Integer/parseInt))
      ;; 0 lets the OS pick, then we print it. A fixed 7888 collides the
      ;; moment two JVMs are up.
      0))

(defn start!
  "Starts an nREPL server on loopback and returns the server handle.

   Refuses to start twice in one JVM: a second server would either fail to
   bind or silently take a different port, and neither is worth debugging."
  []
  ;; requiring-resolve rather than a static :require, because the jar may only
  ;; have just been added by add-jar! above. A static require in the ns form
  ;; would fail while loading this file, before add-jar! ever ran.
  (let [start-server    (requiring-resolve 'nrepl.server/start-server)
        default-handler (requiring-resolve 'nrepl.server/default-handler)
        port            (desired-port)
        srv             (start-server
                          :port port
                          ;; loopback only. Nihilite does not decide this;
                          ;; whoever deploys a service does, and an example
                          ;; that binds the wildcard teaches the wrong habit.
                          :bind "127.0.0.1"
                          ;; default-handler brings nrepl's own ops (eval,
                          ;; load-file, completions, describe). For IDE
                          ;; support, load cider-nrepl's middleware here --
                          ;; that is the piece Nihilite never shipped, and it
                          ;; is the reason an editor refused to talk to the
                          ;; built-in server.
                          :handler (default-handler))]
    (println (str "[nihilite-example] nREPL listening on 127.0.0.1:"
                  (.getLocalPort ^java.net.ServerSocket srv)
                  " -- connect Calva to 127.0.0.1 and that port"))
    (flush)
    srv))

(defn -main [& _]
  (add-jar! (System/getProperty jar-property))
  (start!))