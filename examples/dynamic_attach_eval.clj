(ns examples.dynamic-attach-eval
  "Attach Nihilite to a running JVM and evaluate one deterministic expression.

   Invoke this file as a script with the target process id and the Nihilite
   agent jar:

     clojure examples/dynamic_attach_eval.clj <pid> <agent-jar>

   The target must remain alive until this script finishes.

   The channel is a socket in both directions and nothing is written to disk.
   That matters twice over: the attach argument has a hard 1024-character
   limit on JDK 11 through 21, and a file needs writable storage, which a
   phone does not always have.

   The script exits nonzero on any attach, protocol, parsing, session, or
   assertion failure."
  (:require [clojure.edn :as edn])
  (:import [java.io File]
           [java.net InetAddress InetSocketAddress ServerSocket]
           [java.util UUID]
           [com.sun.tools.attach VirtualMachine]))

(def ^:private success-marker "nihilite:example-pass dynamic-attach-eval")

(def ^:private accept-timeout-ms
  "How long the script waits for the target to open a channel.

   The target opens the code channel while the attach is still in flight and
   the reply channel before it answers, so a target that never opens one is
   already broken and waiting longer only hides it."
  30000)

(def ^:private expected-value
  {:eval-started? true
   :value "42"
   :closed? true
   :session-closed? true})

(def ^:private lifecycle-code
  (str
   (pr-str '(require 'nihilite.builder.eval))
   "\n"
   (pr-str
    '(do
       (let [sid (nihilite.builder.eval/open-session)]
         (try
           (let [eval-id (nihilite.builder.eval/eval-in sid "(+ 20 22)")]
             (loop [attempt 0]
               (let [snap (nihilite.builder.eval/snapshot sid)]
                 (cond
                   (:running? snap)
                   (if (< attempt 100)
                     (do
                       (Thread/sleep 10)
                       (recur (inc attempt)))
                     (throw
                      (ex-info "inner eval session did not become idle"
                               {:session sid :snapshot snap})))

                   (:error snap)
                   (throw
                    (ex-info "inner eval session failed"
                             {:session sid :snapshot snap}))

                   :else
                   (do
                     (when-not (string? eval-id)
                       (throw
                        (ex-info "eval-in did not return an eval id"
                                 {:session sid :eval-id eval-id})))
                     (when-not (= "42" (:value snap))
                       (throw
                        (ex-info "inner eval value was not 42"
                                 {:session sid :snapshot snap})))
                     (let [closed? (nihilite.builder.eval/close-session sid)]
                       (when-not (true? closed?)
                         (throw
                          (ex-info "close-session did not close the inner session"
                                   {:session sid :closed? closed?})))
                       (when (nihilite.builder.eval/session? sid)
                         (throw
                          (ex-info "inner session remained registered after close"
                                   {:session sid})))
                       {:eval-started? true
                        :value (:value snap)
                        :closed? closed?
                        :session-closed? (not (nihilite.builder.eval/session? sid))})))
                 )))
           (finally
             (nihilite.builder.eval/close-session sid))))))))

(defn- fail!
  "Throw a categorized failure for the top-level error handler."
  [stage message data]
  (throw (ex-info (str "dynamic attach eval failed during " stage ": " message)
                  (assoc data :nihilite/example-stage stage))))

(defn- parse-args
  "Parse and validate the target pid and agent jar arguments."
  [args]
  (when-not (= 2 (count args))
    (fail! :arguments
           "usage: dynamic_attach_eval.clj <pid> <agent-jar>"
           {:args args}))
  (let [pid-text (first args)
        jar-text (second args)
        pid (parse-long pid-text)]
    (when-not (and pid (pos? pid))
      (fail! :arguments "the target pid must be a positive integer"
             {:pid pid-text}))
    (let [jar-file (File. ^String jar-text)]
      (when-not (.isFile jar-file)
        (fail! :arguments "the agent jar does not exist or is not a file"
               {:jar jar-text}))
      {:pid pid :jar jar-text :jar-file jar-file})))

(defn- open-loopback-server!
  "Opens a server socket on the loopback interface with an ephemeral port.

   Loopback only: this channel is between this script and the one target it
   attached to, and nothing else should be able to reach it."
  ^ServerSocket []
  (let [server (ServerSocket.)]
    (.bind server (InetSocketAddress. (InetAddress/getByName "127.0.0.1") 0))
    (.setSoTimeout server accept-timeout-ms)
    server))

(defn- read-token-line!
  "Reads one newline-terminated line, one byte at a time.

   Byte at a time on purpose. A buffered reader would swallow the bytes that
   follow the newline, and on the code channel those bytes are the code."
  ^String [^java.io.InputStream in]
  (let [sb (StringBuilder.)]
    (loop []
      (let [b (.read in)]
        (when (neg? b)
          (fail! :protocol "the peer closed the connection before sending its token"
                 {}))
        (if (= b 10)
          (.toString sb)
          (do (.append sb (char b)) (recur)))))))

(defn- read-all!
  "Reads the rest of the stream as UTF-8 text."
  ^String [^java.io.InputStream in]
  (let [buf (byte-array 8192)
        sb  (StringBuilder.)]
    (loop []
      (let [n (.read in buf)]
        (if (neg? n)
          (.toString sb)
          (do (.append sb (String. buf 0 n "UTF-8")) (recur)))))))

(defn- check-token!
  "Requires the peer's token to be the one this script handed out."
  [stage expected got]
  (when-not (= expected got)
    (fail! stage "the peer answered with the wrong token"
           {:expected expected :got got})))

