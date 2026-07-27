(ns nihilite.transport.http
  "HTTP/1.1 branch. Parses request line + headers, routes to /healthz,
   /v1/eval, and the /ws WebSocket upgrade, then writes a
   Connection: close response. WS upgrades are handed to
   `nihilite.transport.ws/handle-ws`."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [nihilite.transport.io :as io]
            [nihilite.transport.ws :as ws])
  (:import [java.net Socket]
           [java.io BufferedInputStream OutputStream]))

(defn- read-http-headers
  "Read HTTP/1.x header lines until blank line. Returns a map of
   header-name (lower-case, trimmed) -> value (trimmed)."
  [^BufferedInputStream buf-in]
  (loop [headers (transient {})]
    (let [line (io/read-bounded-line buf-in io/raw-max-line-bytes)]
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

(defn- http-route-healthz [_method _path _headers]
  [200 "OK" "text/plain; charset=utf-8" "ok\n"])

(defn- http-route-eval [body]
  (let [user-ns (or (find-ns 'user) (create-ns 'user))
        repl-state (atom {:ns user-ns :*1 nil :*2 nil :*3 nil :*e nil})
        response (io/safe-eval-line body user-ns repl-state)
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

(defn- parse-request-line
  "Returns either [:ok method path parts] or [:bad reason request-line]."
  [buf-in]
  (let [request-line (io/read-bounded-line buf-in io/raw-max-line-bytes)]
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

(defn handle-http
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
      (let [verr (ws/ws-validation-error method path headers)]
        (if verr
          (let [[s st ct b] (http-route-ws-rejected verr headers)]
            (write-http-response! out s st ct b))
          (do
            (log/info "http WS upgrade accepted from"
                 (get headers "host") "to" path)
            (ws/handle-ws sock buf-in headers))))

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
