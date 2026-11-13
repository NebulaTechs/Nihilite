(ns nihilite.test.javaagent-classpath-driver
  "The jar-smoke pass: spawns a child JVM with the packaged agent mounted
   through -javaagent:, and gates on what the child reports.

   The child runs in a fresh temporary working directory, so the driver can
   also prove the agent left the host application's directory untouched.

   Gated on:

     - the init form evaluated (`nihilite:init-done`)
     - a hook fired (`nihilite:hook-fired N`, N > 0)
     - install-status! reports the same firing (`:fired` > 0), so the
       runtime counter is wired to dispatch and not just incremented by the
       probe's own atom
     - nothing in the child's log reported `observer threw` or
       `AgentBuilder onError`
     - the child's WORKING DIRECTORY was left untouched. It runs in a
       fresh temp directory, and the agent must not write a single file
       into it: the six classes it generates at mount time are kept in
       memory and read from there, so the `target/classes` directory it
       used to save them into -- a relative path, resolved against the
       application's own directory -- was never needed. A JVM whose
       working directory is not writable could not mount the agent at all.

   The jar-smoke path no longer waits on a server binding, because there is no
   server: `java -jar` runs the init script and returns, so the child's output
   is read to EOF and then asserted on. That also makes the gate stronger --
   it now proves init ran on the Main-Class path with no socket in sight.

   `observer threw` is the signature of a hook whose bridge never completed,
   so it is a failure marker rather than something a human has to notice.

   THE IN-PROCESS PROBES THIS DRIVER USED TO CARRY ARE GONE. It had three:
   the system classloader is a URLClassLoader, a worker-equivalent thread
   can require registry + dispatch + eval concurrently with the main thread
   doing the same, and an eval request round-trips through the real protocol
   code. None of them ever ran: build.clj passes `spawn-jar-smoke` as the
   first argument, so -main took that branch and the probes were
   unreachable from `check` and from every build task. Dead coverage reads
   like live coverage in the file, and the two things they claimed to prove
   are covered elsewhere: the eval protocol over a socket by
   eval-attach-driver, and the worker/dispatcher require race by
   prod-bootstrap-driver.

   Invoked from build.clj as `java nihilite.test.javaagentClasspathDriver`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit])
  (:gen-class
    :name nihilite.test.javaagentClasspathDriver
    :main true))

(def pass-count (atom 0))
(def fail-count (atom 0))

(defn pass! [] (swap! pass-count inc))
(defn fail! [] (swap! fail-count inc))

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

(defn- absolute-path
  "The path as the CHILD has to see it.

   The jar-smoke child runs with a working directory of its own, so a
   relative path would resolve against that directory instead of the
   project root -- and the init script would not be found."
  [path]
  (.getAbsolutePath (io/file path)))

(defn- host-java-major []
  (Integer/parseInt (first (re-seq #"\d+" (System/getProperty "java.version")))))

(defn- absolute-classpath
  "The driver's own classpath with every entry made absolute.

   The jar-smoke child runs with a working directory of its own, so the
   driver can prove the agent does not write into the application's
   directory. A relative classpath entry would resolve against THAT
   directory instead of the project root, and the child would not find
   Nihilite at all."
  []
  (let [sep File/pathSeparator
        pattern (re-pattern (java.util.regex.Pattern/quote sep))]
    (->> (str/split (System/getProperty "java.class.path") pattern)
         (map (fn [^String entry]
                (try (.getCanonicalPath (File. entry))
                     (catch Throwable _ entry))))
         (str/join sep))))

(defn- fresh-working-directory ^File []
  (.toFile (Files/createTempDirectory "nihilite-jar-smoke"
                                     (make-array FileAttribute 0))))

(defn- agent-written-directories
  "What the agent left behind in the working directory it was mounted in.

   The agent generates six classes at mount time and used to save them to
   `target/classes` -- a RELATIVE path, so it resolved against the working
   directory of whatever JVM mounted it. That put six .class files into the
   host application's directory on every mount, and a JVM whose working
   directory was not writable could not mount the agent at all: ByteBuddy's
   saveIn threw out of premain and the JVM aborted with exit 134.

   The bytes are kept in memory (bytegen/generated-class-bytes) and the
   transformer reads them from there, so nothing on disk was ever needed."
  [^File work-dir]
  (let [target (io/file work-dir "target")]
    (when (.exists target)
      (->> (file-seq target)
           (filter #(.isFile ^File %))
           (mapv #(str (.getName ^File %)))))))

(defn spawn-jar-smoke [argv]
  (let [nihilite-jar (nth argv 1)
        init-script (when (> (count argv) 2) (nth argv 2))
        ;; load-file only loads the namespace; it does not call -main. Without
        ;; the explicit call the fixture loads, prints nothing, and the hook is
        ;; never installed -- which reads as "the hook never fired".
        q        (fn [path] (str "  (load-file \"" (.replace (absolute-path path) "\\" "\\\\") "\")"))
        fixture  (str "(do\n" (q jar-smoke-fixture) "\n  (probe.jar-smoke/-main))")
        init-form (if (or (nil? init-script) (str/blank? init-script))
                    fixture
                    (str "(do\n" (q init-script) "\n  (load-file \""
                         (.replace (absolute-path jar-smoke-fixture) "\\" "\\\\")
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
                          "-cp" (absolute-classpath)
                          "clojure.main"
                          "-e" "(do (Thread/sleep 60000) (System/exit 0))"]))
        work-dir (fresh-working-directory)
        pb (doto (ProcessBuilder. cmd)
             (.redirectErrorStream true)
             (.directory work-dir))
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
          fires  (:hook-fires @state)
          written (agent-written-directories work-dir)]
      (cond
        (seq written)
        (do
          (println "javaagentClasspathDriver: the agent wrote into the working"
                   "directory it was mounted in:" (pr-str written))
          (println "  the agent must keep the host application's directory"
                   "untouched: the generated classes are kept in memory and"
                   "read from there, so nothing on disk was ever needed")
          (println "...captured log (full):\n" log)
          (fail!))

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
  (if-not (and (seq args) (= "spawn-jar-smoke" (first args)))
    (do
      (println "javaagentClasspathDriver: usage: spawn-jar-smoke <jar> [init-script]")
      (System/exit 1))
    (do
      (spawn-jar-smoke (vec args))
      (if (and (zero? @fail-count) (pos? @pass-count))
        (do
          (println "DRIVER_PASS jar-smoke: init ran with no server + a"
                   "production-path hook fired with no bridge or transformer errors")
          (System/exit 0))
        (do
          (println "DRIVER_FAIL jar-smoke: pass=" @pass-count " fail=" @fail-count)
          (System/exit 1))))))