(defn- parse-reply
  "Parse and validate one EDN reply map written by the target."
  [payload]
  (let [reply
        (try
          (edn/read-string payload)
          (catch Throwable failure
            (fail! :parse "the target reply is not valid EDN"
                   {:cause (.getMessage failure)})))]
    (when-not (map? reply)
      (fail! :parse "the target reply is not an EDN map"
             {:reply reply}))
    reply))

(defn- start-code-feeder!
  "Starts the thread that hands the code to the target.

   loadAgent blocks until agentmain returns, and agentmain cannot return
   before it has read the code, so the code has to be on its way while
   loadAgent is still blocked. That is what this thread is for."
  [^ServerSocket server token code]
  (let [outcome (atom nil)
        thread  (Thread.
                 (fn []
                   (try
                     (let [sock (.accept server)
                           in   (.getInputStream sock)]
                       (try
                         (check-token! :protocol token (read-token-line! in))
                         (let [out (.getOutputStream sock)]
                           (.write out (.getBytes ^String code "UTF-8"))
                           (.flush out)
                           (.shutdownOutput out))
                         ;; Wait for the target to close its end so both
                         ;; sides tear the socket down cleanly.
                         (read-all! in)
                         (reset! outcome {:ok true})
                         (finally
                           (.close sock))))
                     (catch Throwable failure
                       (reset! outcome {:error failure}))))
                 "nihilite-example-code-feeder")]
    (.setDaemon thread true)
    (.start thread)
    {:thread thread :outcome outcome}))

(defn- read-reply!
  "Accepts the target's reply on the reply channel and parses it."
  [^ServerSocket server token]
  (let [sock (.accept server)
        in   (.getInputStream sock)]
    (try
      (check-token! :protocol token (read-token-line! in))
      (parse-reply (read-all! in))
      (finally
        (.close sock)))))

(defn- validate-reply!
  "Require the current successful reply shape."
  [reply expected-token]
  (let [expected-keys #{:token :session :ns :value :error :out :err :done}]
    (when-not (= expected-keys (set (keys reply)))
      (fail! :protocol "the target reply has an unexpected shape"
             {:reply reply}))
    (when-not (true? (:done reply))
      (fail! :protocol "the target evaluation did not finish"
             {:reply reply}))
    (when-not (nil? (:error reply))
      (fail! :session "the target evaluation reported an error"
             {:reply reply}))
    (when-not (= expected-token (:token reply))
      (fail! :protocol "the reply carried an unexpected token"
             {:expected expected-token :actual (:token reply)}))
    (when-not (string? (:session reply))
      (fail! :protocol "the target reply has no session id"
             {:reply reply}))
    (when-not (string? (:ns reply))
      (fail! :protocol "the target reply has no namespace"
             {:reply reply}))
    (when-not (string? (:value reply))
      (fail! :protocol "the target reply has no rendered value"
             {:reply reply}))
    (when-not (string? (:out reply))
      (fail! :protocol "the target reply has no output stream"
             {:reply reply}))
    (when-not (string? (:err reply))
      (fail! :protocol "the target reply has no error stream"
             {:reply reply}))
    reply))

(defn- validate-value!
  "Parse the rendered value and require the expected deterministic result."
  [reply]
  (let [value
        (try
          (edn/read-string (:value reply))
          (catch Throwable failure
            (fail! :parse "the rendered target value is not valid EDN"
                   {:value (:value reply)
                    :cause (.getMessage failure)})))]
    (when-not (map? value)
      (fail! :parse "the rendered target value is not an EDN map"
             {:value value}))
    (when-not (= expected-value value)
      (fail! :assertion "the target session lifecycle returned an unexpected value"
             {:expected expected-value
              :actual value}))
    value))

(defn- attach-and-eval!
  "Attaches to the target and runs one eval request over two sockets.

   The request carries no code: the code travels on the code channel and the
   reply comes back on the reply channel, so the attach argument stays short
   enough for any JDK."
  [^long pid ^String jar]
  (let [reply-server (open-loopback-server!)
        code-server  (open-loopback-server!)
        reply-token  (str (UUID/randomUUID))
        code-token   (str (UUID/randomUUID))
        request      (str "eval:tcp:127.0.0.1:" (.getLocalPort reply-server) ":" reply-token
                          "|tcp:127.0.0.1:" (.getLocalPort code-server) ":" code-token)]
    (try
      (let [{:keys [thread outcome]} (start-code-feeder! code-server code-token lifecycle-code)
            vm (VirtualMachine/attach (str pid))]
        (try
          (.loadAgent ^VirtualMachine vm ^String jar ^String request)
          (finally
            (.detach ^VirtualMachine vm)))
        (.join thread)
        (when-let [failure (:error outcome)]
          (throw failure))
        {:reply (read-reply! reply-server reply-token) :token reply-token})
      (finally
        (.close reply-server)
        (.close code-server)))))

(defn -main [& args]
  (try
    (let [{:keys [pid jar]} (parse-args args)
          {:keys [reply token]} (attach-and-eval! pid jar)]
      (validate-reply! reply token)
      (validate-value! reply))
    (println success-marker)
    (flush)
    (catch Throwable failure
      (binding [*out* *err*]
        (println "nihilite:example-fail dynamic-attach-eval:"
                 (.getMessage failure))
        (flush))
      (System/exit 1))))

(apply -main *command-line-args*)
