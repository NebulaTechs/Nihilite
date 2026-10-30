(ns nihilite.test.eval-attach-driver
  "End-to-end proof of the eval wire protocol, from another process.

   Everything else about the eval module is tested in-process. This driver
   exercises the path that actually matters and that nothing else reaches:

     VirtualMachine.attach(pid)
       .loadAgent(jar, \"eval:tcp:HOST:PORT:TOKEN|CODE-SOURCE\")
       .detach()

   and then reads the reply the target JVM sent back over the socket.

   Both code sources are covered. The inline one carries the code in the
   attach argument, which the JDK's attach protocol caps at 1024 characters
   on JDK 11 through 21, so it only works for short code. The socket one
   carries the code on its own channel, and the driver sends a payload well
   past that cap through it: that request could not be expressed at all
   before, and it is the reason the protocol has a socket code source.

   The spawned child gets -javaagent and a classpath holding only third-party
   jars, so every nihilite class it uses comes out of the agent jar rather
   than from src/main/clojure. A child that could see the source tree would
   resolve edited namespaces straight from disk and prove nothing about the
   artifact.

   The child is told to use a 1.5s eval wait, so the :done false path is
   reachable without a 30 second stall, and the driver then does what the
   protocol says an attacher does with it: poll the session, interrupt it,
   poll again, close it.

   Invoked from build.clj as `java nihilite.test.evalAttachDriver`."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream File]
           [java.net InetAddress InetSocketAddress ServerSocket]
           [java.util Base64 UUID]
           [java.util.concurrent TimeUnit]
           [com.sun.tools.attach VirtualMachine])
  (:gen-class
    :name nihilite.test.evalAttachDriver
    :main true))

(def ^:private child-wait-ms 1500)

(def ^:private failure-signatures
  "Text in the child's log that means the eval channel is broken even though
   the markers might look fine."
  [["NihiliteAdviceException" "the advice itself threw"]
   ["AgentBuilder onError" "the transformer errored"]
   ["Unmatched delimiter" "a form did not parse"]])

(def ^:private install-hook-code
  "Installs a hook over the eval channel and then writes a file through the
   hooked method, so the reply's :fired is a measurement rather than a claim.
   Returns the :fired count."
  (str "(do"
       "    (require 'nihilite.builder.api)"
       "    (nihilite.builder.api/install!"
       "     {:id \"attach-probe\""
       "      :target-internal \"java/io/FileOutputStream\""
       "      :method-name \"write\""
       "      :descriptor \"([BII)V\""
       "      :position :entry"
       "      :arity 3"
       "      :action :observe"
       "      :bridge (fn [_] nil)})"
       "    (let [f (java.io.File/createTempFile \"attach\" \".bin\")"
       "          o (java.io.FileOutputStream. f)]"
       "      (.write o (.getBytes \"nihilite\" \"UTF-8\"))"
       "      (.close o)"
       "      (.delete f))"
       "    (:fired (nihilite.builder.api/install-status! \"attach-probe\")))"))

(defn- b64 ^String [^String s]
  (.encodeToString (Base64/getEncoder) (.getBytes s "UTF-8")))

(def ^:private accept-timeout-ms
  "How long the driver waits for the target to open a channel.

   The target opens the reply channel before it answers, and the code channel
   while the attach is still in flight, so a target that never opens one is
   already broken and waiting longer only hides it."
  30000)

(def ^:private large-code
  "A payload well past the 1024-character attach argument limit.

   The attach argument used to carry the code itself, and the JDK's attach
   protocol caps one argument at 1024 characters on JDK 11 through 21, so
   this code could not be sent at all. It travels on a socket now, and its
   size is the proof."
  (str "(do"
       "    (require 'nihilite.builder.eval)"
       "    (let [pad \""
       (apply str (repeat 1500 "x"))
       "\"]"
       "      {:pad-length (count pad)}))"))

(defn- open-loopback-server!
  "Opens a server socket on the loopback interface with an ephemeral port.

   Loopback only: the channel is between this driver and the one child it
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
          (throw (ex-info "peer closed the connection before sending its token" {})))
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
  "Requires the peer's token to be the one this driver handed out."
  [expected got]
  (when-not (= expected got)
    (throw (ex-info "peer answered with the wrong token"
                    {:expected expected :got got}))))

(defn- read-reply!
  "Accepts the target's reply on the reply channel and parses it."
  [^ServerSocket server token]
  (let [sock (.accept server)
        in   (.getInputStream sock)]
    (try
      (check-token! token (read-token-line! in))
      (edn/read-string (read-all! in))
      (finally
        (.close sock)))))

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
                         (check-token! token (read-token-line! in))
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
                 "eval-attach-code-feeder")]
    (.setDaemon thread true)
    (.start thread)
    {:thread thread :outcome outcome}))

