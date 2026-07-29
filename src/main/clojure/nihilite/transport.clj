(ns nihilite.transport
  "Single-port loopback ServerSocket with grammar-aware connection-level
   dispatch.

   Each accepted connection is sniffed without consuming its prefix
   (`nihilite.transport.sniff`). A connection whose first bytes match `d`
   followed by one-or-more ASCII decimal digits followed by `:` is routed
   to the native nREPL bencode branch (`nihilite.transport.bencode`);
   every other connection is routed to a plain UTF-8 raw Clojure branch
   (`nihilite.transport.raw`), OR (if it begins with an HTTP/1.1 method
   token + SP, or the HTTP/2.0 preface) to the HTTP/1.1 branch
   (`nihilite.transport.http`), which serves /healthz, /v1/eval, and the
   RFC 6455 WebSocket upgrade on /ws (`nihilite.transport.ws`).

   Lifecycle
     - `start!` binds one `ServerSocket` on `:port` (default 7888) and
       `:bind` (default `127.0.0.1`) with `SO_REUSEADDR`.
     - Accept loop runs on a short `SO_TIMEOUT` so `stop!` can wake it.
     - Each accepted socket is registered in an active-socket atom before
       dispatch and removed on worker exit.
     - A bounded `Executors/newFixedThreadPool` of size `:threads`
       (default 16) runs the per-connection handlers.
     - `stop!` closes the listener, every active socket, the pool, and
       clears the registry.

   Options
     :port    - int, default 7888
     :bind    - str, default `127.0.0.1`
     :threads - int, default 16

   Returns a `(fn stop! [])` no-arg stop handle. `stop!` is idempotent."
  (:require [clojure.tools.logging :as log]
            [nihilite.transport.sniff :as sniff]
            [nihilite.transport.bencode :as bencode]
            [nihilite.transport.raw :as raw]
            [nihilite.transport.http :as http])
  (:import [java.net ServerSocket Socket InetSocketAddress
                    SocketTimeoutException]
           [java.io BufferedInputStream]
           [java.util.concurrent Executors]))

(def ^:const ^:long accept-timeout-ms 200)
(def ^:const ^:long idle-timeout-ms  30000)

(defn- dispatch [^Socket sock active]
  (try
    (swap! active conj sock)
    (let [buf-in (BufferedInputStream. (.getInputStream sock) 4096)
          kind (sniff/sniff sock buf-in)]
      (case kind
        :bencode (bencode/handle-bencode sock buf-in)
        :http    (http/handle-http sock buf-in)
        :raw     (raw/handle-raw sock buf-in)))
    (catch Throwable t
      (log/error t "dispatch error"))
    (finally
      (try (swap! active disj sock) (catch Throwable _))
      (try (.close sock) (catch Throwable _)))))

(defn start!
  "Start single-port dispatcher. Returns (fn stop! []). Options: :port :bind :threads."
  ([] (start! {}))
  ([{:keys [port bind threads]
     :or   {port    7888
            bind    "127.0.0.1"
            threads 16}}]
   (let [server  (doto (ServerSocket.) (.setReuseAddress true))
         _       (.bind server (InetSocketAddress. ^String bind (int port)))
         _       (.setSoTimeout server (int accept-timeout-ms))
         pool    (Executors/newFixedThreadPool (int threads))
         active  (atom #{})
         running (atom true)]
     (log/info "bound on" bind ":" port
               " (single-port bencode+http+raw dispatcher, threads="
               threads ")")
     (future
       (try
         (while @running
           (let [sock (try
                        (.accept server)
                        (catch SocketTimeoutException _ nil)
                        (catch Throwable t
                          (when @running
                            (log/error t "accept error"))
                          nil))]
             (when sock
               (try
                 (.setSoTimeout sock (int idle-timeout-ms))
                 (.submit pool ^Runnable
                          (fn [] (dispatch sock active)))
                 (catch Throwable t
                   (log/error t "submit failed")
                   (try (.close sock) (catch Throwable _)))))))
         (catch Throwable t
           (when @running
             (log/error t "accept loop died")))
         (finally
           (log/info "accept loop exiting"))))
     (let [stop-once (atom false)]
       (fn stop! []
         (when (compare-and-set! stop-once false true)
           (reset! running false)
           (try (.close server) (catch Throwable _))
           (doseq [^Socket s @active]
             (try (.close s) (catch Throwable _)))
           (reset! active #{})
           (.shutdownNow pool)
           (log/info "stopped")))))))
