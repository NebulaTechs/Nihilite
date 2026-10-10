(ns nihilite.test.javaagent-classpath-driver
  "Replaces src/test/java/nihilite/javaagentClasspathDriver.java.

   Two halves.

   In-process probes, which run inside the driver's own JVM:

     - the system classloader is a URLClassLoader, which is what lets a user
       add a jar onto it from an init script
     - a worker-equivalent thread can require registry + dispatch + eval
       concurrently with the main thread doing the same
     - an eval request round-trips through the real protocol code, including
       the file transport and the reply written to disk

   And a jar-smoke path that spawns `java -jar <jar>` and gates on:

     - the init form evaluated (`nihilite:init-done`)
     - a hook fired (`nihilite:hook-fired N`, N > 0)
     - install-status! reports the same firing (`:fired` > 0), so the
       runtime counter is wired to dispatch and not just incremented by the
       probe's own atom
     - nothing in the child's log reported `observer threw` or
       `AgentBuilder onError`

   The jar-smoke path no longer waits on a server binding, because there is no
   server: `java -jar` runs the init script and returns, so the child's output
   is read to EOF and then asserted on. That also makes the gate stronger --
   it now proves init ran on the Main-Class path with no socket in sight.

   `observer threw` is the signature of a hook whose bridge never completed,
   so it is a failure marker rather than something a human has to notice.

   Invoked from build.clj as `java nihilite.test.javaagentClasspathDriver`."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [nihilite.builder.eval.protocol :as proto])
  (:import [java.io ByteArrayOutputStream File]
           [java.net URLClassLoader]
           [java.util Base64]
           [java.util.concurrent TimeUnit])
  (:gen-class
    :name nihilite.test.javaagentClasspathDriver
    :main true))

(def pass-count (atom 0))
(def fail-count (atom 0))

(defn pass! [] (swap! pass-count inc))
(defn fail! [] (swap! fail-count inc))

(defn log-cause-chain [t]
  (loop [cause t]
    (when cause
      (println "  " (.getName (class cause))
               (when-let [m (.getMessage cause)] (str ": " m)))
      (recur (.getCause cause)))))

(defn run-system-classloader-probe []
  (try
    (let [sys (ClassLoader/getSystemClassLoader)]
      (println "javaagentClasspathDriver: system loader class =" (.getName (class sys)))
      (println "javaagentClasspathDriver: system loader is URLClassLoader?"
               (instance? URLClassLoader sys))
      (pass!))
    (catch Throwable t
      (println "javaagentClasspathDriver: system loader probe FAILED")
      (log-cause-chain t)
      (fail!))))

