(ns nihilite.transport.io
  "Shared byte-stream helpers and the eval bridge used by every
   transport branch (sniff, raw, http, ws)."
  (:require [nihilite.readline :as readline])
  (:import [java.io BufferedInputStream ByteArrayOutputStream]))

(def ^:const ^:long raw-max-line-bytes 65536)

(defn safe-reset! [^BufferedInputStream in]
  (try (.reset in) (catch Throwable _)))

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

(defn read-bounded-line
  "Read up to and including a line terminator into a byte buffer.
   Returns the UTF-8 string (terminator stripped), or nil on EOF.
   Throws `:nihilite/oversized-line` if line body exceeds `max-bytes`."
  ^String [^BufferedInputStream in ^long max-bytes]
  (let [buf (ByteArrayOutputStream. 256)]
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

(defn safe-eval-line
  "Thin wrapper: delegates the eval+`*1`/`*2`/`*3`/`*e` state to
   `nihilite.readline/eval-form-lf`, then sets the namespace on the
   per-connection state atom. Callers use the LF-terminated variant
   because the client does its own line discipline and CRLF would
   interfere."
  ^String [^String form-str ns repl-state]
  (swap! repl-state assoc :ns ns)
  (readline/eval-form-lf form-str repl-state))
