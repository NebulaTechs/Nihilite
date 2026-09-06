(ns nihilite.boot
  (:require [nihilite.version :as v]
            [nrepl.middleware]
            [nrepl.server :as nrepl.server])
  (:import [java.util.logging Logger Level]))

(defonce ^:private log (Logger/getLogger "Nihilite.Boot"))

(defonce ^{:tag '[String Level]} silent-log
  (let [l (Logger/getLogger "Nihilite.Boot")]
    (.setLevel l Level/WARNING)
    l))

(defonce ^:private runtime-version v/version)

(def ^:private init-property-name "nihilite.init")

(def ^:private init-default-form "(require 'clojure.repl)")

(defonce ^:private runtime-server (atom nil))

(defonce ^:private positional-args (atom []))

(defn parse-args
  "Parse CLI args for port (integer) and bind (host)."
  [args]
  (let [positional (atom 0)
        port-prop "nihilite.port"
        bind-prop "nihilite.bind"
        port-arg-prefix "--port="
        bind-arg-prefix "--bind="]
    (when (nil? (System/getProperty port-prop))
      (System/setProperty port-prop "7888"))
    (when (nil? (System/getProperty bind-prop))
      (System/setProperty bind-prop "127.0.0.1"))
    (when (some? args)
      (doseq [^String a args]
        (when (and a (pos? (.length a)))
          (cond
            (.startsWith a port-arg-prefix)
            (System/setProperty port-prop (.substring a (count port-arg-prefix)))

            (.startsWith a bind-arg-prefix)
            (System/setProperty bind-prop (.substring a (count bind-arg-prefix)))

            (zero? @positional)
            (try
              (let [p (Integer/parseInt a)]
                (when (<= 1 p 65535)
                  (System/setProperty port-prop a)
                  (swap! positional inc)))
              (catch NumberFormatException _
                (System/setProperty bind-prop a)
                (swap! positional inc)))

            :else
            (reset! positional-args (conj @positional-args a))))))))

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
  "Entry point invoked by java -jar nihilite.jar. Parses args, starts the
   canonical nrepl server, runs the init form, then blocks the main thread
   forever so the JVM stays alive until killed."
  [& args]
  (try
    (parse-args args)
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
