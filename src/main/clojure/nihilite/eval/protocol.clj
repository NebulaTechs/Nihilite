(ns nihilite.eval.protocol
  "The wire format an attaching process uses to evaluate code in this JVM.

   VirtualMachine.loadAgent takes a single String and returns void, so that
   String is the only way in and there is no way back. This namespace turns
   it into a usable channel by replying through one of three transports, and
   by keeping the protocol narrow enough that an attacher can implement it
   in any language:

     eval:<transport>|<base64-code>

   <transport> is one of

     discard              run it, reply nowhere
     file:<path>          reply written to that file as EDN
     tcp:<host>:<port>:<token>
                          reply written to a socket, token echoed on the
                          first line so a concurrent attacher can tell its
                          own replies apart

   agentArgs without the eval: prefix is not an eval request, so an existing
   -javaagent or -jar invocation keeps behaving exactly as before.

   The reply is one EDN map with these keys:

     :token     echoed from the transport, for matching
     :session   session id, usable for a follow-up poll
     :ns        namespace the code ran in
     :value     printed value of the last non-nil form
     :error     printed exception, or nil
     :out       everything written to *out*
     :err       everything written to *err*
     :done      false when the wait expired with the eval still running

   A :done of false means the session is still live: poll it by sending a
   second request whose code is (snapshot <session-id>). Polling costs one
   attach round trip per read, which is the price of loadAgent having no
   return channel. Anything wanting cheaper reads should start a real service
   from its own init script instead -- see examples/nrepl_service.clj."
  (:require [clojure.string :as str]
            [nihilite.eval :as ev])
  (:import [java.io File]
           [java.net InetSocketAddress Socket]
           [java.util Base64]))

(def ^:const prefix "eval:")

(def ^:private default-wait-ms 30000)

(defn- b64-decode ^String [^String s]
  (String. (.decode (Base64/getDecoder) s)))

(defn eval-request?
  "True when agentArgs carries an eval request rather than plain agent args."
  [^String args]
  (and (string? args) (.startsWith args ^String prefix)))

(defn parse-request
  "Splits agentArgs into {:transport :code}. Returns nil when args is not an
   eval request, so the caller can fall through to its previous behaviour."
  [^String args]
  (when (eval-request? args)
    (let [body (subs args (count prefix))
          cut  (.indexOf ^String body "|")]
      (when (pos? cut)
        (let [[kind & more] (str/split (subs body 0 cut) #":")]
          {:transport (case kind
                        "discard" {:kind :discard}
                        "file"    {:kind :file :path (first more)}
                        "tcp"     {:kind :tcp
                                   :host  (nth more 0 "127.0.0.1")
                                   :port  (parse-long (nth more 1 "0"))
                                   :token (nth more 2 "")}
                        {:kind :unknown :spec kind})
           :code (b64-decode (subs body (inc cut)))})))))

(defn- stream-text [snapshot stream]
  (apply str (keep (fn [e] (when (= stream (:stream e)) (:text e)))
                   (:events snapshot))))

(defn- await-done
  "Waits up to wait-ms for the session to stop running. Returns the snapshot
   either way; :running? says which happened."
  ([sid] (await-done sid default-wait-ms))
  ([sid wait-ms]
   (let [deadline (+ (System/nanoTime)
                     (.toNanos java.util.concurrent.TimeUnit/MILLISECONDS
                               (long wait-ms)))]
     (loop []
       (let [s (ev/snapshot sid)]
         (cond
           (not (:running? s)) s
           (> (System/nanoTime) deadline) s
           :else (do (Thread/sleep 10) (recur))))))))

(defn run-request!
  "Evaluates the request's code in a fresh session and returns the reply map.
   A fresh session per request keeps requests independent: two attachers, or
   one attacher asking twice, do not share a namespace."
  [{:keys [code]}]
  (let [sid (ev/open-session)]
    (try
      (ev/eval-in sid code)
      (let [s (await-done sid)]
        {:session sid
         :ns (:ns s)
         :value (:value s)
         :error (:error s)
         :out (stream-text s :out)
         :err (stream-text s :err)
         :done (not (:running? s))})
      (finally
        (ev/close-session sid)))))

(defn- send-tcp!
  [{:keys [host port token]} ^String payload]
  (with-open [sock (Socket.)]
    (.connect sock (InetSocketAddress. ^String host (int port)) 5000)
    (let [out (.getOutputStream sock)]
      (.write out (.getBytes ^String (str token "\n" payload) "UTF-8"))
      (.flush out))))

(defn reply!
  "Sends the reply through the transport. A transport that cannot be reached
   is reported on stderr rather than thrown: the eval already ran, and losing
   the reply must not turn into an agent failure."
  [{:keys [kind] :as transport} ^String payload]
  (try
    (case kind
      :discard :ok
      :file    (do (spit ^File (File. ^String (:path transport)) payload) :ok)
      :tcp     (send-tcp! transport payload)
      (do (binding [*out* *err*]
            (println "[nihilite] unknown eval transport:" (pr-str transport)))
          :unknown))
    (catch Throwable t
      (binding [*out* *err*]
        (println "[nihilite] eval reply failed:" (.getMessage ^Throwable t)))
      :failed)))

(defn handle-args!
  "Entry point for an agent's argument string. Returns nil when the args are
   not an eval request, so the caller can keep its previous behaviour."
  [^String args]
  (if-let [{:keys [transport code] :as req} (parse-request args)]
    (let [reply (assoc (run-request! req) :token (:token transport))]
      (reply! transport (pr-str reply))
      reply)
    nil))