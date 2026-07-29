(ns nihilite.transport.raw
  "Interactive raw REPL branch. A single jline3 xterm readline drives
   every raw client; a telnet IAC nudge coaxes real telnet clients into
   char-at-a-time mode (nc/socat ignore it)."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [nihilite.readline :as readline]
            [nihilite.transport.io :as io])
  (:import [java.net Socket SocketTimeoutException]
           [java.io BufferedInputStream OutputStream]))

(def ^:const ^:long telnet-probe-timeout-ms 300)

;; Telnet IAC bytes nudge a telnet client into char-at-a-time mode.
(def ^:const ^:long iac  255) ; 0xFF Interpret As Command
(def ^:const ^:long do-  253)
(def ^:const ^:long will 251)
(def ^:const ^:long opt-echo 1)
(def ^:const ^:long opt-sga  3)

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
                     (do (io/safe-reset! in)
                         (if @saw-do-echo :char :line)))))
               (catch SocketTimeoutException _
                 (if @saw-do-echo :char :line))
               (catch Throwable _
                 (if @saw-do-echo :char :line)))]
    (.setSoTimeout sock 0)
    mode))

(defn handle-raw
  "Interactive raw REPL. Single jline3 xterm readline drives every raw client."
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
