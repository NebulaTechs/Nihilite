(ns nihilite.test.prod-bootstrap-driver
  "Drives the production install! path against a bootstrap-loader target.

   The indy driver weaves java.io.FileInputStream.read with a hand-built raw
   ClassFileTransformer, which proves the mechanism. This driver goes through
   the real path instead: Agent.premain, then registry/install!, so what it
   asserts is what a user gets. It is the acceptance gate for the switch to
   invokedynamic dispatch — under the old app-loader-only rule a hook on a
   java.* method registered, reported a woven count, and never fired.

   All four positions are exercised, because \"the advice fired\" and \"each
   position survived the invokedynamic switch\" are separate claims.

   STATUS: not wired into `check`; kept as the reproducer. install! reports
   woven-count 1, pending? false, target-loader :bootstrap for every spec, and
   no NoClassDefFoundError is raised any more — the cause of that one was
   visit-return-advice and wrap-redefine still building a bare
   withCustomMapping with no .bootstrap, so those two positions emitted an
   INVOKESTATIC a bootstrap class cannot resolve. What is left is that none of
   the four advices fire: the invokedynamic call site is never linked, and it
   fails silently rather than raising a BootstrapMethodError. The bootstrap
   method's catch-and-fallback has been removed per rule #439 so that a real
   failure surfaces, and nothing surfaces here.

   Targets are java.util.BitSet methods, one instance per position. Two
   constraints shaped that choice, both learned the hard way:
   java.io.FileInputStream is on the class loading path, so the advice body
   needs to load classes which read bytes through the very method it hooked,
   and ReentrantLock.lock is used by the nREPL transport and the logging
   plumbing, so hooking it makes the agent's own machinery re-enter the
   advice. BitSet is used by neither."
  (:require [nihilite.registry :as reg])
  (:import [net.bytebuddy.agent ByteBuddyAgent]
           [java.util BitSet])
  (:gen-class
   :name nihilite.test.prodBootstrapDriver
   :prefix "pbd-"))

(def ^:private bitset-internal "java/util/BitSet")

(def ^:private fired (atom []))

(defn- fail! [why code]
  (println "prodBootstrapDriver FAIL:" why)
  (System/exit code))

(defn- want! [tag]
  (when-not (some #{tag} @fired)
    (fail! (str tag " never fired on a bootstrap method; observed "
                (pr-str (take 8 @fired)))
           3)))

(defn- install! [id method position descriptor arity action bridge]
  (reg/install! {:id              id
                 :target-internal bitset-internal
                 :method-name     method
                 :position        position
                 :arity           arity
                 :descriptor      descriptor
                 :action          action
                 :bridge          bridge
                 :note            (str "production path against a bootstrap class, "
                                     position)})
  (let [st (reg/install-status! id)]
    (println "PROD_STATUS" id (pr-str (select-keys st [:woven-count :pending?
                                                        :target-loader])))
    (when-not (= 1 (:woven-count st))
      (fail! (str id " woven-count=" (:woven-count st)
                  " for a loaded, modifiable bootstrap class") 2))
    (when (:pending? st)
      (fail! (str id " still pending after retransform") 4))))

(defn- one-bit []
  (doto (BitSet. 8) (.set 3)))

(defn pbd-main [& _args]
  (let [inst (ByteBuddyAgent/install)
        Agent (Class/forName "nihilite.kernel.Agent")
        premain (.getDeclaredMethod Agent "premain"
                                    (into-array Class [String
                                                       java.lang.instrument.Instrumentation]))]
    (.setAccessible premain true)
    (.invoke premain nil (object-array [nil inst]))

    ;; Loaded before install!, otherwise there is nothing to retransform and
    ;; the woven count would be 0 for an uninteresting reason.
    (Class/forName "java.util.BitSet")

    (install! "pbd-entry" "isEmpty" :entry "()Z" 0 :observe
              (fn [_ctx] (swap! fired conj :entry) nil))
    ;; size() and cardinality() are leaves inside BitSet; length() is not —
    ;; replacing it breaks every BitSet method that consults it.
    (install! "pbd-return" "size" :return "()I" 0 :modify
              (fn [_ctx] (swap! fired conj :return) 7))
    (install! "pbd-redefine" "cardinality" :redefine "()I" 0 :observe
              (fn [_self _args _method-name] (swap! fired conj :redefine) 99))
    (install! "pbd-throw" "get" :throw "(I)Z" 1 :observe
              (fn [_ctx] (swap! fired conj :throw) nil))

    ;; :entry — the advice fires and the original body still runs.
    (let [empty-set (BitSet. 8)]
      (want! :entry)
      (when-not (.isEmpty empty-set)
        (fail! "the original isEmpty() body did not run; :entry must instrument" 5)))

    ;; :return — the bridge's value becomes the result.
    (want! :return)
    (when-not (= 7 (.size (one-bit)))
      (fail! (str ":return did not replace the value; size="
                  (.size (one-bit)) " expected 7")
             6))

    ;; :redefine — true body replacement: the bridge decides the result and
    ;; the original body never runs.
    (want! :redefine)
    (when-not (= 99 (.cardinality (one-bit)))
      (fail! (str ":redefine did not replace the body; cardinality="
                  (.cardinality (one-bit)) " expected 99")
             7))

    ;; :throw — the advice observes the throwable and the exception still
    ;; reaches the caller.
    (let [thrown (try
                   (.get (BitSet. 8) 999)
                   nil
                   (catch Throwable t t))]
      (want! :throw)
      (when-not (instance? IndexOutOfBoundsException thrown)
        (fail! (str "expected IndexOutOfBoundsException from BitSet.get, got "
                    (pr-str thrown))
               9)))

    (println "DRIVER_PASS all four positions install and fire on"
             " bootstrap-loader classes through the production path")
    (System/exit 0)))

(defn -main [& args]
  (pbd-main args))
