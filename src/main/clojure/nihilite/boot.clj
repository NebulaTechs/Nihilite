(ns nihilite.boot
  "Server bootstrap. start! binds ONE loopback server on the
   canonical port (:port / :bind). The same socket accepts both
   native nREPL bencode clients (editor IDE plugins, cider/calva,
   lein, `clj -M:nrepl`, ...) and plain raw Clojure line clients
   (socat, nc, curl-style hand-rolled probes). Connection-level
   dispatch — bencode prefix sniff vs. plain UTF-8 lines — lives in
   `nihilite.transport`; this namespace is just the lifecycle
   wrapper.

   - start!     binds the one canonical listener.
   - stop!      closes that listener idempotently.
   - load-init! loads a single file from -Dnihilite.init (passed by path).

   The earlier multi-listener mode (`--transport`, `--no-transport`,
   `--transport-port=<n>`, `-Dnihilite.transport?`,
   `-Dnihilite.transport-port`, `-Dnihilite.http.port`) has been
   REMOVED. ServerMain no longer recognizes any of those flags; the
   dispatcher owns one socket, always.

   Anything more complex belongs in the init file: (require ...),
   (def ...), protocol extensions, hook wiring, etc. Nihilite itself
   does NOT auto-discover files."
  (:require [clojure.java.io :as jio]
            [clojure.tools.logging :as log]
            [nihilite.version :as v]
            [nihilite.transport :as transport])
  (:import (java.io File)
           (sun.misc Signal SignalHandler))
  (:gen-class))

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
  "Start the canonical server. Returns a single documented handle:

      {:server <stop-fn>}

   where <stop-fn> is the (idempotent) no-arg closure returned by
   `nihilite.transport/start!`. The shape is deliberately minimal —
   one entry, one stop fn — and replaces the prior two-server map
   (which routed nREPL through `nrepl.server/start-server` and an
   optional raw listener through a parallel branch).

   Options:
     :port          - canonical port (default 7888)
     :bind          - bind host (default \"127.0.0.1\")

   The JVM blocks on the latch started by `ServerMain`; this
   function itself does not block."
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
  "Stop a handle returned by `start!`. Accepts either:

     - the documented `{:server stop-fn}` map (preferred), or
     - a bare IFn (narrow back-compat for callers that already have
       the stop fn in hand.

   Stop is idempotent: calling twice is a no-op the second time
   (the transport-level stop fn guards itself)."
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
  "If -Dnihilite.init points at a readable file, load it via
   clojure.core/load-file. Returns the path that was loaded (or nil
   if no init was requested). Failures are logged and swallowed —
   a broken init must not abort the server."
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