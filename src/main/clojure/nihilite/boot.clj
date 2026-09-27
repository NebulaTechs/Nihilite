(ns nihilite.boot
  (:require [nihilite.version :as v]
            [nrepl.middleware]
            [nrepl.server :as nrepl.server])
  (:import [java.util.logging Logger Level]))

(defonce ^:private log
  (doto (Logger/getLogger "Nihilite.Boot")
    (.setLevel Level/WARNING)))

(defonce ^:private runtime-version v/version)

(def ^:private init-property-name "nihilite.init")

(def ^:private init-default-form "(require 'clojure.repl)")

(defonce ^:private runtime-server (atom nil))

(defn ensure-defaults!
  "Populate the nihilite.bind / nihilite.port / nihilite.init system
   properties with the canonical defaults if the caller has not set
   them. Reads only from System/getProperty so callers always supply
   configuration via -D on the java command line."
  []
  (when (nil? (System/getProperty "nihilite.port"))
    (System/setProperty "nihilite.port" "7888"))
  (when (nil? (System/getProperty "nihilite.bind"))
    (System/setProperty "nihilite.bind" "127.0.0.1")))

(defn middleware-stack
  "No-op middleware stack; replace with custom middlewares in user init if needed."
  [handler]
  handler)

(nrepl.middleware/set-descriptor!
 #'middleware-stack
 {:requires #{}
  :expects  #{"eval"}})

(defn start!
  "Start the nrepl server on the configured bind:port and return the server handle."
  []
  (let [bind (System/getProperty "nihilite.bind")
        port (Integer/parseInt (System/getProperty "nihilite.port"))
        handler (nrepl.server/default-handler (var middleware-stack))
        server (nrepl.server/start-server :port port :bind bind :handler handler)]
    (reset! runtime-server server)
    server))

(defn eval-init!
  "Read the system property `nihilite.init` as a Clojure form string and eval it in
   the current namespace. The default is (require 'clojure.repl) so the connected
   nREPL client has familiar REPL bindings."
  []
  (let [form (or (System/getProperty init-property-name) init-default-form)]
    (try
      (let [forms (read-string (str "[" form "]"))]
        (doseq [f forms]
          (clojure.lang.Compiler/eval f)))
      (catch Throwable t
        (.log ^Logger log Level/WARNING
              (str "[Nihilite] init failed: " (.getMessage t)) t)))))

(defn -main
  "Entry point invoked by java -jar nihilite.jar. Ensures bind / port
   system properties have defaults, starts the canonical nrepl server,
   runs the init form, then blocks the main thread forever so the JVM
   stays alive until killed. Configuration is read exclusively from
   System/getProperty so callers must supply -D on the java command
   line; --bind / --port CLI flags are not recognized."
  [& _args]
  (try
    (ensure-defaults!)
    (start!)
    (eval-init!)
    (println (str "[Nihilite] server " runtime-version " ready on "
                  (System/getProperty "nihilite.bind") ":"
                  (System/getProperty "nihilite.port")))
    (flush)
    @(.await (java.util.concurrent.CountDownLatch. 1))
    (catch InterruptedException _ nil)
    (catch Throwable t
      (.log ^Logger log Level/SEVERE (str "[Nihilite] FATAL: " (.getMessage t)) t)
      (System/exit 1))))
