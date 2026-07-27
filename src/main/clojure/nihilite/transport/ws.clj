(ns nihilite.transport.ws
  "RFC 6455 WebSocket branch. Handshake validation, frame IO (read/
   write/close), per-frame dispatch, and the read loop. Reachable via
   an HTTP Upgrade on /ws; `handle-ws` owns the connection thereafter."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [nihilite.transport.io :as io])
  (:import [java.io BufferedInputStream ByteArrayOutputStream OutputStream]
           [java.security MessageDigest]
           [java.util Base64]))

(def ^:const ^:long ws-max-frame-bytes 65536)
(def ^:const ^:long ws-max-accum-bytes 65536)
(def ^:const ^:long ws-idle-timeout-ms 30000)

(defn- ws-accept-key
  "Compute Sec-WebSocket-Accept from the client-supplied Key per
   RFC 6455 §4.2.2. SHA-1(Key + GUID), then base64-encode.
   GUID is the magic 258EAFA5-E914-47DA-95CA-C5AB0DC85B11."
  ^String [^String client-key]
  (let [combined (str client-key "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
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
  "Pre-check for an HTTP request that should be a WS upgrade.
   Returns nil if all required headers are RFC 6455-compliant; else
   {:status n :reason str}.

   Required (§4.1):
     - method=GET
     - path=/ws
     - Upgrade=websocket (case-insensitive)
     - Connection token contains 'upgrade' (case-insensitive)
     - Sec-WebSocket-Key base64 → exactly 16 bytes after decode
     - Sec-WebSocket-Version=13
     - Host header present"
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

(defn- read-exact-bytes
  "Read exactly n bytes into a fresh byte array. Returns the array
   on success, nil on EOF (short read)."
  [^BufferedInputStream buf-in ^long n]
  (let [buf (byte-array n)
        total (loop [off 0]
                (if (>= off n)
                  off
                  (let [r (.read buf-in buf off (- n off))]
                    (cond
                      (neg? r) off
                      (zero? r) (recur off)
                      :else (recur (+ off r))))))]
    (if (= total n) buf nil)))

(defn- ws-unmask [payload mask ext-len]
  (let [unmasked (byte-array ext-len)]
    (dotimes [i ext-len]
      (aset-byte unmasked i
                 (bit-xor (aget payload i)
                          (aget mask (mod i 4)))))
    unmasked))

(defn- read-ws-frame
  "Read one RFC 6455 frame from buf-in. Returns
   {:opcode n :fin? bool :payload byte-array} or nil on EOF.
   Returns {:ws-error code :reason str} for protocol violations."
  [^BufferedInputStream buf-in]
  (let [b0 (.read buf-in)]
    (when (>= b0 0)
      (let [b1 (.read buf-in)]
        (when (>= b1 0)
          (let [opcode (bit-and b0 0x0F)
                fin?   (not= 0 (bit-and b0 0x80))
                rsv    (bit-and b0 0x70)
                masked? (not= 0 (bit-and b1 0x80))
                len7 (bit-and b1 0x7F)]
            (cond
              (not= 0 (bit-and rsv 0x10))
              {:ws-error 1002 :reason "rsv1-set"}
              (not masked?) {:ws-error 1002 :reason "unmasked-client-frame"}
              (= len7 127) {:ws-error 1009 :reason "64-bit-length-not-supported"}
              :else
              (let [ext-len (if (= len7 126)
                              (let [bs (read-exact-bytes buf-in 2)]
                                (when bs
                                  (+ (bit-shift-left (aget bs 0) 8)
                                     (aget bs 1))))
                              len7)]
                (when (and ext-len (<= ext-len ws-max-frame-bytes))
                  (let [mask (read-exact-bytes buf-in 4)
                        payload (read-exact-bytes buf-in ext-len)]
                    (when (and mask payload)
                      {:opcode opcode :fin? fin? :payload (ws-unmask payload mask ext-len)})))))))))))

(defn- write-ws-frame
  "Write one RFC 6455 frame (server→client, unmasked per RFC 6455
   §5.1). Supports payload lengths <65536. Flushes after writing."
  [^OutputStream out ^long opcode ^bytes payload]
  (let [len (count payload)
        b1 (cond
             (< len 126) len
             (< len 65536) 126
             :else 127)
        head (byte-array 2)]
    (aset-byte head 0 (unchecked-byte (bit-or 0x80 opcode)))
    (aset-byte head 1 (unchecked-byte b1))
    (.write out head)
    (when (= b1 126)
      (let [ext (byte-array 2)]
        (aset-byte ext 0 (unchecked-byte (bit-shift-right len 8)))
        (aset-byte ext 1 (unchecked-byte (bit-and len 0xFF)))
        (.write out ext)))
    (.write out payload)
    (.flush out)))

(defn- write-ws-close
  "Send a CLOSE frame per RFC 6455 §5.5.2 / §7.4.1. Status code is
   the 2-byte big-endian reason code followed by an optional
   UTF-8 reason text. Close code 1000 = normal closure; 1002 =
   protocol error; 1009 = message too big; 1011 = server error."
  [^OutputStream out ^long code ^String reason]
  (let [code-bytes (byte-array 2)
        reason-bytes (if reason (.getBytes ^String reason "UTF-8") (byte-array 0))]
    (aset-byte code-bytes 0 (unchecked-byte (bit-shift-right code 8)))
    (aset-byte code-bytes 1 (unchecked-byte (bit-and code 0xFF)))
    (let [payload (byte-array (+ 2 (alength reason-bytes)))
          bos (ByteArrayOutputStream.)]
      (.write bos code-bytes)
      (.write bos reason-bytes)
      (write-ws-frame out 0x8 (.toByteArray bos)))))

(defn- ws-frame-handle-ping
  "RFC 6455 §5.5.3: respond with PONG carrying the same payload, but
   only if FIN=1 and payload ≤125 bytes. Otherwise close 1002."
  [^OutputStream out ^bytes payload fin?]
  (if (and fin? (<= (alength ^bytes payload) 125))
    (do (write-ws-frame out 0xA payload) :continue)
    (do (write-ws-close out 1002 "ping-rule-violation") :closed)))

(defn- ws-frame-handle-pong
  "PONG is ignored — never sent by server, but if client sends one
   anyway we just continue."
  [_out _payload _fin?]
  :continue)

(defn- ws-frame-handle-close
  "RFC 6455 §5.5.1: peer-initiated close; reply with 1000 + empty
   reason, then terminate."
  [^OutputStream out _payload _fin?]
  (write-ws-close out 1000 "")
  :closed)

(defn- ws-frame-handle-continuation-fin
  "Flush accumulated message: eval the reassembled text, send the
   response as a single WebSocket TEXT frame, reset accumulators."
  [^OutputStream out user-ns repl-state accum-buf accum-opcode
   opcode-before-continuation]
  (let [msg-bytes (.toByteArray accum-buf)
        _ (.reset accum-buf)
        saved-op opcode-before-continuation
        _ (reset! accum-opcode nil)
        text (String. ^bytes msg-bytes "UTF-8")
        response (io/safe-eval-line text user-ns repl-state)
        out-bytes (.getBytes ^String response "UTF-8")]
    (log/debug "ws FRAG-FIN opcode=" saved-op
          "size=" (alength msg-bytes))
    (write-ws-frame out (or saved-op 0x1) out-bytes)
    :continue))

(defn- ws-frame-handle-data
  "TEXT 0x1 / BINARY 0x2: accumulate payload.
   CONTINUATION 0x0 without FIN: append to accum.
   CONTINUATION 0x0 with FIN: flush eval.
   Protocol violations: close 1002 or 1009."
  [^OutputStream out user-ns repl-state accum-buf accum-opcode
   op ^bytes payload fin?]
  (let [first? (nil? @accum-opcode)]
    (cond
      ;; CONTINUATION without prior data frame
      (and (= op 0x0) first?)
      (do (write-ws-close out 1002 "continuation-without-start") :closed)

      ;; DATA frame after we've already started accumulating
      (and (or (= op 0x1) (= op 0x2)) (not first?))
      (do (write-ws-close out 1002 "data-without-fin") :closed)

      ;; Data frame: flush on FIN, otherwise hold for continuation.
      (or (= op 0x1) (= op 0x2))
      (do
        (.write accum-buf payload)
        (cond
          (> (.size accum-buf) ws-max-accum-bytes)
          (do (write-ws-close out 1009 "too-big") :closed)
          fin?
          (ws-frame-handle-continuation-fin
            out user-ns repl-state accum-buf accum-opcode op)
          :else
          (do (reset! accum-opcode op) :continue)))

      ;; CONTINUATION with FIN — flush message
      (and (= op 0x0) fin?)
      (ws-frame-handle-continuation-fin
        out user-ns repl-state accum-buf accum-opcode @accum-opcode)

      ;; CONTINUATION without FIN — append payload
      (= op 0x0)
      (do
        (.write accum-buf payload)
        (if (> (.size accum-buf) ws-max-accum-bytes)
          (do (write-ws-close out 1009 "too-big") :closed)
          :continue))

      :else
      (do (log/warn "ws unhandled opcode" op)
          (write-ws-close out 1002 "unhandled-opcode")
          :closed))))

(defn- ws-frame-dispatch
  "Per-frame dispatcher. Returns :continue, :closed, or :eof."
  [^OutputStream out user-ns repl-state accum-buf accum-opcode frame]
  (cond
    (nil? frame)
    (do (log/info "ws EOF, closing") :eof)

    (:ws-error frame)
    (let [{:keys [ws-error reason]} frame]
      (log/error "ws protocol-error" ws-error reason)
      (write-ws-close out (long ws-error) (or reason ""))
      :closed)

    :else
    (let [op (:opcode frame)
          payload (:payload frame)
          fin? (:fin? frame)]
      (case (long op)
        0x8 (ws-frame-handle-close out payload fin?)
        0x9 (ws-frame-handle-ping   out payload fin?)
        0xA (ws-frame-handle-pong   out payload fin?)
        ;; 0x0/0x1/0x2 all flow through the data handler
        (ws-frame-handle-data out user-ns repl-state accum-buf accum-opcode
                              op payload fin?)))))

(defn- ws-loop
  "Inner read-and-dispatch loop. Returns when :closed or :eof. The
   recur is at the very tail of the function body — no try, no
   cond, no nested let/if around it."
  [^BufferedInputStream buf-in ^OutputStream out user-ns repl-state
   accum-buf accum-opcode]
  (loop []
    (let [frame (read-ws-frame buf-in)
          action (ws-frame-dispatch out user-ns repl-state accum-buf accum-opcode frame)]
      (case action
        (:continue) (recur)
        nil))))

(defn handle-ws
  "RFC 6455 WebSocket branch. Caller (handle-http) has already
   drained the request line + headers and validated the upgrade.
   We write the 101 Switching Protocols response, then loop on
   frames."
  [sock buf-in headers]
  (.setSoTimeout sock ws-idle-timeout-ms)
  (let [out (.getOutputStream sock)
        client-key (get headers "sec-websocket-key")
        accept (ws-accept-key (or client-key ""))
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
        (ws-loop buf-in out user-ns repl-state accum-buf accum-opcode)
        (catch Throwable t
          (log/error t "ws connection error"))
        (finally
          (try (.close sock) (catch Throwable _)))))))
