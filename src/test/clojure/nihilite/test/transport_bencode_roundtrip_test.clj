(ns nihilite.test.transport-bencode-roundtrip-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nihilite.test.fixtures :as fx]
            [nihilite.transport :as t]
            [nrepl.bencode :as nb])
  (:import [java.net ServerSocket Socket InetSocketAddress]
           [java.io BufferedInputStream BufferedOutputStream
                    ByteArrayOutputStream]))

(defn- try-bind? [^long port]
  ^boolean
  (let [s (ServerSocket.)]
    (try
      (.setReuseAddress s true)
      (.bind s (InetSocketAddress. "127.0.0.1" (int port)))
      (.close s)
      true
      (catch Throwable _ false))))

(defn- read-upto
  [^Socket sock ^BufferedInputStream in ^long max-ms]
  (let [deadline (+ (System/currentTimeMillis) max-ms)
        buf (ByteArrayOutputStream.)
        chunk (byte-array 4096)]
    (loop []
      (let [remaining (- deadline (System/currentTimeMillis))]
        (if-not (pos? remaining)
          (.toString buf "UTF-8")
          (let [n (try
                    (.setSoTimeout sock (int (min 1000 remaining)))
                    (.read in chunk 0 (count chunk))
                    (catch java.net.SocketTimeoutException _ 0))]
            (cond
              (or (neg? n) (zero? n))
              (.toString buf "UTF-8")
              :else
              (do (.write buf chunk 0 n) (recur)))))))))

(defn- send-bencode-eval
  [^Socket sock id code ^long max-ms]
  (let [out (BufferedOutputStream. (.getOutputStream sock))
        in  (BufferedInputStream. (.getInputStream sock))]
    (nb/write-bencode out {:op "eval" :id id :code code})
    (.flush out)
    (read-upto sock in max-ms)))

(deftest transport-binds-and-accepts-tcp
  (testing "start! binds a ServerSocket and accepts TCP connections"
    (let [port (fx/free-port)]
      (if-not (try-bind? port)
        (println "bencode-smoke: skipped — port" port "unavailable")
        (let [stop (t/start! {:port port :bind "127.0.0.1" :threads 4})
              sock (Socket.)]
          (try
            (.connect sock (InetSocketAddress. "127.0.0.1" (int port)))
            (.setSoTimeout sock fx/connect-timeout-ms)
            (is (and (.isConnected sock) (not (.isClosed sock)))
                "client Socket reaches the listener")
            (finally
              (try (.close sock) (catch Throwable _))
              (try (stop) (catch Throwable _)))))))))

(deftest transport-stop-is-idempotent
  (testing "calling the stop-fn returned by start! twice is a no-op"
    (let [port (fx/free-port)]
      (if-not (try-bind? port)
        (println "stop-idempotent: skipped — port" port "unavailable")
        (let [stop (t/start! {:port port :bind "127.0.0.1" :threads 4})]
          (try
            (stop)
            (is (true? (try (stop) true
                            (catch Throwable _ false)))
                "second stop! does not throw")
            (finally
              (try (stop) (catch Throwable _)))))))))

(deftest bencode-eval-roundtrip
  (testing "nREPL bencode eval returns a response containing :done"
    (let [port (fx/free-port)]
      (if-not (try-bind? port)
        (println "eval-roundtrip: skipped — port" port "unavailable")
        (let [stop (t/start! {:port port :bind "127.0.0.1" :threads 4})
              sock (Socket.)]
          (try
            (.connect sock (InetSocketAddress. "127.0.0.1" (int port)))
            (.setSoTimeout sock fx/response-timeout-ms)
            (let [wire (send-bencode-eval sock "rt1" "(+ 1 2)" fx/response-timeout-ms)]
              (is (seq wire) "server returned a non-empty response")
              (is (str/includes? wire "rt1")
                  "response echoes the request id")
              (is (str/includes? wire "done")
                  "response carries a :done status"))
            (finally
              (try (.close sock) (catch Throwable _))
              (try (stop) (catch Throwable _)))))))))

(deftest bencode-eval-nrepl-core-version
  (testing "reply prelude nrepl.core/version evals without ClassNotFoundException"
    (let [port (fx/free-port)]
      (if-not (try-bind? port)
        (println "nrepl-core-version: skipped — port" port "unavailable")
        (let [stop (t/start! {:port port :bind "127.0.0.1" :threads 4})
              sock (Socket.)]
          (try
            (.connect sock (InetSocketAddress. "127.0.0.1" (int port)))
            (.setSoTimeout sock fx/response-timeout-ms)
            (let [wire (send-bencode-eval
                         sock "rt-nrepl"
                         "(:version-string nrepl.core/version)"
                         fx/response-timeout-ms)]
              (is (seq wire) "server returned a non-empty response")
              (is (str/includes? wire "done")
                  "response carries a :done status")
              (is (not (str/includes? wire "ClassNotFoundException"))
                  "nrepl.core is loaded; reply init must not ClassNotFound")
              (is (re-find #"1\.\d+" wire)
                  "value is the bundled nREPL version string"))
            (finally
              (try (.close sock) (catch Throwable _))
              (try (stop) (catch Throwable _)))))))))

(deftest disconnect-ex-classifies-client-close
  (testing "EOFException, SocketException, ClosedChannelException are normal disconnects"
    (is (#'t/disconnect-ex?
          (java.io.EOFException. "Invalid netstring. Unexpected end of input.")))
    (is (#'t/disconnect-ex?
          (java.net.SocketException. "Socket closed")))
    (is (#'t/disconnect-ex?
          (java.nio.channels.ClosedChannelException.))))
  (testing "other throwables remain errors"
    (is (not (#'t/disconnect-ex?
               (IllegalStateException. "handler boom"))))))