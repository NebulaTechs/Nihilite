(ns nihilite.boot
  "Server bootstrap. start! binds ONE loopback server. Lifecycle wrapper around transport."
  (:require [clojure.java.io :as jio]
            [clojure.tools.logging :as log]
            [nihilite.version :as v]
            [nihilite.transport :as transport])
  (:import (java.io File)
           (sun.misc Signal SignalHandler))
  (:gen-class))

;; Anything more complex belongs in the init file: (require ...), (def ...), hook wiring.

(defn- ignore-signal!
  "Install a no-op handler for the named POSIX signal so a `^C` typed
   in the controlling terminal (which is forwarded to every JVM in
   the foreground process group — including us, even though we are
   listening on a TCP socket) does NOT terminate the server.

   The user's terminal still gets its own SIGINT (so `nc` exits
   cleanly), but the JVM survives and accepts new connections.

   Idempotent and safe to call multiple times: Signal.handle
   replaces any prior handler, including the JVM default. We do
   NOT touch SIGTERM (so `kill <pid>` from a shell still shuts
   the server down via System.exit + shutdown-hook)."
  [^String sig-name]
  (try
    (let [sig (Signal. sig-name)
          prev (Signal/handle sig
                   (reify SignalHandler
                     (handle [_ signal]
                       (log/info sig-name "ignored (server alive)"))))]
      (when prev
        (log/debug sig-name "previously installed:" prev)))
    (catch Throwable t
      (log/warn "signal install failed (non-fatal):" (.getMessage t)))))

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
  (log/info (v/banner))
  (ignore-signal! "INT")
  (when-not (loopback? bind)
    (log/warn "bound on non-loopback host" bind
              "— REPL is unauthenticated, do NOT expose to a shared network"))
  (log/info "starting canonical server on" bind ":" port
            "(bencode + plain raw, single socket)")
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
  "If -Dnihilite.init points at readable file, load-file it. Returns path or nil. Failures swallowed."
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