(defn- third-party-classpath
  "The driver's own classpath minus the project's own directories.

   What is left is jars -- clojure, byte-buddy, tools.logging -- which is
   exactly what the child needs to run clojure.main. Nihilite itself arrives
   through -javaagent, which appends the jar to the system class path."
  []
  (->> (str/split (System/getProperty "java.class.path")
                  (re-pattern (java.util.regex.Pattern/quote File/pathSeparator)))
       (remove (fn [entry]
                 (or (.contains entry "src/main/clojure")
                     (.contains entry "src/test/clojure")
                     (.contains entry (str "target" File/separator "classes"))
                     (.contains entry (str "target" File/separator "test-classes")))))
       (str/join File/pathSeparator)))

(defn- spawn-child [^String jar]
  (let [cmd    ["java"
                "-Djdk.attach.allowAttachSelf=true"
                "-Dnet.bytebuddy.safe=false"
                (str "-Dnihilite.eval.wait-ms=" child-wait-ms)
                (str "-javaagent:" jar)
                "-cp" (third-party-classpath)
                "clojure.main"
                ;; The main thread sleeps, which is what keeps the JVM alive
                ;; for the attach. An agent that exits when main returns
                ;; cannot be attached to.
                "-e" "(do (Thread/sleep 120000) (System/exit 0))"]
        pb     (doto (ProcessBuilder. ^java.util.List cmd)
                 (.redirectErrorStream true))
        proc   (.start pb)
        pipe   (ByteArrayOutputStream.)
        buf    (byte-array 4096)
        reader (doto (Thread.
                       ^Runnable
                       (fn []
                         (try
                           (loop []
                             (let [n (try (.read (.getInputStream proc) buf)
                                          (catch Throwable _ -1))]
                               (when (not= -1 n)
                                 (locking pipe (.write pipe buf 0 n))
                                 (recur))))
                           (catch Throwable _)))
                       "eval-attach-child-reader")
                 (.setDaemon true))]
    (.start reader)
    {:proc proc :pipe pipe}))

(defn- child-log [{:keys [pipe]}]
  (locking pipe (.toString pipe "UTF-8")))

(defn- await-marker
  "Waits for text to appear in the child's merged output. Returns the log
   either way, so the caller can print what did arrive."
  [{:keys [^Process proc] :as child} text timeout-ms]
  (let [deadline (+ (System/nanoTime)
                    (.toNanos TimeUnit/MILLISECONDS timeout-ms))]
    (loop []
      (let [log (child-log child)]
        (cond
          (.contains log text) log
          (or (> (System/nanoTime) deadline) (not (.isAlive proc))) log
          :else (do (Thread/sleep 100) (recur)))))))

(defn- eval-in-target!
  "One full attach round trip with the code inline in the attach argument.

   The reply comes back over the socket. The code travels in the attach
   argument, which the JDK caps at 1024 characters on JDK 11 through 21, so
   this form only works for short code."
  [^VirtualMachine vm ^String jar ^ServerSocket reply-server code]
  (let [token   (str (UUID/randomUUID))
        request (str "eval:tcp:127.0.0.1:" (.getLocalPort reply-server) ":" token
                     "|" (b64 code))]
    (.loadAgent vm jar request)
    (read-reply! reply-server token)))

(defn- eval-in-target-via-socket!
  "One full attach round trip with the code on its own socket.

   The attach argument carries only the two channel addresses, so the code's
   size does not enter the attach protocol at all."
  [^VirtualMachine vm ^String jar ^ServerSocket reply-server code]
  (let [reply-token (str (UUID/randomUUID))
        code-token  (str (UUID/randomUUID))
        code-server (open-loopback-server!)
        request     (str "eval:tcp:127.0.0.1:" (.getLocalPort reply-server) ":" reply-token
                         "|tcp:127.0.0.1:" (.getLocalPort code-server) ":" code-token)]
    (try
      (let [{:keys [thread outcome]} (start-code-feeder! code-server code-token code)]
        (.loadAgent vm jar request)
        (.join thread)
        (when-let [failure (:error outcome)]
          (throw failure))
        (read-reply! reply-server reply-token))
      (finally
        (.close code-server)))))

