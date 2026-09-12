(ns nihilite.javaagent-classpath-driver
  "Replaces src/test/java/nihilite/javaagentClasspathDriver.java.

   Runs four probes against the agent classpath, and a fifth jar-smoke
   path that spawns `java -javaagent:<jar>` and verifies the embedded
   nREPL server boots within the timeout.

   Invoked from build.clj as `java nihilite.javaagentClasspathDriver`."
  (:require [nihilite.boot :as boot]
            [clojure.string :as str]
            [nrepl.server :as nrepl.server])
  (:import [java.io ByteArrayOutputStream]
           [java.net URLClassLoader]
           [java.util.concurrent TimeUnit])
  (:gen-class
   :name nihilite.javaagentClasspathDriver
   :prefix "jacd-"
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

(defn- read-pipe-into [pipe is buf]
  (let [n (.read is buf)]
    (when (pos? n)
      (locking pipe (.write pipe buf 0 n)))))

(defn- check-markers [state snapshot-string]
  (when (and (not (:init-done? @state)) (.contains snapshot-string "init eval done"))
    (swap! state assoc :init-done? true))
  (when (and (not (:bound? @state)) (.contains snapshot-string "nREPL bencode clients may connect"))
    (swap! state assoc :bound? true)))

(defn spawn-jar-smoke [argv]
  (let [nihilite-jar (nth argv 1)
        init-script (when (> (count argv) 2) (nth argv 2))
        init-form (if (or (nil? init-script) (str/blank? init-script))
                   "(do (require 'clojure.repl) (in-ns 'user))"
                   (str "(load-file \"" (.replace init-script "\\" "\\\\") "\")"))
        cmd ["java"
             "-Djdk.attach.allowAttachSelf=true"
             "-XX:+EnableDynamicAgentLoading"
             (str "-javaagent:" nihilite-jar)
             (str "-Dnihilite.init=" init-form)
             "-Dnihilite.port=0"
             "-Dnihilite.bind=127.0.0.1"
             "-jar" nihilite-jar]
        pb (doto (ProcessBuilder. cmd) (.redirectErrorStream true))
        proc (.start pb)
        is (.getInputStream proc)
        pipe (ByteArrayOutputStream.)
        buf (byte-array 4096)
        reader (Thread.
                (fn []
                  (try
                    (loop []
                      (read-pipe-into pipe is buf)
                      (when (not= -1 (try (.read is buf) (catch Throwable _ -1)))
                        (recur)))
                    (catch Throwable _)))
                "jar-smoke-reader")
        deadline (+ (System/nanoTime) (.toNanos TimeUnit/SECONDS 45))
        state (atom {:bound? false :init-done? false})]
    (.setDaemon reader true)
    (.start reader)
    (loop []
      (when (and (< (System/nanoTime) deadline)
                 (.isAlive proc)
                 (not (and (:init-done? @state) (:bound? @state))))
        (Thread/sleep 200)
        (check-markers state (locking pipe (.toString pipe)))
        (recur)))
    (.destroyForcibly proc)
    (try (.waitFor proc 5 TimeUnit/SECONDS) (catch InterruptedException _ (.interrupt (Thread/currentThread))))
    (let [log (locking pipe (.toString pipe))]
      (if (and (:bound? @state) (:init-done? @state))
        (do
          (println "javaagentClasspathDriver: jar-smoke (nrepl server bound + init ran) OK")
          (pass!))
        (do
          (println "javaagentClasspathDriver: jar-smoke incomplete (bound=" (:bound? @state)
                   ", initDone=" (:init-done? @state) ")")
          (println "...captured log (full):\n" log)
          (fail!))))))

(defn -main [& args]
  (if (and (seq args) (= "spawn-jar-smoke" (first args)))
    (do (spawn-jar-smoke (vec args)) (System/exit 0))
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
