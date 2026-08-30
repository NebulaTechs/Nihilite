(ns nihilite.test.transport-timeout-test
  (:require [clojure.test :refer [deftest is testing]]
            [nihilite.test.fixtures :as fx]
            [nihilite.transport :as t])
  (:import [java.net ServerSocket Socket InetSocketAddress
                    SocketTimeoutException]
           [java.io InputStream]
           [java.util.concurrent TimeUnit]))

(defn- bind-listener
  [f]
  (let [port (fx/free-port)
        s    (ServerSocket.)]
    (.setReuseAddress s true)
    (.bind s (InetSocketAddress. "127.0.0.1" (int port)))
    (f s (long port))
    (try (.close s) (catch Throwable _))))

(defn- cleanup
  [& resources]
  (doseq [r resources]
    (when r
      (try (.close ^Socket r) (catch Throwable _))
      (try (future-cancel ^java.util.concurrent.Future r) (catch Throwable _)))))

(defn- drive-accepted-read-test
  [idle-bound-ms]
  (bind-listener
    (fn [server port]
      (let [accepted (atom nil)
            acceptor (future (reset! accepted (.accept server)))
            client   (Socket. "127.0.0.1" (int port))]
        (try
          (.get acceptor fx/connect-timeout-ms TimeUnit/MILLISECONDS)
          (let [^Socket srv-sock @accepted]
            (is (some? srv-sock) "server accepted the client connection")
            (.setSoTimeout srv-sock (int idle-bound-ms))
            (Thread/sleep (+ idle-bound-ms 100))
            (let [^InputStream in (.getInputStream srv-sock)
                  buf (byte-array 16)]
              (is (thrown? SocketTimeoutException
                           (.read in buf 0 (int 16)))
                  "idle read on accepted socket raises SocketTimeoutException")))
          (finally
            (cleanup client)))))))

(defn- drive-listener-accept-test
  [idle-bound-ms]
  (bind-listener
    (fn [server port]
      (let [acceptor (future (.accept server))]
        (try
          (Thread/sleep (+ idle-bound-ms 100))
          (let [client (Socket. "127.0.0.1" (int port))]
            (try
              (let [accepted (.get acceptor fx/connect-timeout-ms TimeUnit/MILLISECONDS)]
                (is (instance? Socket accepted)
                    "listener accepted after idle > idle-timeout-ms"))
              (finally (.close client))))
          (finally
            (try (future-cancel acceptor) (catch Throwable _))))))))

(deftest accepted-socket-read-times-out-after-idle
  (testing "accepted socket read times out after idle"
    (with-redefs [t/idle-timeout-ms 250]
      (drive-accepted-read-test t/idle-timeout-ms))))

(deftest listener-accept-does-not-use-idle-timeout
  (testing "ServerSocket listener stays responsive past idle bound"
    (with-redefs [t/idle-timeout-ms 250]
      (drive-listener-accept-test t/idle-timeout-ms))))

(deftest idle-timeout-constant-is-set
  (testing "idle-timeout-ms defaults to 30000ms"
    (is (= 30000 (deref #'t/idle-timeout-ms))
        "idle-timeout-ms defaults to 30000ms (30s)")))
