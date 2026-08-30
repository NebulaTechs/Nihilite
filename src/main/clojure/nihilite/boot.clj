(ns nihilite.boot
  "Server bootstrap. start! binds ONE loopback server."
  (:require [clojure.java.io :as jio]
            [clojure.tools.logging :as log]
            [nihilite.version :as v]
            [nihilite.transport :as transport])
  (:gen-class))

(defonce ^:private ready-fn (atom nil))

(defn set-ready!
  "Register a thunk the worker will invoke before binding nREPL.
   Init authors call (nihilite.boot/set-ready! (fn [] ...))."
  [f]
  (reset! ready-fn f)
  f)

(defn await-runtime-ready!
  "Worker entry. Invokes the registered thunk if any; no-op otherwise."
  []
  (when-let [f @ready-fn]
    (f)))

(defn- loopback? [^String host]
  (or (= host "127.0.0.1")
      (= host "::1")
      (= host "localhost")
      (nil? host)
      (= host "")))

(defn start!
  "Start canonical server. Returns {:server <stop-fn>}. Options: :port (7888), :bind."
  [& {:keys [port bind]
      :or {bind "127.0.0.1"
           port 7888}}]
  (log/info "nihilite version" v/version)
  (when-not (loopback? bind)
    (log/warn "bound on non-loopback host" bind
              "— REPL is unauthenticated, do NOT expose to a shared network"))
  (log/info "starting canonical server on" bind ":" port
            "(nREPL bencode, single socket)")
  (let [stop-fn (transport/start! {:port port :bind bind})]
    {:server stop-fn}))

(defn stop!
  "Stop a handle from start!. Accepts {:server stop-fn} or bare IFn. Idempotent."
  [server-or-handle]
  (let [stop-fn (cond
                  (map? server-or-handle)      (:server server-or-handle)
                  (ifn? server-or-handle)      server-or-handle
                  :else                       nil)]
    (when stop-fn
      (log/info "stopping canonical server")
      (try
        (stop-fn)
        (log/info "canonical server stopped")
        (catch Throwable t
          (log/error t "stop failed"))))))

(defn load-init!
  "If -Dnihilite.init points at readable file, load-file it.
   Returns path or nil. Failures swallowed."
  []
  (when-let [path (System/getProperty "nihilite.init")]
    (let [f (jio/file path)]
      (if (.isFile f)
        (try
          (log/info "loading init:" (.getAbsolutePath f))
          (load-file (.getAbsolutePath f))
          (log/info "init loaded:" (.getAbsolutePath f))
          (.getAbsolutePath f)
          (catch Throwable t
            (log/error t "init load failed")
            nil))
        (do (log/warn "init path is not a file:" (.getAbsolutePath f)) nil)))))

(defn -main [& _]
  (log/info "nihilite.boot -main invoked; this ns is a library, not an entry point")
  (log/info "use nihilite.server.ServerMain as the Main-Class"))