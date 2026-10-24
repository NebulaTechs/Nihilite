(ns nihilite.test.examples-driver
  "Runs examples in isolated child JVMs. Init mode loads an example and probe
   through nihilite.init; attach mode runs a dynamic-attach example with the
   target PID and agent jar."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]
           [java.util.concurrent LinkedBlockingQueue TimeUnit])
  (:gen-class
    :name nihilite.test.examplesDriver
    :main true))

(def ^:private child-timeout-ms 60000)
(def ^:private init-done-marker "nihilite:init-done")
(def ^:private target-ready-marker "nihilite:examples-target-ready")

(def ^:private failure-signatures
  ["init-failed" "NihiliteAdviceException" "AgentBuilder onError"
   "observer threw" "BootstrapMethodError"])

(def ^:private project-classpath-entries
  ["src/main/clojure" "src/test/clojure" "target/classes" "target/test-classes"])

(defn- canonical-path [^File file]
  (.getCanonicalPath file))

(defn- absolute-existing-file! [path kind]
  (let [file (File. ^String path)]
    (when-not (.isFile file)
      (throw (ex-info (str kind " file does not exist: " path) {:path path})))
    (canonical-path file)))

(defn- dependency-classpath [uberjar]
  (let [excluded (into #{(canonical-path (File. ^String uberjar))}
                       (map #(canonical-path (File. ^String %)))
                       project-classpath-entries)
        separator File/pathSeparator
        entries (str/split (System/getProperty "java.class.path")
                            (re-pattern (java.util.regex.Pattern/quote separator)))]
    (->> entries
         (map #(canonical-path (File. ^String %)))
         (remove excluded)
         (filter #(and (.isFile (File. ^String %))
                       (.endsWith (.getName (File. ^String %)) ".jar")))
         distinct
         (str/join separator))))

(defn- failure-signature [text]
  (some #(when (str/includes? text %) %) failure-signatures))

(defn- read-child-output! [^Process proc lines log reader-error]
  (try
    (with-open [reader (io/reader (.getInputStream proc))]
      (loop []
        (if-let [line (.readLine reader)]
          (do (locking log (.append log (str line "\n")))
              (.put lines line)
              (recur))
          (.put lines ::eof))))
    (catch Throwable failure
      (reset! reader-error failure))
    (finally (.put lines ::eof))))

(defn- start-child [command]
  (let [pb (doto (ProcessBuilder. ^java.util.List command)
             (.redirectErrorStream true))
        proc (.start pb)
        lines (LinkedBlockingQueue.)
        log (StringBuilder.)
        reader-error (atom nil)
        reader (doto (Thread. ^Runnable
                              (fn [] (read-child-output! proc lines log reader-error))
                              "examples-driver-reader")
                 (.setDaemon true))]
    (.start reader)
    {:proc proc :reader reader :lines lines :log log
     :reader-error reader-error
     :stdin (.getOutputStream proc)}))

(defn- child-log [{:keys [log]}]
  (locking log (.toString log)))

(defn- close-child-input! [{:keys [^java.io.OutputStream stdin]}]
  (try
    (.close stdin)
    (catch Throwable _)))

(defn- cleanup-child! [{:keys [^Process proc ^Thread reader stdin]}]
  (close-child-input! {:stdin stdin})
  (try
    (.destroyForcibly proc)
    (catch Throwable _))
  (try
    (.waitFor proc 5 TimeUnit/SECONDS)
    (catch Throwable _))
  (try
    (.join reader 1000)
    (catch Throwable _)))

(defn- await-markers [child markers deadline-ns]
  (let [found (atom #{})]
    (loop []
      (cond
        (= markers @found)
        {:status :ok :found @found}

        (>= (System/nanoTime) deadline-ns)
        {:status :timeout :found @found}

        :else
        (let [remaining (max 0 (- deadline-ns (System/nanoTime)))
              line (.poll (:lines child) remaining TimeUnit/NANOSECONDS)]
          (cond
            (nil? line)
            {:status :timeout :found @found}

            (= ::eof line)
            {:status :eof :found @found}

            (failure-signature line)
            {:status :failure :found @found :signature (failure-signature line)}

            (contains? markers line)
            (do
              (swap! found conj line)
              (recur))

            :else
            (recur)))))))

(defn- wait-for-exit [^Process proc deadline-ns]
  (let [remaining (max 0 (- deadline-ns (System/nanoTime)))]
    (if (and (pos? remaining)
             (.waitFor proc remaining TimeUnit/NANOSECONDS))
      {:status :exited :exit-code (.exitValue proc)}
      {:status :timeout :exit-code nil})))

(defn- run-marked-child! [command markers]
  (let [child (start-child command)
        deadline (+ (System/nanoTime)
                    (.toNanos TimeUnit/MILLISECONDS child-timeout-ms))]
    (try
      (let [marker-result (await-markers child markers deadline)
            _ (close-child-input! child)
            exit-result (wait-for-exit (:proc child) deadline)
            _ (when (= :exited (:status exit-result))
                (.join (:reader child) 1000))
            log (child-log child)]
        (assoc child
               :marker-result marker-result
               :exit-result exit-result
               :reader-error @(:reader-error child)
               :log-text log))
      (finally
        (cleanup-child! child)))))

(defn- require-child-success! [result markers]
  (let [{:keys [marker-result exit-result log-text reader-error]} result
        marker-status (:status marker-result)
        exit-code (:exit-code exit-result)
        found (:found marker-result)
        bad-signatures (filterv #(str/includes? log-text %) failure-signatures)]
    (when reader-error
      (throw (ex-info (str "child output reader failed: "
                           (class reader-error) ": "
                           (.getMessage ^Throwable reader-error))
                      {:log log-text
                       :reader-error (str reader-error)})))
    (when (seq bad-signatures)
      (throw (ex-info (str "child output contained failure signatures: "
                           (str/join ", " bad-signatures))
                      {:log log-text :signatures bad-signatures})))
    (when-not (= :ok marker-status)
      (throw (ex-info (str "child did not emit every required marker; status="
                           marker-status " found=" (pr-str found))
                      {:log log-text :found found :status marker-status})))
    (when-not (= markers found)
      (throw (ex-info (str "child markers did not match exactly; expected="
                           (pr-str markers) " got=" (pr-str found))
                      {:log log-text :found found})))
    (when-not (= 0 exit-code)
      (throw (ex-info (str "child exited with status " exit-code)
                      {:log log-text :exit-code exit-code})))))

(defn- init-form [example-file probe-file]
  (str "(do\n"
       "  (load-file " (pr-str example-file) ")\n"
       "  (load-file " (pr-str probe-file) "))"))

(defn- init-command [jar dependency-cp example-file probe-file]
  ["java" "-Djdk.attach.allowAttachSelf=true" "-Dnet.bytebuddy.safe=false"
   (str "-Dnihilite.init=" (init-form example-file probe-file))
   (str "-javaagent:" jar) "-cp" dependency-cp "clojure.main"
   "-e" "(do (read-line) (System/exit 0))"])

(defn- target-command [dependency-cp]
  ["java" "-cp" dependency-cp "clojure.main"
   "-e" (str "(do (println " (pr-str target-ready-marker) ")"
              " (flush) (read-line))")])

(defn- attach-command [dependency-cp example-file pid jar]
  ["java"
   "-cp" dependency-cp
   "clojure.main"
   example-file
   (str pid)
   jar])

(defn- valid-label? [label]
  (and (not (str/blank? label))
       (not (str/includes? label "\n"))
       (not (str/includes? label "\r"))))

(defn- run-init! [jar-path example-path probe-path label]
  (let [jar (absolute-existing-file! jar-path "jar")
        example-file (absolute-existing-file! example-path "example")
        probe-file (absolute-existing-file! probe-path "probe")
        dependency-cp (dependency-classpath jar)
        pass-marker (str "nihilite:example-pass " label)]
    (when-not (valid-label? label)
      (throw (ex-info "example label must be non-blank and single-line"
                      {:label label})))
    (let [result (run-marked-child!
                  (init-command jar dependency-cp example-file probe-file)
                  #{init-done-marker pass-marker})]
      (require-child-success! result #{init-done-marker pass-marker})
      (println "DRIVER_PASS examples init:" label))))

(defn- run-attach! [jar-path example-path label]
  (let [jar (absolute-existing-file! jar-path "jar")
        example-file (absolute-existing-file! example-path "attach example")
        dependency-cp (dependency-classpath jar)
        pass-marker (str "nihilite:example-pass " label)
        _ (when (str/blank? dependency-cp)
            (throw (ex-info "attach mode requires dependency jars on the driver classpath"
                            {})))
        _ (when-not (valid-label? label)
            (throw (ex-info "example label must be non-blank and single-line"
                            {:label label})))
        target (start-child (target-command dependency-cp))
        deadline (+ (System/nanoTime)
                    (.toNanos TimeUnit/MILLISECONDS child-timeout-ms))]
    (try
      (let [ready (await-markers target #{target-ready-marker} deadline)]
        (when-not (= :ok (:status ready))
          (throw (ex-info (str "attach target did not become ready; status="
                               (:status ready))
                          {:log (child-log target)})))
        (let [result (run-marked-child!
                      (attach-command dependency-cp example-file (.pid (:proc target)) jar)
                      #{pass-marker})
              combined-log (str (:log-text result) "\n" (child-log target))
              bad-signatures (filterv #(str/includes? combined-log %) failure-signatures)]
          (when (seq bad-signatures)
            (throw (ex-info (str "attach output contained failure signatures: "
                                 (str/join ", " bad-signatures))
                            {:log combined-log :signatures bad-signatures})))
          (require-child-success! result #{pass-marker})
          (println "DRIVER_PASS examples attach:" label)))
      (finally
        (cleanup-child! target)))))

(defn -main [& args]
  (try
    (case (first args)
      "init"
      (do
        (when-not (= 5 (count args))
          (throw (ex-info "usage: init <jar> <example-file> <probe-file> <label>"
                          {:args args})))
        (run-init! (nth args 1) (nth args 2) (nth args 3) (nth args 4)))

      "attach"
      (do
        (when-not (= 4 (count args))
          (throw (ex-info "usage: attach <jar> <example-file> <label>"
                          {:args args})))
        (run-attach! (nth args 1) (nth args 2) (nth args 3)))

      (throw (ex-info (str "usage: init <jar> <example-file> <probe-file> <label>"
                           "\n   or: attach <jar> <example-file> <label>")
                      {:args args})))
    (catch Throwable t
      (println "examplesDriver FAIL:" (.getMessage t))
      (when-let [log (:log (ex-data t))]
        (println "--- child output ---")
        (println log))
      (flush)
      (System/exit 1))))
