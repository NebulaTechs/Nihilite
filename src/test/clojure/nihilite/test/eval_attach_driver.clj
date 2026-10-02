(ns nihilite.test.eval-attach-driver
  "End-to-end proof of the eval wire protocol, from another process.

   Everything else about the eval module is tested in-process. This driver
   exercises the path that actually matters and that nothing else reaches:

     VirtualMachine.attach(pid)
       .loadAgent(jar, \"eval:file:<reply>|<base64-code>\")
       .detach()

   and then reads the reply the target JVM wrote to disk.

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
           [java.util Base64]
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
       "    (require 'nihilite.api)"
       "    (nihilite.api/install!"
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
       "    (:fired (nihilite.api/install-status! \"attach-probe\")))"))

(defn- b64 ^String [^String s]
  (.encodeToString (Base64/getEncoder) (.getBytes s "UTF-8")))

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

(defn- reply-file ^File []
  (doto (File/createTempFile "nihilite-attach-reply" ".edn")
    (.deleteOnExit)))

(defn- eval-in-target!
  "One full attach round trip. Sends code, waits for loadAgent to return, and
   returns the reply the target wrote.

   The reply file is removed first and asserted present after, so a request
   that silently wrote nothing is a failure rather than a stale read."
  [^VirtualMachine vm ^String jar ^File reply code]
  (.delete reply)
  (.loadAgent vm jar (str "eval:file:" (.getPath reply) "|" (b64 code)))
  (when-not (.exists reply)
    (throw (ex-info "target wrote no reply file" {:code code})))
  (edn/read-string (slurp reply)))

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
  [^VirtualMachine vm ^String jar ^File reply problems]
  (let [ask (fn [code] (eval-in-target! vm jar reply code))]
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
      (let [s1 (ask (str "(nihilite.eval/snapshot \"" sid "\")"))]
        (check "polled session is still running" problems
               (true? (:running? (value->data s1))) (pr-str s1)))

      ;; 6. interrupt it
      (let [i (ask (str "(nihilite.eval/interrupt \"" sid "\")"))]
        (check "interrupt reports it interrupted" problems
               (str/includes? (str (:value i)) ":interrupted? true")
               (pr-str i)))

      ;; 7. and it stopped
      (Thread/sleep 300)
      (let [s2 (ask (str "(nihilite.eval/snapshot \"" sid "\")"))]
        (check "interrupted session stopped running" problems
               (false? (:running? (value->data s2))) (pr-str s2)))

      ;; 8. and the attacher can close it
      (let [c (ask (str "(nihilite.eval/close-session \"" sid "\")"))]
        (check "attacher can close the session" problems
               (= "true" (:value c)) (pr-str c))))

    ;; 9. a hook installed from the eval channel really fires
    (let [r (ask install-hook-code)
          fired (parse-long (str (:value r)))]
      (check "hook installed over the eval channel fires" problems
             (and fired (pos? fired))
             (pr-str (select-keys r [:value :error]))))))

(defn- report! [child reply problems]
  (let [log (child-log child)
        seen (filterv (fn [[sig _]] (.contains log sig)) failure-signatures)]
    (when (seq seen)
      (swap! problems conj
             (str "child log carried " (str/join ", " (map first seen)))))
    (println "evalAttachDriver: child log tail:\n"
             (subs log (max 0 (- (count log) 1200))))
    (.delete reply)
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
        reply    (reply-file)]
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
              (run-attach-checks! vm jar reply problems)
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
      (report! child reply problems))))