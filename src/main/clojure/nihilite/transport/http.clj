(ns nihilite.transport.http
  "HTTP/1.1: one round-trip per conn; WS upgrade handoff to handle/handle-ws."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [nihilite.transport.io :as io]
            [nihilite.transport.ws.handshake :as ws-hs]
            [nihilite.transport.ws.handle :as ws-handle])
  (:import [java.net Socket]
           [java.io BufferedInputStream OutputStream]))

(defn- write-reply!
  "Emit one HTTP/1.1 response. Status is passed as a boxed Integer."
  [^OutputStream out ^Integer status ^String status-text
   ^String content-type ^String body]
  (let [head  (.getBytes (str "HTTP/1.1 " status " " status-text "\r\n"
                              "Content-Type: " content-type "\r\n"
                              "Content-Length: " (count (.getBytes ^String body "UTF-8")) "\r\n"
                              "Connection: close\r\n"
                              "\r\n")
                         "UTF-8")
        chunk (.getBytes ^String body "UTF-8")]
    (.write out head)
    (.write out chunk)
    (.flush out)))

(defn- read-headers
  "Read HTTP/1.x header lines until blank line. Keys lower-cased + trimmed,
   values trimmed."
  [^BufferedInputStream buf-in]
  (loop [hs (transient {})]
    (let [line (io/read-bounded-line buf-in io/raw-max-line-bytes)]
      (cond
        (or (nil? line) (str/blank? line))
        (persistent! hs)
        :else
        (let [colon (.indexOf line (int \:))]
          (recur
            (if (>= colon 0)
              (assoc! hs
                      (-> line (subs 0 colon) str/trim str/lower-case)
                      (-> line (subs (inc colon)) str/trim))
              hs)))))))

(defn handle-http
  "Endpoints: GET /healthz, POST /v1/eval, GET /ws (upgrade), else 404."
  [^Socket sock ^BufferedInputStream buf-in]
  (let [^OutputStream out (.getOutputStream sock)
        ;; sniff left soTimeout=2s; clear it so body reads can block.
        _ (.setSoTimeout sock 0)
        request-line (io/read-bounded-line buf-in io/raw-max-line-bytes)
        parts        (when request-line (str/split request-line #"\s+"))
        [method path headers]
        (if (and parts (= 3 (count parts))
                 (str/starts-with? ^String (nth parts 2) "HTTP/"))
          [(nth parts 0) (nth parts 1) (read-headers buf-in)]
          [nil nil nil])
        ws-upgrade?
        (and (= method "GET")
             (= path "/ws")
             (some-> (get headers "upgrade") str/lower-case (= "websocket"))
             (some-> (get headers "connection") str/lower-case
                     (str/includes? "upgrade")))]
      (cond
        (nil? method)
      (do
        (log/warn "http malformed line:" (pr-str request-line))
        (write-reply! out 400 "Bad Request"
                     "text/plain; charset=utf-8" "Bad Request\n"))

      ws-upgrade?
      (if-let [verr (ws-hs/ws-validation-error method path headers)]
        (do
          (log/warn "http WS upgrade rejected:" verr
                    "from" (get headers "host"))
          (write-reply! out (:status verr) "Bad Request"
                       "text/plain; charset=utf-8"
                       (str "WebSocket upgrade rejected: " (:reason verr) "\n")))
        (do
          (log/info "http WS upgrade accepted from"
                    (get headers "host") "to" path)
          (ws-handle/handle-ws sock buf-in headers)))

      (and (= method "GET") (= path "/healthz"))
      (write-reply! out 200 "OK"
                   "text/plain; charset=utf-8" "ok\n")

      (and (= method "POST") (= path "/v1/eval"))
      (let [body      (if-let [cl (get headers "content-length")]
                        (let [n   (Long/parseLong cl)
                              buf (byte-array n)]
                          (.read buf-in buf 0 n)
                          (String. ^bytes buf "UTF-8"))
                        "")
            preview   (if (> (count body) 60)
                        (str (subs body 0 60) "...")
                        body)
            user-ns   (or (find-ns 'user) (create-ns 'user))
            repl      (atom {:ns user-ns :*1 nil :*2 nil :*3 nil :*e nil})
            response  (io/safe-eval-line body user-ns repl)
            err?      (str/starts-with? response "ERROR ")]
        (log/debug "http POST /v1/eval" (pr-str preview))
        (if err?
          (write-reply! out 500 "Internal Server Error"
                       "text/plain; charset=utf-8" response)
          (write-reply! out 200 "OK"
                       "text/plain; charset=utf-8" response)))

      :else
      (do
        (log/info "http" method path "-> 404")
        (write-reply! out 404 "Not Found"
                     "text/plain; charset=utf-8" "Not Found\n")))))
