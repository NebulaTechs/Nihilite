(ns nihilite.transport
  "Single-port loopback ServerSocket routing every connection to the nREPL bencode branch."
  (:require [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [nrepl.bencode :as bencode]
            [nrepl.core]   ; reply/lein :connect evals nrepl.core/version
            [nrepl.server :as nrserver]
            [nrepl.transport :as nrtransport])
  (:import [java.net ServerSocket Socket InetSocketAddress
                    SocketTimeoutException]
           [java.io BufferedInputStream OutputStream
                    PushbackInputStream]
           [java.util.concurrent Executors]))

(def ^:const ^:long accept-timeout-ms 200)
(def ^:const ^:long idle-timeout-ms  30000)

(def ^:const ^:long sniff-buffer-size 4096)

(defn- disconnect-ex?
  [^Throwable t]
  (or (instance? java.io.EOFException t)
      (instance? java.net.SocketException t)
      (instance? java.nio.channels.ClosedChannelException t)))

(defn- decode-message
  [msg]
  (let [unencoded (get msg "-unencoded")
        drop-keys (cond-> ["-unencoded"]
                    (seq unencoded) (into unencoded))
        without   (apply dissoc msg drop-keys)
        decoded   (reduce-kv
                    (fn [m k v]
                      (assoc m k (if (bytes? v)
                                   (String. ^bytes v "UTF-8")
                                   v)))
                    (empty without)
                    without)]
    (walk/keywordize-keys
      (merge decoded
             (when (seq unencoded)
               (select-keys msg unencoded))))))

(defn handle-bencode
  [^Socket sock ^BufferedInputStream buf-in
   & {:keys [middlewares]
      :or   {middlewares []}}]
  (.setSoTimeout sock 0)
  (let [pb-in     (PushbackInputStream. buf-in sniff-buffer-size)
        ^OutputStream out (.getOutputStream sock)]
    (try
      (let [transport (nrtransport/fn-transport
                        (fn [] (decode-message (bencode/read-nrepl-message pb-in)))
                        (fn [resp]
                          (locking out
                            (bencode/write-bencode out resp)
                            (.flush out)))
                        (fn [] (try (.close sock) (catch Throwable _))))
            handler (apply nrserver/default-handler middlewares)]
        (nrserver/handle handler transport))
      (catch Throwable t
        (when-not (disconnect-ex? t)
          (log/error t "bencode connection error")))
      (finally
        (try (.close sock) (catch Throwable _))))))

(defn- dispatch [^Socket sock active]
  (try
    (swap! active conj sock)
    (let [buf-in (BufferedInputStream. (.getInputStream sock) 4096)]
      (handle-bencode sock buf-in))
    (catch Throwable t
      (log/error t "dispatch error"))
    (finally
      (try (swap! active disj sock) (catch Throwable _))
      (try (.close sock) (catch Throwable _)))))

(defn start!
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
               " (single-port bencode nREPL dispatcher, threads="
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
                 (if (.isShutdown pool)
                   (try (.close sock) (catch Throwable _))
                   (.submit pool ^Runnable
                            (fn [] (dispatch sock active))))
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
