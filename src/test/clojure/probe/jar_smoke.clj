(ns probe.jar-smoke
  "Init script for the jar-smoke driver: installs a hook through the
   production install! path, writes a file so the hook has something to
   observe, and prints two markers the driver asserts on.

   java.io.FileOutputStream.write([BII)V is the target on purpose. It is on
   the agent's own logging and class-loading path, so this doubles as a
   reentrancy smoke test: if the advice re-entered itself through the hooked
   method the count would explode or the JVM would die before printing. It is
   NOT on the class loading path -- see the README's Limits section for what
   happens when a hook is put there.

   The bridge calls registry/ctx-return, the one call site that used a
   defrecord field accessor Clojure 1.12 does not emit."
  (:require [nihilite.api :as api]
            [nihilite.registry :as reg]))

(def ^:private fires (atom 0))

(defn install! []
  (api/install!
   {:id "jar-smoke-probe"
    :target-internal "java/io/FileOutputStream"
    :method-name "write"
    :descriptor "([BII)V"
    :position :entry
    :arity 3
    :action :observe
    :note "jar-smoke driver: does the advice run in a real agent JVM"
    :bridge (fn [ctx]
              (swap! fires inc)
              (reg/ctx-return ctx))}))

(defn- write-target-directly!
  "Writes through java.io.FileOutputStream explicitly rather than via spit.

   The driver runs this inside an eval session, where *out* is bound to the
   session writer, so spit alone stopped exercising the hook: nothing in the
   init script reached FileOutputStream any more except spit, and clojure.io
   buffers a small string before flushing it. Going at the stream directly
   makes the trigger deterministic."
  []
  (let [f   (java.io.File/createTempFile "nihilite-probe" ".bin")
        fos (java.io.FileOutputStream. f)]
    (.write fos (.getBytes "nihilite" "UTF-8"))
    (.flush fos)
    (.close fos)
    (.delete f)))

(defn- burst-real-stdout!
  "Prints to the process's actual stdout, in a loop.

   System.out is a FileOutputStream on fd 1 even when fd 1 is a pipe, so this
   drives write([BII)V from the agent's own output path -- the reentrancy
   smoke test. If the advice re-entered itself through the hooked method the
   count would explode or the JVM would die before the markers print.

   System/out is used directly rather than *out*, because the driver runs init
   inside an eval session where *out* is the session writer, not the process's
   stdout -- and writing to the session writer would not exercise the hook at
   all."
  [n]
  (dotimes [i n]
    (.println ^java.io.PrintStream System/out (str "jar-smoke-stdout-burst " i))
    (.flush ^java.io.PrintStream System/out)))

(defn -main [& _]
  (install!)
  (write-target-directly!)
  (burst-real-stdout! 5)
  ;; Each marker is one string on purpose. println of two arguments is three
  ;; writes, and another thread's output lands between them -- which is how a
  ;; marker ends up in the log as hook-fired320 with no separator.
  (println (str "nihilite:hook-fired " @fires))
  (flush)
  (let [st (api/install-status! "jar-smoke-probe")]
    (println (str "nihilite:status-fired " (:fired st)))
    (println (str "nihilite:status-woven " (:woven-count st)
                  " pending=" (:pending? st)
                  " loader=" (:target-loader st)))
    (println (str "nihilite:probe-done fires=" @fires)))
  (flush))