(ns nihilite.test.javaagent-classpath-driver
  "Replaces src/test/java/nihilite/javaagentClasspathDriver.java.

   Runs four probes against the agent classpath, and a fifth jar-smoke
   path that spawns `java -javaagent:<jar>` and drives the embedded nREPL
   server from the outside.

   The jar-smoke path is the only place a hook runs under a real agent in a
   real jar deployment, so it gates on four things, not two:

     - the server bound (`nihilite:server-ready`)
     - the init form evaluated (`nihilite:init-done`)
     - the hook fired (`nihilite:hook-fired N`, N > 0)
     - install-status! reports the same firing (`:fired` > 0), so the
       runtime counter is wired to dispatch and not just incremented by the
       probe's own atom
     - nothing in the child's log reported `observer threw` or
       `AgentBuilder onError`

   The last two exist because the hook was registered, init ran, the server
   came up, and nothing checked that the bridge ever completed.
   `observer threw` is the signature of that failure, so it is a failure
   marker rather than something a human has to notice.

   Invoked from build.clj as `java nihilite.test.javaagentClasspathDriver`."
  (:require [nihilite.boot :as boot]
            [clojure.string :as str]
            [nrepl.server :as nrepl.server])
  (:import [java.io ByteArrayOutputStream]
           [java.net URLClassLoader]
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

(defn run-nrepl-misc-probe []
  (try
    (let [sys (ClassLoader/getSystemClassLoader)]
      (println "javaagentClasspathDriver: system loader class =" (.getName (class sys)))
      (println "javaagentClasspathDriver: system loader is URLClassLoader?"
               (instance? URLClassLoader sys))
      (println "javaagentClasspathDriver: nrepl/misc.clj via system ="
               (.getResource sys "nrepl/misc.clj"))
      (require 'nrepl.misc)
      (println "javaagentClasspathDriver: require nrepl.misc OK")
      (pass!))
    (catch Throwable t
      (println "javaagentClasspathDriver: require nrepl.misc FAILED")
      (log-cause-chain t)
      (fail!))))

(defn run-require-nrepl-server-probe []
  (try
    (require 'nrepl.server)
    (println "javaagentClasspathDriver: require nrepl.server OK")
    (pass!)
    (catch Throwable t
      (println "javaagentClasspathDriver: require nrepl.server FAILED")
      (log-cause-chain t)
      (fail!))))

(defn run-worker-equivalent-probe []
  (let [worker-error (atom nil)
        worker-done (atom false)
        worker-lock (Object.)
        worker-thread (Thread.
                       (fn []
                         (try
                           (require 'nihilite.registry)
                           (require 'nihilite.boot)
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
      (require 'nihilite.boot)
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

(defn run-boot-then-start-server-probe []
  (try
    (require 'nrepl.server)
    (let [handle (boot/start!)]
      (try
        (println "javaagentClasspathDriver: nihilite.boot/start! OK")
        (pass!)
        (finally
          (try (nrepl.server/stop-server handle) (catch Throwable _)))))
    (catch Throwable t
      (println "javaagentClasspathDriver: nihilite.boot/start! FAILED")
      (log-cause-chain t)
      (fail!))))

(defn- check-markers [state snapshot-string]
  (when (and (not (:init-done? @state))
             (or (.contains snapshot-string "nihilite:init-done")
                 (.contains snapshot-string "nihilite:init-failed")))
    (swap! state assoc :init-done? true))
  (when (and (not (:bound? @state))
             (.contains snapshot-string "nihilite:server-ready"))
    (swap! state assoc :bound? true))
  (when-let [[_ n] (re-find #"nihilite:hook-fired\s+(\d+)" snapshot-string)]
    (swap! state assoc :hook-fires (Integer/parseInt n)))
  (when-let [[_ n] (re-find #"nihilite:status-fired\s+(\d+)" snapshot-string)]
    (swap! state assoc :status-fires (Integer/parseInt n))))

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

(def ^:private hook-firing-probe
  "Init tail that installs a hook through the production install! path on
   java.io.FileOutputStream.write([BII)V and writes a file, then prints how
   many times the advice ran. The bridge also calls registry/ctx-return, the
   one call site that used a defrecord field accessor Clojure 1.12 does not
   emit.

   The target is a method the agent's own logging and the JVM's class loading
   both drive, so this doubles as a reentrancy smoke test: if the advice path
   re-entered itself through the hooked method, the count would explode or the
   JVM would die before printing. It is NOT on the class loading path -- see
   the README's Limits section for what happens when a hook is put there."
  (str
   "(require 'nihilite.api)"
   "(require 'nihilite.registry)"
   "(let [n (atom 0) f (java.io.File/createTempFile \"nihilite-probe\" \".bin\")]"
   "  (nihilite.api/install!"
   "   {:id \"jar-smoke-probe\""
   "    :target-internal \"java/io/FileOutputStream\""
   "    :method-name \"write\""
   "    :descriptor \"([BII)V\""
   "    :position :entry"
   "    :arity 3"
   "    :action :observe"
   "    :note \"jar-smoke driver: does the advice run in a real agent JVM\""
   "    :bridge (fn [ctx] (swap! n inc) (nihilite.registry/ctx-return ctx))})"
   "  (spit f \"nihilite\")"
   "  (.delete f)"
   "  (println \"nihilite:hook-fired\" @n)"
   "  (let [st (nihilite.api/install-status! \"jar-smoke-probe\")]"
   "    (println \"nihilite:status-fired\" (:fired st))))"))

(defn- host-java-major []
  (Integer/parseInt (first (re-seq #"\d+" (System/getProperty "java.version")))))

(defn spawn-jar-smoke [argv]
  (let [nihilite-jar (nth argv 1)
        init-script (when (> (count argv) 2) (nth argv 2))
        init-form (if (or (nil? init-script) (str/blank? init-script))
                    (str "(do " hook-firing-probe ")")
                    (str "(do (load-file \""
                         (.replace init-script "\\" "\\\\")
                         "\") " hook-firing-probe ")"))
        cmd (vec (concat ["java"
                          "-Djdk.attach.allowAttachSelf=true"
                          "-Dnet.bytebuddy.safe=false"]
                         ;; JEP 451, JDK 21+ only; older JVMs refuse to start with it.
                         (when (>= (host-java-major) 21) ["-XX:+EnableDynamicAgentLoading"])
                         [(str "-javaagent:" nihilite-jar)
                          (str "-Dnihilite.init=" init-form)
                          "-Dnihilite.port=0"
                          "-Dnihilite.bind=127.0.0.1"
                          "-jar" nihilite-jar]))
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
         deadline (+ (System/nanoTime) (.toNanos TimeUnit/SECONDS 45))
         state (atom {:bound? false :init-done? false :hook-fires 0 :status-fires 0})]
    (.setDaemon reader true)
    (.start reader)
    (loop []
      (when (and (< (System/nanoTime) deadline)
                 (.isAlive proc)
                 (not (and (:init-done? @state) (:bound? @state)
                           (pos? (long (:hook-fires @state))))))
        (Thread/sleep 200)
        (check-markers state (locking pipe (.toString pipe)))
        (recur)))
    (.destroyForcibly proc)
    (try (.waitFor proc 5 TimeUnit/SECONDS) (catch InterruptedException _ (.interrupt (Thread/currentThread))))
    (let [log (locking pipe (.toString pipe))
          errors (log-failures log)
          fires (:hook-fires @state)]
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

        (not (and (:bound? @state) (:init-done? @state)))
        (do
          (println "javaagentClasspathDriver: jar-smoke incomplete (bound=" (:bound? @state)
                   ", initDone=" (:init-done? @state) ", hookFires=" fires ")")
          (println "...captured log (full):\n" log)
          (fail!))

        (not (pos? (long fires)))
        (do
          (println "javaagentClasspathDriver: jar-smoke installed the probe hook but it never fired"
                   "(hookFires=" fires ")")
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
                   "(server bound, init evaluated, hook fired" fires
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
          (println "DRIVER_PASS jar-smoke: nrepl server bound + init ran + a"
                   "production-path hook fired with no bridge or transformer errors")
          (System/exit 0))
        (do
          (println "DRIVER_FAIL jar-smoke: pass=" @pass-count " fail=" @fail-count)
          (System/exit 1))))
    (do
      (run-nrepl-misc-probe)
      (run-require-nrepl-server-probe)
      (run-worker-equivalent-probe)
      (run-boot-then-start-server-probe)
      (if (and (zero? @fail-count) (= 4 @pass-count))
        (do (println "DRIVER_PASS javaagent classpath: 4/4 probes OK")
            (System/exit 0))
        (do (println "DRIVER_FAIL javaagent classpath: pass=" @pass-count " fail=" @fail-count)
            (System/exit 1))))))
