(ns nihilite.transport.ws.frames
  "RFC 6455 §5 — WebSocket frame IO. Frame parsing, masking, and
   writing. Stateless helpers consumed by `nihilite.transport.ws.dispatch`
   for per-opcode handling and `nihilite.transport.ws.handle` for
   the connection-level read loop."
  (:require [clojure.tools.logging :as log])
  (:import [java.io BufferedInputStream ByteArrayOutputStream OutputStream])
  (:require [nihilite.transport.ws.handshake :as hs]))

(def ^:const ^:long ws-max-frame-bytes hs/ws-max-frame-bytes)
(def ^:const ^:long ws-max-accum-bytes hs/ws-max-accum-bytes)

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

(defn read-ws-frame
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

(defn write-ws-frame
  "Write one RFC 6455 frame (server→client, unmasked per RFC 6455
   §5.1). Supports payload lengths <65536. Flushes after writing.
   Public so the dispatch ns (PONG, TEXT reply) can use it."
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

(defn write-ws-close
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
