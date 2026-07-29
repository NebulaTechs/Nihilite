(ns nihilite.transport.ws.handshake
  "RFC 6455 §4 — WebSocket Upgrade handshake. The HTTP caller has
   already routed GET /ws to this namespace. We validate the
   required headers and compute the Sec-WebSocket-Accept response.

   The handshake is purely synchronous and stateless: it does
   not own the socket. Frame IO and the read loop live in
   `nihilite.transport.ws.frames` and `nihilite.transport.ws.dispatch`."
  (:require [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.util Base64]))

(def ^:const ^:long ws-max-frame-bytes 65536)
(def ^:const ^:long ws-max-accum-bytes 65536)
(def ^:const ^:long ws-idle-timeout-ms 30000)

;; RFC 6455 §4.2.2 magic GUID concatenated with the client Key,
;; SHA-1 hashed, then base64-encoded.
(def ^:const ws-magic-guid "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")

(defn ws-accept-key
  "Compute Sec-WebSocket-Accept per RFC 6455 §4.2.2: SHA-1(Key+GUID), base64."
  ^String [^String client-key]
  (let [combined (str client-key ws-magic-guid)
        md (MessageDigest/getInstance "SHA-1")
        digest (.digest md (.getBytes ^String combined "UTF-8"))]
    (.encodeToString (Base64/getEncoder) digest)))

(defn- ws-check-base64-key-16
  "Return {:status n :reason str} if the key is invalid, else nil."
  [^String k]
  (if-not k
    {:status 400 :reason "missing-ws-key"}
    (let [decoded (try (.decode (Base64/getDecoder)
                               (.getBytes ^String k "UTF-8"))
                       (catch Throwable _ nil))]
      (cond
        (nil? decoded)        {:status 400 :reason "ws-key-not-base64"}
        (not= 16 (alength ^bytes decoded))
        {:status 400 :reason "ws-key-not-16-bytes"}
        :else nil))))

(defn ws-validation-error
  "Pre-check for WS upgrade. Returns nil if RFC 6455-compliant; else {:status n :reason str}."
  [method path headers]
  (cond
    (not= method "GET")
    {:status 405 :reason "method-not-get"}

    (not= path "/ws")
    {:status 404 :reason "unknown-ws-path"}

    (or (nil? (get headers "upgrade"))
        (not= "websocket" (clojure.string/lower-case (get headers "upgrade"))))
    {:status 400 :reason "missing-upgrade"}

    (or (nil? (get headers "connection"))
        (not (clojure.string/includes? (clojure.string/lower-case (get headers "connection"))
                            "upgrade")))
    {:status 400 :reason "missing-connection-upgrade"}

    (not (get headers "host"))
    {:status 400 :reason "missing-host"}

    (not= 13 (let [v (get headers "sec-websocket-version")]
               (try (Long/parseLong v) (catch Throwable _ -1))))
    {:status 426 :reason "ws-version-must-be-13"}

    :else
    (ws-check-base64-key-16 (get headers "sec-websocket-key"))))