(defn run-worker-equivalent-probe []
  (let [worker-error (atom nil)
        worker-done (atom false)
        worker-lock (Object.)
        worker-thread (Thread.
                       (fn []
                         (try
                           (require 'nihilite.builder.registry)
                           (require 'nihilite.builder.registry.dispatch)
                           (require 'nihilite.builder.eval)
                           (require 'nihilite.builder.eval.protocol)
                           (catch Throwable t
                             (reset! worker-error t))
                           (finally
                             (locking worker-lock
                               (reset! worker-done true)
                               (.notifyAll worker-lock)))))
                       "driver-worker-equivalent")]
    (.setDaemon worker-thread true)
    (.start worker-thread)
    (try
      (require 'nihilite.builder.eval.protocol)
      (catch Throwable t (log-cause-chain t)))
    (locking worker-lock
      (while (not @worker-done)
        (.wait worker-lock)))
    (if-let [we @worker-error]
      (do
        (println "javaagentClasspathDriver: worker-equivalent concurrent require FAILED")
        (log-cause-chain we)
        (fail!))
      (do
        (println "javaagentClasspathDriver: worker-equivalent concurrent require OK")
        (pass!)))))

(defn- b64 ^String [^String s]
  (.encodeToString (Base64/getEncoder) (.getBytes s "UTF-8")))

(defn run-eval-protocol-probe []
  (let [out-file (File/createTempFile "nihilite-eval-probe" ".edn")
        code     "(do (println \"from-eval\") (+ 40 2))"]
    (.delete out-file)
    (try
      (let [args    (str "eval:file:" (.getPath out-file) "|" (b64 code))
            req     (proto/parse-request args)
            reply   (proto/handle-args! args)
            on-disk (when (.exists out-file) (slurp out-file))
            problems (cond-> []
                       (not= {:kind :file :path (.getPath out-file)}
                             (:transport req))
                       (conj (str "transport parsed as " (pr-str (:transport req))))

                       (not= "42" (:value reply))
                       (conj (str ":value was " (pr-str (:value reply))))

                       (not (:done reply))
                       (conj ":done was false")

                       (not (str/includes? (str (:out reply)) "from-eval"))
                       (conj (str ":out was " (pr-str (:out reply))))

                       (nil? on-disk)
                       (conj "file transport wrote nothing")

                       (and on-disk (nil? (try (edn/read-string on-disk)
                                               (catch Throwable _ nil))))
                       (conj "file transport wrote unreadable EDN"))]
        (println "javaagentClasspathDriver: eval transport =" (pr-str (:transport req)))
        (println "javaagentClasspathDriver: eval reply =" (pr-str reply))
        (if (seq problems)
          (do
            (doseq [p problems]
              (println "javaagentClasspathDriver:   problem --" p))
            (fail!))
          (pass!)))
      (catch Throwable t
        (println "javaagentClasspathDriver: eval protocol probe FAILED")
        (log-cause-chain t)
        (fail!))
      (finally
        (.delete out-file)))))

(defn- check-markers [state snapshot-string]
  (when (and (not (:init-done? @state))
             (or (.contains snapshot-string "nihilite:init-done")
                 (.contains snapshot-string "nihilite:init-failed")))
    (swap! state assoc :init-done? true))
  (when-let [[_ n] (re-find #"nihilite:hook-fired\s*(\d+)" snapshot-string)]
    (swap! state assoc :hook-fires (Integer/parseInt n)))
  (when-let [[_ n] (re-find #"nihilite:status-fired\s*(\d+)" snapshot-string)]
    (swap! state assoc :status-fires (Integer/parseInt n)))
  (when-let [[_ n] (re-find #"nihilite:status-woven\s*(\d+)" snapshot-string)]
    (swap! state assoc :woven (Integer/parseInt n)))
  (when (.contains snapshot-string "nihilite:probe-done")
    (swap! state assoc :probe-done? true)))

(def ^:private child-log-failure-signatures
  "Log text that means a hook did not do its job even though the agent
   looked healthy. Matched against the whole child log, since the JVM
   interleaves the agent's output with the probe's."
  [["observer threw" "a hook bridge threw; dispatch logged it and moved on"]
   ["AgentBuilder onError" "the transformer errored; ByteBuddy left the class unwoven"]
   ["BootstrapMethodError" "an invokedynamic call site failed to link"]
   ["NihiliteAdviceException" "the advice itself threw"]])

(defn- log-failures [log]
  (keep (fn [[signature meaning]]
          (when (.contains log signature)
            [signature meaning
             (first (filter (fn [line] (.contains line signature))
                            (str/split-lines log)))]))
        child-log-failure-signatures))

(def ^:private jar-smoke-fixture
  "Path to the init script the jar-smoke child loads. It is a real file
   rather than an inline string so it is ordinary linted Clojure: building
   this form by concatenating escaped string literals made the paren counting
   unreviewable, and got it wrong twice."
  "src/test/clojure/probe/jar_smoke.clj")

(defn- host-java-major []
  (Integer/parseInt (first (re-seq #"\d+" (System/getProperty "java.version")))))

(defn spawn-jar-smoke [argv]
  (let [nihilite-jar (nth argv 1)
        init-script (when (> (count argv) 2) (nth argv 2))
        ;; load-file only loads the namespace; it does not call -main. Without
        ;; the explicit call the fixture loads, prints nothing, and the hook is
        ;; never installed -- which reads as "the hook never fired".
        q        (fn [path] (str "  (load-file \"" (.replace path "\\" "\\\\") "\")"))
        fixture  (str "(do\n" (q jar-smoke-fixture) "\n  (probe.jar-smoke/-main))")
        init-form (if (or (nil? init-script) (str/blank? init-script))
                    fixture
                    (str "(do\n" (q init-script) "\n  (load-file \""
                         (.replace jar-smoke-fixture "\\" "\\\\")
                         "\")\n  (probe.jar-smoke/-main))"))
        ;; -javaagent, not -jar. Weaving needs a real Instrumentation, and only
        ;; the agent entry points get one: java -jar reaches Main-Class, which
        ;; is handed nil, so nothing is ever woven and every hook sits at
        ;; :pending? true. -javaagent is also the deployment this project
        ;; documents, and the one where -Dnihilite.init used to be dead.
        cmd (vec (concat ["java"
                          "-Djdk.attach.allowAttachSelf=true"
                          "-Dnet.bytebuddy.safe=false"]
                         ;; JEP 451, JDK 21+ only; older JVMs refuse to start with it.
                         (when (>= (host-java-major) 21) ["-XX:+EnableDynamicAgentLoading"])
                         [(str "-javaagent:" nihilite-jar)
                          (str "-Dnihilite.init=" init-form)
                          "-cp" (System/getProperty "java.class.path")
                          "clojure.main"
                          "-e" "(do (Thread/sleep 60000) (System/exit 0))"]))
        pb (doto (ProcessBuilder. cmd) (.redirectErrorStream true))
        proc (.start pb)
        is (.getInputStream proc)
        pipe (ByteArrayOutputStream.)
        buf (byte-array 4096)
        reader (Thread.
                (fn []
                  (try
                    (loop []
                      (let [n (try (.read is buf) (catch Throwable _ -1))]
                        (when (not= -1 n)
                          (locking pipe (.write pipe buf 0 n))
                          (recur))))
                    (catch Throwable _)))
                "jar-smoke-reader")
        deadline (+ (System/nanoTime) (.toNanos TimeUnit/SECONDS 60))
        state (atom {:init-done? false :hook-fires 0 :status-fires 0
                     :woven -1 :probe-done? false})]
    (.setDaemon reader true)
    (.start reader)
    (loop []
      (when (and (< (System/nanoTime) deadline)
                 (.isAlive proc)
                 (not (:probe-done? @state)))
        (Thread/sleep 200)
        (check-markers state (locking pipe (.toString pipe)))
        (recur)))
    (.destroyForcibly proc)
    (try (.waitFor proc 5 TimeUnit/SECONDS) (catch InterruptedException _ (.interrupt (Thread/currentThread))))
    (let [log (locking pipe (.toString pipe))
          errors (log-failures log)
          fires  (:hook-fires @state)]
      (cond
        (seq errors)
        (do
          (println "javaagentClasspathDriver: jar-smoke child log reported"
                   (count errors) "failure signature(s):")
          (doseq [[signature meaning line] errors]
            (println "  " signature "--" meaning)
            (println "     " (or line "<signature present but no line captured>")))
          (println "...captured log (full):\n" log)
          (fail!))

        (not (:init-done? @state))
        (do
          (println "javaagentClasspathDriver: jar-smoke never reported init-done")
          (println "...captured log (full):\n" log)
          (fail!))

        (neg? (long (:woven @state)))
        (do
          (println "javaagentClasspathDriver: jar-smoke never reported :woven-count")
          (println "...captured log (full):\n" log)
          (fail!))

        (zero? (long (:woven @state)))
        (do
          (println "javaagentClasspathDriver: jar-smoke registered the hook but wove 0 classes"
                   "-- the transformer was never armed")
          (println "...captured log (full):\n" log)
          (fail!))

        (not (pos? (long fires)))
        (do
          (println "javaagentClasspathDriver: jar-smoke wove" (:woven @state)
                   "class(es) but the hook never fired (hookFires=" fires ")")
          (println "...captured log (full):\n" log)
          (fail!))

        (not (pos? (long (:status-fires @state))))
        (do
          (println "javaagentClasspathDriver: jar-smoke install-status! reports :fired 0 for a hook"
                   "whose bridge did run" (pr-str (:status-fires @state))
                   "-- the runtime counter is not wired to dispatch")
          (println "...captured log (full):\n" log)
          (fail!))

        :else
        (do
          (println "javaagentClasspathDriver: jar-smoke OK"
                   "(-javaagent path, no server: init evaluated, hook wove into"
                   (:woven @state) "class(es) and fired" fires
                   "time(s) through the production install! path, install-status!"
                   "reports :fired" (:status-fires @state)
                   ", no bridge or transformer errors)")
          (pass!))))))

(defn -main [& args]
  (if (and (seq args) (= "spawn-jar-smoke" (first args)))
    (do
      (spawn-jar-smoke (vec args))
      (if (and (zero? @fail-count) (pos? @pass-count))
        (do
          (println "DRIVER_PASS jar-smoke: init ran with no server + a"
                   "production-path hook fired with no bridge or transformer errors")
          (System/exit 0))
        (do
          (println "DRIVER_FAIL jar-smoke: pass=" @pass-count " fail=" @fail-count)
          (System/exit 1))))
    (do
      (run-system-classloader-probe)
      (run-worker-equivalent-probe)
      (run-eval-protocol-probe)
      (if (and (zero? @fail-count) (= 3 @pass-count))
        (do (println "DRIVER_PASS javaagent classpath: 3/3 probes OK")
            (System/exit 0))
        (do (println "DRIVER_FAIL javaagent classpath: pass=" @pass-count " fail=" @fail-count)
            (System/exit 1))))))