(defn- value->data
  "The reply's :value is the session's rendering of the form's value, so it
   arrives as a string. Read it back rather than treating it as data --
   (:running? \"true\") is nil, and the check would quietly always fail."
  [reply]
  (let [v (:value reply)]
    (try
      (if (string? v) (edn/read-string v) v)
      (catch Throwable _ v))))

(defn- check [label problems ok? detail]
  (println (format "evalAttachDriver: %-36s %s" label (if ok? "ok" "FAIL")))
  (when-not ok?
    (swap! problems conj (str label " -- " detail))))

(defn- run-attach-checks!
  "Every attach round trip, over one VM attach. Split out of -main so the
   nesting stays shallow: at seven levels a single stray paren closed the
   inner try, and the compiler reported it as an unresolved finally rather
   than as the paren error it was."
  [^VirtualMachine vm ^String jar ^ServerSocket reply-server problems]
  (let [ask (fn [code] (eval-in-target! vm jar reply-server code))]
    ;; 1. the plain round trip
    (let [r (ask "(+ 1 2)")]
      (check "eval returns a value" problems
             (= "3" (:value r)) (pr-str r))
      (check "eval reports done" problems
             (true? (:done r)) (pr-str r)))

    ;; 2. stdout of the evaluated code comes back
    (let [r (ask "(do (println \"hello-from-target\") :ok)")]
      (check "eval captures *out*" problems
             (str/includes? (str (:out r)) "hello-from-target")
             (pr-str r)))

    ;; 3. a throw is reported, not propagated into the attach
    (let [r (ask "(throw (ex-info \"boom\" {:a 1}))")]
      (check "eval reports a thrown error" problems
             (str/includes? (str (:error r)) "boom")
             (pr-str r)))

    ;; 4. a request that outlives the wait replies :done false and leaves the
    ;;    session alive. That handle is the entire point of the reply.
    (let [r   (ask "(do (Thread/sleep 60000) :late)")
          sid (:session r)]
      (check "unfinished eval reports :done false" problems
             (false? (:done r)) (pr-str r))
      (check "unfinished eval returns a session" problems
             (string? sid) (pr-str r))

      ;; 5. poll it from a second attach
      (let [s1 (ask (str "(nihilite.builder.eval/snapshot \"" sid "\")"))]
        (check "polled session is still running" problems
               (true? (:running? (value->data s1))) (pr-str s1)))

      ;; 6. interrupt it
      (let [i (ask (str "(nihilite.builder.eval/interrupt \"" sid "\")"))]
        (check "interrupt reports it interrupted" problems
               (str/includes? (str (:value i)) ":interrupted? true")
               (pr-str i)))

      ;; 7. and it stopped
      (Thread/sleep 300)
      (let [s2 (ask (str "(nihilite.builder.eval/snapshot \"" sid "\")"))]
        (check "interrupted session stopped running" problems
               (false? (:running? (value->data s2))) (pr-str s2)))

      ;; 8. and the attacher can close it
      (let [c (ask (str "(nihilite.builder.eval/close-session \"" sid "\")"))]
        (check "attacher can close the session" problems
               (= "true" (:value c)) (pr-str c))))

    ;; 9. a hook installed from the eval channel really fires
    (let [r (ask install-hook-code)
          fired (parse-long (str (:value r)))]
      (check "hook installed over the eval channel fires" problems
             (and fired (pos? fired))
             (pr-str (select-keys r [:value :error]))))

    ;; 10. a payload past the attach argument limit travels on the socket.
    ;;     This request could not be expressed before: the code used to ride
    ;;     in the attach argument, and the JDK drops an argument longer than
    ;;     1024 characters without a word.
    (check "the socket payload really is past 1024 characters" problems
           (> (count large-code) 1024)
           (str "payload was " (count large-code) " characters"))
    (let [r (eval-in-target-via-socket! vm jar reply-server large-code)]
      (check "socket code source carries a payload past 1024 chars" problems
             (= {:pad-length 1500} (value->data r))
             (pr-str (select-keys r [:value :error]))))))

(defn- report! [child problems]
  (let [log (child-log child)
        seen (filterv (fn [[sig _]] (.contains log sig)) failure-signatures)]
    (when (seq seen)
      (swap! problems conj
             (str "child log carried " (str/join ", " (map first seen)))))
    (println "evalAttachDriver: child log tail:\n"
             (subs log (max 0 (- (count log) 8000))))
    (if (seq @problems)
      (do
        (println "evalAttachDriver: FAILED")
        (doseq [p @problems] (println "  problem --" p))
        (System/exit 1))
      (do
        (println (str "DRIVER_PASS eval-attach: attach + eval round trip, out capture,"
                      " error capture, :done false, poll, interrupt, close, and a hook"
                      " installed and fired over the channel"))
        (System/exit 0)))))

(defn -main [& args]
  (let [jar      (or (first args) "target/nihilite.jar")
        jar-file (File. ^String jar)
        problems (atom [])
        reply-server (open-loopback-server!)]
    (when-not (.exists jar-file)
      (println "evalAttachDriver: jar not found:" jar)
      (System/exit 2))
    (let [{:keys [^Process proc] :as child} (spawn-child jar)]
      (try
        (let [log (await-marker child "nihilite:init-done" 60000)]
          (when-not (.contains log "nihilite:init-done")
            (swap! problems conj "child never reached init-done")
            (println "...child log:\n" log)))
        ;; str, not the raw long: Process.pid returns a long and attach takes
        ;; a String, so passing it directly reflects to "no matching method".
        (let [vm (VirtualMachine/attach (str (.pid proc)))]
          (try
            ;; A throw here must still reach report!, or the child's log --
            ;; the only place the target's side of the failure appears -- is
            ;; never printed.
            (try
              (run-attach-checks! vm jar reply-server problems)
              (catch Throwable t
                (swap! problems conj (str "attach checks threw: " (.getMessage t)))
                (println "evalAttachDriver: attach checks threw"
                         (.getMessage t))))
            (finally (.detach vm))))
        (finally
          (.destroyForcibly proc)
          (try (.waitFor proc 5 TimeUnit/SECONDS)
               (catch InterruptedException _ (.interrupt (Thread/currentThread))))))
      ;; after the finally, not inside it: one finally per try, and report!
      ;; exits the process so it has to be the last form anyway.
      (report! child problems))))
