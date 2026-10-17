(ns nihilite.test.eval-jar-driver
  "Jar-level coverage for the eval stack.

   Every other pass in this build -- eval_attach_driver included -- runs with
   src/main/clojure on the child classpath, so what they exercise is the
   working tree, not the artifact. The README leads with eval, and

     builder/eval/protocol.clj   7110 bytes  SOURCE
     builder/eval/session.clj   11594 bytes  SOURCE

   ship in the uberjar as source rather than AOT, so until this driver existed
   nothing in the build could load them from the released file at all. The one
   pass that put a jar on a child classpath (javaagent-driver) only hands it to
   -javaagent; the classes themselves still came from src/.

   This pass runs on the uberjar and target/test-classes and NOTHING else --
   no class-dir, no src/main/clojure, no ~/.m2 basis. The uberjar shades its
   own dependencies, so whatever it forgot is genuinely absent here instead of
   being quietly supplied from the local repository. That is the whole point:
   the same pass with the basis left on the classpath would exercise the working
   tree again and prove nothing about the release."
  (:gen-class
    :name nihilite.test.evalJarDriver
    :prefix "ejd-"
    :main true))

(defn- fail!
  [why]
  (println "evalJarDriver FAIL:" why)
  (System/exit 40))

(defn- check
  [what got want]
  (when-not (= got want)
    (fail! (str what " -- expected " (pr-str want) ", got " (pr-str got)))))

(defn- eval-ns
  "Loads nilite.builder.eval out of the uberjar and hands back the namespace.

   Required at RUN time rather than in this ns form on purpose. A :require here
   would be satisfied at AOT time -- that compilation runs with
   src/main/clojure on its classpath -- so a jar missing the eval source would
   only surface later as a bare class-loading stack trace from inside
   clojure.main, naming neither the namespace nor the cause."
  []
  (try
    (require 'nihilite.builder.eval)
    (the-ns 'nihilite.builder.eval)
    (catch Throwable t
      (fail! (str "nihilite.builder.eval is not loadable from the uberjar. That "
                  "namespace ships as source, not AOT, so the jar has to carry "
                  "nihilite/builder/eval/*.clj -- " (.getMessage t))))))

(defn- verb
  "One public fn out of `ns`, or fails naming it."
  [ns sym]
  (or (ns-resolve ns sym)
      (fail! (str sym " is missing from " (ns-name ns) " in the uberjar"))))

(defn- await-idle
  "Polls snapshot until the session stops running, the way a caller of the
   facade has to. Bounded: a session that never goes idle is a failure, not a
   hang."
  [snapshot sid]
  (loop [tried 0]
    (let [snap (snapshot sid)]
      (cond
        (not (:running? snap)) snap
        (> tried 400) (fail! (str "session never went idle, snapshot: "
                                  (pr-str snap)))
        :else (do (Thread/sleep 25)
                  (recur (inc tried)))))))

(defn ejd-main [& _args]
  (let [ns            (eval-ns)
        open-session  (verb ns 'open-session)
        close-session (verb ns 'close-session)
        session?      (verb ns 'session?)
        session-ids   (verb ns 'session-ids)
        eval-in       (verb ns 'eval-in)
        snapshot      (verb ns 'snapshot)
        before        (set (session-ids))
        sid           (open-session)]
    (check "open-session returns a session id" (boolean sid) true)
    (check "session? agrees about the new session" (session? sid) true)
    (check "the new session is listed"
           (contains? (set (session-ids)) sid) true)

    ;; A real round trip through the compiled-and-run path, not just a load.
    (eval-in sid "(+ 1 1)")
    (let [snap (await-idle snapshot sid)]
      (check "the evaluated form returned its value" (:value snap) "2")
      (check "no error was reported" (:error snap) nil)
      (check "the session recorded its output stream"
             (boolean (some #(= :out (:stream %)) (:events snap)))
             true))

    (close-session sid)
    (check "close-session makes session? false" (session? sid) false)
    (check "the session table is back to what it was before"
           (set (session-ids))
           before)
    (println "DRIVER_PASS the eval stack loads AND runs from the uberjar alone:")
    (println "          open-session / eval-in / snapshot / close-session, with")
    (println "          src/, class-dir and the m2 basis all off the classpath")
    (System/exit 0)))
