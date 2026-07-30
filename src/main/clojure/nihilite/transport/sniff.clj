(ns nihilite.transport.sniff
  "Peek first bytes; classify as :bencode/:http/:raw. Returns :raw on
   timeout, or [kind prefix-bytes] so the bencode branch can unread
   onto a PushbackInputStream (nrepl.bencode hard-requires it)."
  (:import [java.net Socket SocketTimeoutException]
           [java.io BufferedInputStream]
           [java.util Arrays]))

(def ^:const ^:long sniff-bytes 16)
(def ^:const ^:long sniff-timeout-ms 2000)

(defn- ascii-digit-byte? [^long b]
  (and (>= b 0x30) (<= b 0x39)))

(defn- bencode-prefix?
  "True iff `prefix[0..n)` begins a non-empty bencode map: 'd' (0x64),
   then one-or-more ASCII decimal digits, then ':' (0x3A)."
  [^bytes prefix ^long n]
  (cond
    (< n 3) false
    (not (== (aget prefix 0) (byte 0x64))) false
    :else
    (loop [i 1]
      (cond
        (>= i n) false
        (ascii-digit-byte? (aget prefix i)) (recur (inc i))
        (== (aget prefix i) (byte 0x3A)) (>= i 2)
        :else false))))

(def ^:private http-method-prefixes
  [[(byte-array [(byte 0x44) (byte 0x45) (byte 0x4C)
                 (byte 0x45) (byte 0x54) (byte 0x45) (byte 0x20)]) 7] ; "DELETE "
   [(byte-array [(byte 0x4F) (byte 0x50) (byte 0x54)
                 (byte 0x49) (byte 0x4F) (byte 0x4E) (byte 0x53) (byte 0x20)]) 8] ; "OPTIONS "
   [(byte-array [(byte 0x47) (byte 0x45) (byte 0x54) (byte 0x20)]) 4] ; "GET "
   [(byte-array [(byte 0x48) (byte 0x45) (byte 0x41) (byte 0x44) (byte 0x20)]) 5] ; "HEAD "
   [(byte-array [(byte 0x50) (byte 0x55) (byte 0x54) (byte 0x20)]) 4] ; "PUT "
   [(byte-array [(byte 0x50) (byte 0x4F) (byte 0x53) (byte 0x54) (byte 0x20)]) 5] ; "POST "
   [(byte-array [(byte 0x50) (byte 0x41) (byte 0x54) (byte 0x43)
                 (byte 0x48) (byte 0x20)]) 6]])                            ; "PATCH "

(defn- http-method-prefix?
  "True iff `prefix[0..n)` starts with an HTTP/1.1 method token + SP,
   or the HTTP/2.0 preface 'PRI * HTTP/2.0'."
  [^bytes prefix ^long n]
  (boolean
    (or (some (fn [[bytes len]]
                (and (>= n len)
                     (java.util.Arrays/equals
                       (Arrays/copyOfRange prefix 0 len)
                       bytes)))
              http-method-prefixes)
        (and (>= n 4)
             (= (aget prefix 0) (byte 0x50))
             (= (aget prefix 1) (byte 0x52))
             (= (aget prefix 2) (byte 0x49))
             (= (aget prefix 3) (byte 0x20))))))

(defn sniff
  "Read up to sniff-bytes; classify. bencode/http branches return
   the bytes read so the caller can unread them onto the downstream
   PushbackInputStream. :raw resets the BufferedInputStream."
  [^Socket sock ^BufferedInputStream in]
  (.setSoTimeout sock (int sniff-timeout-ms))
  (let [buf (byte-array sniff-bytes)
        n (try (.read in buf 0 (int sniff-bytes))
               (catch SocketTimeoutException _ -1)
               (catch Throwable _ -1))]
    (cond
      (neg? (int n)) :raw
      (bencode-prefix? buf (long n)) [:bencode (java.util.Arrays/copyOf buf (int n))]
      (http-method-prefix? buf (long n))
      (do (.reset in) [:http (java.util.Arrays/copyOf buf (int n))])
      :else (do (.reset in) :raw))))
