(ns nihilite.builder.boot
  "Startup work that is not the transformer: run the init script, and serve
   an eval request that arrived on the agent args.

   This namespace used to own an embedded server and its middleware stack.
   Both are gone. Nihilite opens no port; a process that wants to evaluate
   code in an attached JVM sends an eval request (see
   nihilite.builder.eval.protocol), and a process that wants a full control plane
   starts its own service from its init script (see
   examples/nrepl_service.clj)."
  (:require [nihilite.builder.eval :as ev]
            [nihilite.builder.eval.protocol :as proto])
  (:import [java.util.concurrent.atomic AtomicBoolean]
           [java.util.logging Logger Level]))

(defonce ^:private log
  (doto (Logger/getLogger "Nihilite.Boot")
    (.setLevel Level/WARNING)))

(def ^:private init-property-name "nihilite.init")

(def ^:const init-marker-done "nihilite:init-done")
(def ^:const init-marker-failed "nihilite:init-failed")

(defonce ^:private init-ran
  (AtomicBoolean. false))

(defn- stream-text [snapshot stream]
  (apply str (keep (fn [e] (when (= stream (:stream e)) (:text e)))
                   (:events snapshot))))

(defn- await-settled
  "Waits up to timeout-ms for a session to stop running. Returns the snapshot
   either way; :running? says which happened."
  [sid timeout-ms]
  (let [deadline (+ (System/nanoTime)
                    (.toNanos java.util.concurrent.TimeUnit/MILLISECONDS timeout-ms))]
    (loop []
      (let [s (ev/snapshot sid)]
        (cond
          (not (:running? s)) s
          (> (System/nanoTime) deadline) s
          :else (do (Thread/sleep 20) (recur)))))))

(defn- eval-init!
  "Runs the form in the `nihilite.init` system property, if there is one, in
   its own eval session. Returns true when it ran without throwing.

   The property names a FORM rather than a file, matching every other knob in
   this project; a path works too because (load-file \"...\") is a form.
   There is deliberately no default form any more: the old default was
   (require 'clojure.repl), which only existed so a client connecting to the
   deleted server would have familiar bindings.

   Output is echoed to stdout as it happens rather than swallowed into the
   session. A script that installs a hook and prints a marker is debugging,
   and silent output turns that into guesswork.

   Runs at most once per JVM. Every attach calls run-startup! -- that is where
   an eval request is served -- so without the guard a script that installs
   hooks would install them again on every poll."
  []
  (if-not (.compareAndSet init-ran false true)
    (do
      (.log ^Logger log Level/FINE "init already ran in this JVM; skipping")
      true)
    (if-let [form (System/getProperty init-property-name)]
    (let [sid (ev/open-session)]
      (try
        (ev/eval-in sid form)
        (let [s   (await-settled sid 30000)
              out (stream-text s :out)
              err (stream-text s :err)]
          (when (seq out) (print out) (flush))
          (when (seq err) (binding [*out* *err*] (print err)) (flush))
          (cond
            (:error s)
            (do (.log ^Logger log Level/WARNING
                      (str "[Nihilite] init failed: " (:error s)))
                false)

            (:running? s)
            (do (.log ^Logger log Level/WARNING
                      "[Nihilite] init did not finish within 30s")
                false)

            :else true))
        (finally
          (ev/close-session sid))))
      true)))

(defn run-startup!
  "Runs the init script and then serves an eval request from the agent args.

   Args that are not an eval request are left alone, so an existing
   -javaagent or -jar invocation behaves exactly as it did. Returns the eval
   reply when one was served, nil otherwise."
  [args]
  (let [init-ok? (eval-init!)]
    (println (if init-ok? init-marker-done init-marker-failed))
    (flush)
    (proto/handle-args! args)))