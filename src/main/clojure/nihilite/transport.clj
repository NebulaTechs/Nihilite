(ns nihilite.transport
  "Single-port loopback ServerSocket with grammar-aware connection-level
   dispatch.

   Each accepted connection is sniffed without consuming its prefix. A
   connection whose first bytes match `d` followed by one-or-more ASCII
   decimal digits followed by `:` is routed to the native nREPL bencode
   branch; every other connection (including `de`, empty, or stalled
   input) is routed to a plain UTF-8 raw Clojure line branch, OR (if it
   begins with an HTTP/1.1 method token + SP, or the HTTP/2.0 preface)
   to the HTTP/1.1 branch which serves /healthz, /v1/eval, and WS
   upgrade on /ws.

   Bencode branch
     - Uses native `nrepl.bencode/read-nrepl-message` + `write-bencode`,
       `nrepl.transport/fn-transport`, and `(nrepl.server/default-handler)`
       through `nrepl.server/handle`.
     - Restores the `nrepl 1.7.0` byte-array UTF-8 decoding / keywordize-
       keys contract.

   Raw branch contract (per connection, until EOF / error / `(exit)`):
     - UTF-8 lines, capped at 64 KiB per line (line terminator excluded).
     - Blank/whitespace-only lines are silently skipped.
     - A trimmed `(exit)` line writes `bye\\n` and closes the socket.
     - Any other line evaluates in the `user` namespace; the value is
       `pr-str`'d and written followed by `\\n`.
     - Evaluation failures are reported as `ERROR: <message>\\n`.

   HTTP branch contract:
     - `GET /healthz`         — 200 'ok\\n' (text/plain; sanity probe)
     - `POST /v1/eval`        — body is a Clojure form evaluated in the
                                `user` namespace; 200 returns the pr-str
                                result, 500 returns 'ERROR: <msg>\\n'.
     - `GET /ws` (Upgrade)    — RFC 6455 WebSocket handshake.
     - anything else          — 404 'Not Found\\n'.
     - All responses carry `Connection: close`.

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
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [nihilite.readline :as readline]
            [nrepl.bencode :as bencode]
            [nrepl.server :as nrserver]
            [nrepl.transport :as nrtransport])
  (:import [java.net ServerSocket Socket InetSocketAddress
                    SocketTimeoutException]
           [java.io BufferedInputStream ByteArrayOutputStream
                    OutputStream PushbackInputStream]
           [java.security MessageDigest]
           [java.util Arrays Base64]
           [java.util.concurrent Executors
                               BlockingQueue ArrayBlockingQueue
                               TimeUnit]))

;; ===========================================================================
;; Section 1: Constants + log
;; ===========================================================================

(def ^:const ^:long sniff-bytes 16)
(def ^:const ^:long sniff-timeout-ms 2000)
(def ^:const ^:long raw-max-line-bytes 65536)
(def ^:const ^:long accept-timeout-ms 200)
(def ^:const bencode-pushback-buf 1024)
(def ^:const ^:long telnet-probe-timeout-ms 300)

;; Telnet IAC bytes nudge a telnet client into char-at-a-time mode.
(def ^:const ^:long iac  255) ; 0xFF Interpret As Command
(def ^:const ^:long dont 254)
(def ^:const ^:long do-  253)
(def ^:const ^:long wont 252)
(def ^:const ^:long will 251)
(def ^:const ^:long sb   250) ; subnegotiation begin
(def ^:const ^:long se   240) ; subnegotiation end
(def ^:const ^:long opt-echo 1)
(def ^:const ^:long opt-sga  3)
(def ^:const ^:long ws-max-frame-bytes 65536)
(def ^:const ^:long ws-max-accum-bytes 65536)
(def ^:const ^:long ws-idle-timeout-ms 30000)



;; ===========================================================================
;; Section 2: Sniff helpers
;; ===========================================================================

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

(defn- safe-reset! [^BufferedInputStream in]
  (try (.reset in) (catch Throwable _)))

;; ===========================================================================
;; Section 3: sniff + bencode branch
;; ===========================================================================

(defn- sniff
  "Mark+reset the first up to `sniff-bytes` bytes of `in` and classify
   the prefix. Returns :bencode, :http, or :raw."
  [^Socket sock ^BufferedInputStream in]
  (.setSoTimeout sock (int sniff-timeout-ms))
  (.mark in (int sniff-bytes))
  (let [buf (byte-array sniff-bytes)
        n (try (.read in buf 0 (int sniff-bytes))
               (catch SocketTimeoutException _ -1)
               (catch Throwable _ -1))]
    (safe-reset! in)
    (cond
      (neg? (int n)) :raw
      (bencode-prefix? buf (long n)) :bencode
      (http-method-prefix? buf (long n)) :http
      :else :raw)))

