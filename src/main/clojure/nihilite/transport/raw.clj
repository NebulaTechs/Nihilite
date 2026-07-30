(ns nihilite.transport.raw
  "Interactive raw REPL branch. A telnet IAC nudge decides echo mode
   (:char for real telnet, :line for nc/socat); terminal type and
   jline3 list-renderer options follow from that probe."
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
   render at column 0 instead of staircasing."
  ^String [^String s]
  (str/replace s #"(?<!\r)\n" "\r\n"))

(defn- raw-write!
  "Write a string LF→CRLF normalized, flushed. `writer` is either an
   OutputStream or a jline3 Terminal (which owns its own stream).
   Both paths lock on the underlying OutputStream so writes never
   interleave with jline3's own ANSI output."
  [writer ^String s]
  (let [out (if (instance? org.jline.terminal.Terminal writer)
               (.output ^org.jline.terminal.Terminal writer)
               writer)]
    (locking out
      (.write out (.getBytes (raw-crlf s) "UTF-8"))
      (.flush out))))

(defn- negotiate-echo-mode!
  "Send WILL ECHO + WILL SGA; probe for DO ECHO reply.
   Returns :char (real telnet) or :line (nc/socat/stayed silent).
   Drains leading IAC bytes; non-IAC during probe is reset for reader.
   Restores SO_TIMEOUT 0 before returning."
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
  "Interactive raw REPL. Single jline3 readline drives every raw client.
   Terminal type is selected from the echo-mode probe: real telnet
   (:char) keeps xterm + multi-column list; nc/socat (:line) drops to
   dumb + LIST_PACKED so jline3's renderer does not staircase."
  [^Socket sock ^BufferedInputStream buf-in]
  (.setSoTimeout sock 0)
  (let [raw-out (.getOutputStream sock)
        user-ns (or (find-ns 'user) (create-ns 'user))
        repl-state (atom {:ns user-ns :*1 nil :*2 nil :*3 nil :*e nil})
        write! (fn [^String s] (raw-write! raw-out s))]
    (try
      (let [echo-mode (negotiate-echo-mode! sock buf-in raw-out)
            term-type (if (= echo-mode :char) :xterm :dumb)]
        (raw-write! raw-out readline/banner)
        (raw-write! raw-out (str "Connect time: " (java.time.LocalTime/now) "\n"))
        (let [terminal (readline/build-terminal buf-in raw-out "nihilite-raw" term-type)]
          (try
            (readline/run-loop terminal repl-state write!)
            (raw-write! raw-out "bye\n")
            (finally (.close terminal)))))
      (catch Throwable t
        (log/error t "raw connection error"))
      (finally
        (try (.close sock) (catch Throwable _))))))
