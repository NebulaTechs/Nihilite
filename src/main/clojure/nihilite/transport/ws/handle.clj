(ns nihilite.transport.ws.handle
  "WS per-conn entry: 101 Switching Protocols + dispatch loop."
  (:require [clojure.tools.logging :as log]
            [nihilite.transport.io :as io]
            [nihilite.transport.ws.handshake :as hs]
            [nihilite.transport.ws.dispatch :as dispatch])
  (:import [java.io ByteArrayOutputStream]))

(defn handle-ws
  "RFC 6455 WS branch. Caller drained req+headers; we write 101 and loop frames."
  [sock buf-in headers]
  (.setSoTimeout sock hs/ws-idle-timeout-ms)
  (let [out (.getOutputStream sock)
        client-key (get headers "sec-websocket-key")
        accept (hs/ws-accept-key (or client-key ""))
        resp (str "HTTP/1.1 101 Switching Protocols\r\n"
                  "Upgrade: websocket\r\n"
                  "Connection: Upgrade\r\n"
                  "Sec-WebSocket-Accept: " accept "\r\n"
                  "\r\n")]
    (.write out (.getBytes ^String resp "UTF-8"))
    (.flush out)
    (log/info "ws upgrade accepted from" (get headers "host"))
    (let [user-ns (or (find-ns 'user) (create-ns 'user))
          accum-buf (ByteArrayOutputStream.)
          accum-opcode (atom nil)
          repl-state (atom {:ns user-ns :*1 nil :*2 nil :*3 nil :*e nil})]
      (try
        (dispatch/ws-loop buf-in out user-ns repl-state accum-buf accum-opcode)
        (catch Throwable t
          (log/error t "ws connection error"))
        (finally
          (try (.close sock) (catch Throwable _)))))))
