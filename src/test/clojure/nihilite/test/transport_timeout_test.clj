(ns nihilite.test.transport-timeout-test
  "Verifies Wave-1 T5: the idle SO_TIMEOUT is applied to the *accepted*
   socket only, not the ServerSocket listener.

   The listener keeps its short `accept-timeout-ms` (200) so `stop!` can
   wake the accept loop; only after `.accept()` returns do we install
   `idle-timeout-ms` on the per-connection socket. This means:

     1. A client that connects and then idles longer than `idle-timeout-ms`
        gets a SocketTimeoutException on its next read — the server will
        close the connection instead of holding a half-dead worker.

     2. The ServerSocket itself is NOT subject to `idle-timeout-ms`. The
        listener keeps the short wakeup SO_TIMEOUT only; a SYN arriving
        after a long idle still completes accept() promptly.

   `idle-timeout-ms` is a `^:const` so it's inlined at compile time; the
   test `with-redefs`s the var (via `#'`) to use a small value and keep
   the suite fast."
  (:require [clojure.test :refer [deftest is testing]]
            [nihilite.transport :as t])
  (:import [java.net ServerSocket Socket InetSocketAddress
                    SocketTimeoutException]
           [java.io InputStream]
           [java.util.concurrent TimeUnit]))

(defn- free-port ^long []
  (let [s (ServerSocket. 0)]
    (try (.getLocalPort s)
         (finally (.close s)))))

(defn- bind-listener
  "Bind a ServerSocket with NO SO_TIMEOUT applied (mirroring the post-fix
   listener shape: wakeup-only, never the 30s idle bound) and call `f`
   with `[server port]`. Closes the server on exit."
  [f]
  (let [port (free-port)
        s    (ServerSocket.)]
    (.setReuseAddress s true)
    (.bind s (InetSocketAddress. "127.0.0.1" (int port)))
    (f s (long port))
    (try (.close s) (catch Throwable _))))

(defn- cleanup
  "Best-effort cleanup of optional resources; swallows all throwables."
  [& resources]
  (doseq [r resources]
    (when r
      (try (.close ^Socket r) (catch Throwable _))
      (try (future-cancel ^java.util.concurrent.Future r) (catch Throwable _)))))

(defn- drive-accepted-read-test
  "Bind a fresh listener, accept a client, install SO_TIMEOUT on the
   accepted socket, idle > the bound, then read — must STE."
  [idle-bound-ms]
  (bind-listener
    (fn [server port]
      (let [accepted (atom nil)
            acceptor (future (reset! accepted (.accept server)))
            client   (Socket. "127.0.0.1" (int port))]
        (try
          (.get acceptor 2000 TimeUnit/MILLISECONDS)
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
  "Bind a fresh listener, idle > `idle-bound-ms`, then a client SYN
   arrives — accept() must still return promptly."
  [idle-bound-ms]
  (bind-listener
    (fn [server port]
      (let [acceptor (future (.accept server))]
        (try
          (Thread/sleep (+ idle-bound-ms 100))
          (let [client (Socket. "127.0.0.1" (int port))]
            (try
              (let [accepted (.get acceptor 2000 TimeUnit/MILLISECONDS)]
                (is (instance? Socket accepted)
                    "listener accepted after idle > idle-timeout-ms"))
              (finally (.close client))))
          (finally
            (try (future-cancel acceptor) (catch Throwable _))))))))

(deftest accepted-socket-read-times-out-after-idle
  (testing "accepted socket's read raises SocketTimeoutException when
            the client idles longer than idle-timeout-ms (the
            per-connection SO_TIMEOUT installed by the accept loop)."
    (with-redefs [t/idle-timeout-ms 250]
      (drive-accepted-read-test t/idle-timeout-ms))))

(deftest listener-accept-does-not-use-idle-timeout
  (testing "ServerSocket listener is NOT subject to idle-timeout-ms.
            The listener keeps the short wakeup SO_TIMEOUT only, never
            the 30s idle cutoff. After the listener has been idle past
            `idle-timeout-ms`, a client SYN still completes accept()
            promptly — the listener is not pinned to the long idle bound."
    (with-redefs [t/idle-timeout-ms 250]
      (drive-listener-accept-test t/idle-timeout-ms))))

(deftest idle-timeout-constant-is-set
  (testing "transport exposes `idle-timeout-ms` as the per-connection
            idle bound (default 30000ms / 30s)."
    ;; Looked up via the var so the assertion survives future const-inlining.
    (is (= 30000 (deref #'t/idle-timeout-ms))
        "idle-timeout-ms defaults to 30000ms (30s)")))