(defn- decode-message
  "Walk a parsed nrepl message: keys keywordized; byte-array values
   UTF-8 decoded EXCEPT for any key listed under `-unencoded`."
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

(defn- handle-bencode [^Socket sock ^BufferedInputStream buf-in]
  (.setSoTimeout sock 0)
  (let [pb-in (PushbackInputStream. buf-in bencode-pushback-buf)
        ^OutputStream out (.getOutputStream sock)]
    (try
      (let [transport (nrtransport/fn-transport
                        (fn [] (decode-message (bencode/read-nrepl-message pb-in)))
                        (fn [resp]
                          (locking out
                            (bencode/write-bencode out resp)
                            (.flush out)))
                        (fn [] (try (.close sock) (catch Throwable _))))]
        (nrserver/handle (nrserver/default-handler) transport))
      (catch Throwable t
        (log/error t "bencode connection error"))
      (finally
        (try (.close sock) (catch Throwable _))))))

;; ===========================================================================
;; Section 4: Raw branch helpers (drain, read-line, write, eval)
;; ===========================================================================

(defn- drain-rest-of-line!
  "Consume bytes until \\n, \\r\\n, or EOF so the next line-read starts
   on a fresh line."
  [^BufferedInputStream in]
  (loop []
    (let [b (.read in)]
      (cond
        (neg? b) nil
        (== b 10) nil
        (== b 13) (do (.mark in 1)
                      (let [peek (.read in)]
                        (when (and (>= peek 0) (not= peek 10))
                          (safe-reset! in))))
        :else (recur)))))

(defn- read-bounded-line
  "Read up to and including a line terminator. Returns the UTF-8 string
   (terminator stripped), or nil on EOF before any byte. Throws
   `:nihilite/oversized-line` if the line body exceeds `max-bytes`."
  ^String [^BufferedInputStream in ^long max-bytes]
  (let [buf (ByteArrayOutputStream.)]
    (loop []
      (let [b (.read in)]
        (cond
          (neg? b)
          (if (zero? (.size buf))
            nil
            (.toString buf "UTF-8"))
          (or (== b 10) (== b 13))
          (do
            (when (== b 13)
              (.mark in 1)
              (let [peek (.read in)]
                (when (and (>= peek 0) (not= peek 10))
                  (safe-reset! in))))
            (.toString buf "UTF-8"))
          :else
          (do
            (when (>= (.size buf) max-bytes)
              (drain-rest-of-line! in)
              (throw (ex-info "raw line exceeds max-bytes"
                              {:nihilite/kind :nihilite/oversized-line
                               :max-bytes max-bytes})))
            (.write buf b)
            (recur)))))))

(defn- write-utf8-line! [^OutputStream out ^String s]
  (locking out
    (.write out (.getBytes s "UTF-8"))
    (.flush out)))

(defn- raw-crlf
  "Normalize bare LF to CRLF so raw clients in a no-ONLCR terminal
   (socat rawer, telnet char-mode) render at column 0 instead of
   staircasing. A pre-existing CRLF is left intact (the regex only
   matches an LF not already preceded by CR)."
  ^String [^String s]
  (str/replace s #"(?<!\r)\n" "\r\n"))

(defn- raw-write!
  "Write a string to a raw-branch client, LF→CRLF normalized, flushed.
   `writer` is either a java.io.OutputStream (line mode, the raw
   socket output) or an org.jline.terminal.Terminal (char mode, which
   owns its own output stream for ANSI-aware writing through JLine's
   discipline). For Terminal we use `.output()` to get the underlying
   OutputStream JLine writes through, so our writes go through JLine's
   own buffering (mouse-mode toggles, bracket-paste, etc.)."
  [writer ^String s]
  (let [out (if (instance? org.jline.terminal.Terminal writer)
              (.output ^org.jline.terminal.Terminal writer)
              writer)]
    (locking out
      (.write out (.getBytes (raw-crlf s) "UTF-8"))
      (.flush out))))

(defn- safe-eval-line
  "Thin wrapper: delegates the eval+`*1`/`*2`/`*3`/`*e` state to
   `nihilite.readline/eval-form-lf`, then sets the namespace on the
   per-connection state atom. The raw branch uses the LF-terminated
   variant because the client (nc / socat) does its own line
   discipline and CRLF would interfere."
  ^String [^String form-str ns repl-state]
  (swap! repl-state assoc :ns ns)
  (readline/eval-form-lf form-str repl-state))

;; ===========================================================================

(defn- negotiate-echo-mode!
  "Send `IAC WILL ECHO` + `IAC WILL SGA`, then probe (with a short
   SO_TIMEOUT) for the client's reply to decide who owns echo:

     :char  — client answered `IAC DO ECHO` (real telnet). It has
              dropped local echo; the server runs the char-at-a-time
              line editor (single echo, history, C-c/C-d).
     :line  — client answered `DONT`/other, or stayed silent until
              the probe timed out (nc, socat, socat rawer). It keeps
              its own cooked-mode local echo/line editing; the server
              never echoes and reads whole lines.

   Any leading telnet IAC command bytes the client sent are drained
   here so they never reach the REPL. A non-IAC byte typed during the
   probe window (rare) is reset back into the stream for the reader.
   Restores the socket to blocking (SO_TIMEOUT 0) before returning."
  [^Socket sock ^BufferedInputStream in ^OutputStream out]
  (locking out
    (.write out (byte-array (map unchecked-byte
                                 [iac will opt-echo
                                  iac will opt-sga])))
    (.flush out))
  (.setSoTimeout sock (int telnet-probe-timeout-ms))
  (let [saw-do-echo (atom false)
        mode (try
               (loop []
                 (.mark in 8)
                 (let [b (.read in)]
                   (cond
                     (neg? b) (if @saw-do-echo :char :line)
                     (= b iac)
                     (let [verb (.read in)
                           opt  (.read in)]
                       (when (and (= verb do-) (= opt opt-echo))
                         (reset! saw-do-echo true))
                       (recur))
                     :else
                     (do (safe-reset! in)
                         (if @saw-do-echo :char :line)))))
               (catch SocketTimeoutException _
                 (if @saw-do-echo :char :line))
               (catch Throwable _
                 (if @saw-do-echo :char :line)))]
    (.setSoTimeout sock 0)
    mode))


;; ===========================================================================
(import '(org.jline.terminal.impl AbstractTerminal ExternalTerminal)
        '(org.jline.terminal TerminalBuilder Terminal Terminal$SignalHandler))
(import '(org.jline.reader LineReader LineReaderBuilder Completer
                           Candidate UserInterruptException
                           EndOfFileException)
        '(org.jline.reader.impl DefaultParser))

(defn- handle-raw
  "Interactive raw REPL branch. Single jline3 xterm readline drives every
   raw client (no `:line` vs `:char` fork).

   On connect the server sends `IAC WILL ECHO/SGA` (via
   `negotiate-echo-mode!`, called for its side-effect only — the
   :char/:line return is ignored) to nudge a real telnet client into
   char-at-a-time mode. jline3 does NOT speak telnet IAC, so this
   nudge is what makes telnet usable; nc/socat ignore the bytes.

   Then a single `nihilite.readline/run-loop` drives the session:
   char echo, 1000-entry shared history (Up/Down + C-r), TAB
   completion, cursor motion, paren-balance continuation, C-c
   eval-cancel, C-d exit-on-empty, friendly non-leaky errors. A
   cooked-mode `nc` still connects and can eval whole lines; it just
   lacks in-line editing (its own terminal owns the line discipline).

   `(exit)` or C-d-on-empty returns from run-loop; we then write
   `bye` and close the socket (never System/exit)."
  [^Socket sock ^BufferedInputStream buf-in]
  (.setSoTimeout sock 0)
  (let [raw-out (.getOutputStream sock)
        user-ns (or (find-ns 'user) (create-ns 'user))
        repl-state (atom {:ns user-ns :*1 nil :*2 nil :*3 nil :*e nil})
        write! (fn [^String s] (raw-write! raw-out s))]
    (try
      ;; IAC nudge is side-effect only; jline3 keeps the readline snug
      (negotiate-echo-mode! sock buf-in raw-out)
      (raw-write! raw-out readline/banner)
      (raw-write! raw-out (str "Connect time: " (java.time.LocalTime/now) "\n"))
      (let [terminal (readline/build-terminal buf-in raw-out "nihilite-raw")]
        (try
          (readline/run-loop terminal repl-state write!)
          ;; bye goes out before the socket closes so the client sees it.
          (raw-write! raw-out "bye\n")
          (finally (.close terminal))))
      (catch Throwable t
        (log/error t "raw connection error"))
      (finally
        (try (.close sock) (catch Throwable _))))))

;; ===========================================================================
;; Section 7: HTTP helpers (header reader, response writer, body reader)
;; ===========================================================================

(defn- read-http-headers
  "Read HTTP/1.x header lines until blank line. Returns a map of
   header-name (lower-case, trimmed) -> value (trimmed)."
  [^BufferedInputStream buf-in]
  (loop [headers (transient {})]
    (let [line (read-bounded-line buf-in raw-max-line-bytes)]
      (cond
        (nil? line)        (persistent! headers)
        (str/blank? line)  (persistent! headers)
        :else
        (let [colon (.indexOf line (int \:))]
          (if (and (>= colon 0))
            (recur (assoc! headers
                           (-> line (subs 0 colon) str/trim str/lower-case)
                           (-> line (subs (inc colon)) str/trim)))
            (recur headers)))))))

(defn- write-http-response!
  "Write an HTTP/1.1 response with status line, Content-Type,
   Content-Length, Connection: close, and the body. Flushes after
   writing. Status is passed as a boxed Integer."
  [^OutputStream out ^Integer status ^String status-text
   ^String content-type ^String body]
  (let [body-bytes (.getBytes ^String body "UTF-8")
        head (str "HTTP/1.1 " status " " status-text "\r\n"
                  "Content-Type: " content-type "\r\n"
                  "Content-Length: " (count body-bytes) "\r\n"
                  "Connection: close\r\n"
                  "\r\n")]
    (.write out (.getBytes ^String head "UTF-8"))
    (.write out body-bytes)
    (.flush out)))

(defn- read-http-body
  "Read exactly content-length bytes from buf-in as a UTF-8 string.
   Caller must drain the request line + headers first so the next
   read starts at the body."
  [^BufferedInputStream buf-in ^long content-length]
  (let [buf (byte-array content-length)]
    (.read buf-in buf 0 content-length)
    (String. ^bytes buf "UTF-8")))

;; ===========================================================================
;; Section 8: HTTP routes + handle-http
;; ===========================================================================

(defn- http-route-healthz [_method _path _headers]
  [200 "OK" "text/plain; charset=utf-8" "ok\n"])

(defn- http-route-eval [body]
  (let [user-ns (or (find-ns 'user) (create-ns 'user))
        repl-state (atom {:ns user-ns :*1 nil :*2 nil :*3 nil :*e nil})
        response (safe-eval-line body user-ns repl-state)
        is-error (str/starts-with? response "ERROR ")
        status (if is-error 500 200)
        status-text (if is-error "Internal Server Error" "OK")]
    [status status-text "text/plain; charset=utf-8" response]))

(defn- http-route-notfound [method path]
  (log/info "http" method path "-> 404")
  [404 "Not Found" "text/plain; charset=utf-8" "Not Found\n"])

(defn- http-route-badrequest [_reason _request-line]
  [400 "Bad Request" "text/plain; charset=utf-8" "Bad Request\n"])

(defn- http-route-ws-rejected [verr headers]
  (log/warn "http WS upgrade rejected:" verr
            "from" (get headers "host"))
  [(:status verr) "Bad Request" "text/plain; charset=utf-8"
   (str "WebSocket upgrade rejected: " (:reason verr) "\n")])

;; Forward-declared — defined in section 9 (WebSocket helpers).
(declare ws-validation-error handle-ws)

(defn- parse-request-line
  "Returns either [:ok method path parts] or [:bad reason request-line]."
  [buf-in]
  (let [request-line (read-bounded-line buf-in raw-max-line-bytes)]
    (cond
      (nil? request-line)
      [:bad "malformed" request-line]

      :else
      (let [parts (str/split request-line #"\s+")]
        (cond
          (not (= 3 (count parts)))
          [:bad "malformed" request-line]

          (not (str/starts-with? ^String (nth parts 2) "HTTP/"))
          [:bad "non-http" request-line]

          :else
          [:ok (nth parts 0) (nth parts 1) parts])))))

(defn- handle-http
  "HTTP/1.1 branch. Parses request line + headers, routes to the
   registered endpoints, writes response with Connection: close. On
   RFC 6455 WebSocket upgrade hands the parsed headers off to
   handle-ws which owns the connection from there.

   Endpoints:
     GET  /healthz   — 200 ok\\n  (text/plain; sanity probe)
     POST /v1/eval   — body is a Clojure form evaluated in the
                       user namespace; 200 returns pr-str result,
                       500 returns ERROR: <msg>\\n on Throwable
     GET  /ws        — RFC 6455 WebSocket upgrade
     anything else   — 404 Not Found\\n"
  [^Socket sock ^BufferedInputStream buf-in]
  (.setSoTimeout sock 0)
  (let [^OutputStream out (.getOutputStream sock)
        parsed (parse-request-line buf-in)
        [method path headers] (case (first parsed)
                                :ok  [(nth parsed 1) (nth parsed 2)
                                      (read-http-headers buf-in)]
                                :bad [nil nil nil])
        ws-upgrade? (and (= :ok (first parsed))
                         (= "websocket"
                            (some-> (get headers "upgrade") str/lower-case))
                         (some-> (get headers "connection") str/lower-case
                                 (str/includes? "upgrade")))]
    (cond
      (= :bad (first parsed))
      (let [bad-line (nth parsed 2)
            [s st ct b] (http-route-badrequest "malformed" bad-line)]
        (log/warn "http malformed line:" (pr-str bad-line))
        (write-http-response! out s st ct b))

      ws-upgrade?
      (let [verr (ws-validation-error method path headers)]
        (if verr
          (let [[s st ct b] (http-route-ws-rejected verr headers)]
            (write-http-response! out s st ct b))
          (do
            (log/info "http WS upgrade accepted from"
                 (get headers "host") "to" path)
            (handle-ws sock buf-in headers))))

      (and (= method "GET") (= path "/healthz"))
      (let [[s st ct b] (http-route-healthz method path headers)]
        (write-http-response! out s st ct b))

      (and (= method "POST") (= path "/v1/eval"))
      (let [cl-str (get headers "content-length")
            body (if cl-str
                   (read-http-body buf-in (Long/parseLong cl-str))
                   "")
            preview (if (> (count body) 60)
                      (str (subs body 0 60) "...")
                      body)]
        (log/debug "http POST /v1/eval" (pr-str preview))
        (let [[s st ct b] (http-route-eval body)]
          (write-http-response! out s st ct b)))

      :else
      (let [[s st ct b] (http-route-notfound method path)]
        (write-http-response! out s st ct b)))))

;; ===========================================================================
;; Section 9: WebSocket helpers (accept-key, validation, exact-bytes, frame IO)
;; ===========================================================================

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

(defn- ws-validation-error
  "Phase pre-check for an HTTP request that should be a WS upgrade.
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
        (not= "websocket" (str/lower-case (get headers "upgrade"))))
    {:status 400 :reason "missing-upgrade"}

    (or (nil? (get headers "connection"))
        (not (str/includes? (str/lower-case (get headers "connection"))
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
          bos (java.io.ByteArrayOutputStream.)]
      (.write bos code-bytes)
      (.write bos reason-bytes)
      (write-ws-frame out 0x8 (.toByteArray bos)))))

;; ===========================================================================
;; Section 10: WebSocket frame handlers, WS loop, handle-ws, dispatch, start!
;; ===========================================================================

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
        response (safe-eval-line text user-ns repl-state)
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

(defn- handle-ws
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
          accum-buf (java.io.ByteArrayOutputStream.)
          accum-opcode (atom nil)
          repl-state (atom {:ns user-ns :*1 nil :*2 nil :*3 nil :*e nil})]
      (try
        (ws-loop buf-in out user-ns repl-state accum-buf accum-opcode)
        (catch Throwable t
          (log/error t "ws connection error"))
        (finally
          (try (.close sock) (catch Throwable _)))))))

(defn- dispatch [^Socket sock active]
  (try
    (swap! active conj sock)
    (let [buf-in (BufferedInputStream. (.getInputStream sock) 4096)
          kind (sniff sock buf-in)]
      (case kind
        :bencode (handle-bencode sock buf-in)
        :http    (handle-http sock buf-in)
        :raw     (handle-raw sock buf-in)))
    (catch Throwable t
      (log/error t "dispatch error"))
    (finally
      (try (swap! active disj sock) (catch Throwable _))
      (try (.close sock) (catch Throwable _)))))

(defn start!
  "Start the single-port dispatcher. Returns a `(fn stop! [])` that
   closes the listener, all active connections, and the worker pool.
   Stop is idempotent.

   Options:
     :port    - int, default 7888
     :bind    - str, default `127.0.0.1`
     :threads - int, default 16"
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
