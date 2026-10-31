(ns nihilite.builder.eval.protocol
  "The wire format an attaching process uses to evaluate code in this JVM.

   VirtualMachine.loadAgent takes a single String and returns void, so that
   String is the only way in and there is no way back. This namespace turns
   it into a usable channel by replying through one of two transports, and
   by keeping the protocol narrow enough that an attacher can implement it
   in any language:

   ```text
   eval:TRANSPORT|CODE-SOURCE

   # TRANSPORT is one of
   discard              # run it, reply nowhere
   tcp:HOST:PORT:TOKEN  # reply written to a socket, TOKEN echoed on the
                        # first line so a concurrent attacher can tell its
                        # own replies apart

   # CODE-SOURCE is one of
   BASE64-CODE          # the code itself, Base64 of its UTF-8 bytes
   tcp:HOST:PORT:TOKEN  # the code read from that socket. The target
                        # connects, echoes TOKEN on the first line, then
                        # reads the code until the attacher closes its
                        # half of the connection.
   ```

   agentArgs without the eval: prefix is not an eval request, so an existing
   -javaagent or -jar invocation keeps behaving exactly as before.

   The reply is one EDN map with these keys:

   ```clojure
   {:token     ;; echoed from the transport, for matching
    :session   ;; session id, usable for a follow-up poll
    :ns        ;; namespace the code ran in
    :value     ;; printed value of the last non-nil form
    :error     ;; printed exception, or nil
    :out       ;; everything written to *out*
    :err       ;; everything written to *err*
    :done      ;; false when the wait expired with the eval still running
   }
   ```

   A :done of false means the session is still live: poll it by sending a
    second request whose code is (snapshot SESSION-ID). Polling costs one
   attach round trip per read, which is the price of loadAgent having no
   return channel. Anything wanting cheaper reads can start a user-supplied
   control plane from its own init script.

   The two directions are symmetric: a socket can carry the code in and the
   reply out. The socket form for the code is not a nicety. The attach
   protocol allows at most 1024 characters in one request argument on JDK 11
   through 21 -- AttachOperation::arg_length_max, checked by the target in
   AttachOperation::RequestReader::read_request -- and the JDK drops an
   oversized request without a word, so the attaching side sees only a
   Premature EOF. Code that travels over a socket keeps the request's size
   independent of the code's size, and a socket exists on every platform the
   JVM runs on, Android included, so nothing has to be written to shared
   storage first."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [nihilite.builder.eval :as ev])
  (:import [java.net InetSocketAddress Socket]
           [java.util Base64]))

(def ^:const prefix "eval:")

(def ^:const wait-ms-property "nihilite.eval.wait-ms")

(def ^:private default-wait-ms
  "How long one request waits for its eval before replying :done false.

   Settable so a test -- or someone driving a JVM they cannot restart -- can
   make the poll path reachable without waiting half a minute."
  (or (some-> (System/getProperty wait-ms-property) str/trim not-empty
              parse-long)
      30000))

(defn- b64-decode ^String [^String s]
  (String. (.decode (Base64/getDecoder) s)))

(defn eval-request?
  "True when agentArgs carries an eval request rather than plain agent args."
  [^String args]
  (and (string? args) (.startsWith args ^String prefix)))

(defn- parse-code-source
  "Parses the CODE-SOURCE half of a request.

   BASE64-CODE is the code itself, carried inline. tcp:HOST:PORT:TOKEN means
   the code is read from that socket instead. Base64's alphabet cannot
   contain a colon, so the two forms can never be confused."
  [^String code-source]
  (if (.startsWith code-source "tcp:")
    (let [[_ host port token] (str/split code-source #":" 4)]
      {:kind :tcp
       :host  (or host "127.0.0.1")
       :port  (parse-long (or port "0"))
       :token (or token "")})
    {:kind :inline :code (b64-decode code-source)}))

(defn parse-request
  "Splits agentArgs into {:transport SPEC :code-source SPEC}. Returns nil when
   args is not an eval request, so the caller can fall through to its previous
   behaviour.

   The two halves are parsed independently. TRANSPORT says where the reply
   goes, CODE-SOURCE says where the code comes from, and a request is free to
   use a different mechanism for each -- a socket for the code in, a socket
   for the reply out.

   Parsing does no I/O: the socket form is read later, by read-code!."
  [^String args]
  (when (eval-request? args)
    (let [body (subs args (count prefix))
          cut  (.indexOf ^String body "|")]
      (when (pos? cut)
        (let [code-source (subs body (inc cut))
              ;; Four parts at most, so a TOKEN that contains a colon keeps it
              ;; instead of being cut short. The code source parses the same way.
              [kind & more] (str/split (subs body 0 cut) #":" 4)]
          {:transport (case kind
                        "discard" {:kind :discard}
                        "tcp"     {:kind :tcp
                                   :host  (nth more 0 "127.0.0.1")
                                   :port  (parse-long (nth more 1 "0"))
                                   :token (nth more 2 "")}
                        {:kind :unknown :spec kind})
           :code-source (parse-code-source code-source)})))))

(defn- read-tcp-code!
  "Reads the code from the attacher's socket.

   The target connects, writes the token on the first line so the attacher
   can check it is talking to the agent that answered its request, and then
   reads until the attacher closes its half of the connection. The size of
   the code is bounded by nothing but the socket, which is the whole point:
   the attach argument it arrived in has a hard 1024-character limit on JDK
   11 through 21."
  [{:keys [host port token]}]
  (with-open [sock (Socket.)]
    (.connect sock (InetSocketAddress. ^String host (int port)) 5000)
    (let [out (.getOutputStream sock)]
      (.write out (.getBytes ^String (str token "\n") "UTF-8"))
      (.flush out))
    (let [in  (.getInputStream sock)
          buf (byte-array 8192)]
      (loop [sb (StringBuilder.)]
        (let [n (.read in buf)]
          (if (neg? n)
            (.toString sb)
            (recur (.append sb (String. buf 0 n "UTF-8")))))))))

(defn read-code!
  "Resolves the CODE-SOURCE half of a request into the code string.

   Split out from parse-request because parsing is pure and the socket form
   has to do I/O. An unknown code source throws: the request asked for
   something this agent cannot provide, and silently evaluating nothing
   would look like a successful no-op."
  [{:keys [kind] :as source}]
  (case kind
    :inline (:code source)
    :tcp    (read-tcp-code! source)
    (throw (ex-info "unknown eval code source" {:code-source source}))))

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
   one attacher asking twice, do not share a namespace.

   The session is closed on the way out ONLY when the eval finished. When it
   did not, the session is left live on purpose: :done false plus a :session
   id is the attacher's handle for coming back, and closing it here would kill
   the polling loop the reply describes. The attacher owns that session from
   then on and should close it when finished."
  [{:keys [code]}]
  (let [sid (ev/open-session)
        s   (do (ev/eval-in sid code)
                (await-done sid))
        done (not (:running? s))
        reply {:session sid
               :ns (:ns s)
               :value (:value s)
               :error (:error s)
               :out (stream-text s :out)
               :err (stream-text s :err)
               :done done}]
    (when done
      (ev/close-session sid))
    reply))

(defn- send-tcp!
  [{:keys [host port token]} ^String payload]
  (with-open [sock (Socket.)]
    (.connect sock (InetSocketAddress. ^String host (int port)) 5000)
    (let [out (.getOutputStream sock)]
      (.write out (.getBytes ^String (str token "\n" payload) "UTF-8"))
      (.flush out))))

(defn reply!
  "Sends the reply through the transport. A transport that cannot be REACHED
   is logged rather than thrown: the eval already ran, and losing the reply
   must not turn into an agent failure. The client sees the loss as a
   timeout, which is a signal it can act on.

   A transport name this protocol does not implement is a different thing:
   that is a malformed request, and the client is about to wait for a reply
   that can never come. It is raised, so the attacher fails now instead of
   timing out later.

   These two diagnostics go through the logging system rather than *err*
   because they are Nihilite's own failures, not the evaluated code's
   output -- the user's configured backend is where an agent problem
   belongs, and a bare println to *err* is invisible to anyone who
   redirected logging. Logging also flushes, which a bare *err* println
   does not reliably do before System/exit."
  [{:keys [kind] :as transport} ^String payload]
  (case kind
    :discard :ok
    :tcp     (try
               (send-tcp! transport payload)
               (catch Throwable t
                 (log/error t "eval reply failed")
                 :failed))
    (throw (ex-info "unknown eval transport" {:transport transport}))))

(defn handle-args!
  "Entry point for an agent's argument string. Returns nil when the args are
   not an eval request, so the caller can keep its previous behaviour."
  [^String args]
  (if-let [{:keys [transport code-source]} (parse-request args)]
    (let [code  (read-code! code-source)
          reply (assoc (run-request! {:code code}) :token (:token transport))]
      (reply! transport (pr-str reply))
      reply)
    nil))
