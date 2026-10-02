(ns nihilite.test.prod-bootstrap-driver
  "Drives the production install! path against bootstrap-loader targets.

   The indy driver weaves java.io.FileInputStream.read with a hand-built raw
   ClassFileTransformer, which proves the mechanism. This driver goes through
   the real path instead: Agent.premain, then registry/install!, so what it
   asserts is what a user gets. It is the acceptance gate for the invokedynamic
   switch — under the old app-loader-only rule a hook on a java.* method
   registered, reported a woven count, and never fired.

   All four positions are exercised, because \"the advice fired\" and \"each
   position survived the switch\" are separate claims.

   Targets for the four-position matrix are java.util.BitSet methods, one
   instance per position. Three constraints shaped that choice: BitSet.length
   cannot be replaced because other BitSet methods consult it, and the matrix
   needs one target per position with independent bodies. ReentrantLock.lock
   is deliberately absent even though it would work -- it is used by the nREPL
   transport, so hooking it makes the agent's own machinery re-enter the
   advice.

   The last section hooks java.io.FileInputStream.read on purpose: that is the
   class loading path, and it is where a reentrancy guard earns its keep. It
   was avoided here until the guard's behaviour on it was measured rather than
   assumed."
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
    ;; The :redefine advice dispatches through this. Without it the advice runs,
    ;; finds its spec, and returns the default value instead of the bridge's.
    ((requiring-resolve 'nihilite.registry.dispatch/install-redefine-dispatcher!))

    ;; Loaded before install!, otherwise there is nothing to retransform and
    ;; the woven count would be 0 for an uninteresting reason.
    (Class/forName "java.util.BitSet")

    (install! "pbd-entry" "isEmpty" :entry "()Z" 0 :observe
              (fn [_ctx] (swap! fired conj :entry) nil))
    ;; The :modify bridges return int, since the target returns int.
    (install! "pbd-return" "size" :return "()I" 0 :modify
              (fn [_ctx] (swap! fired conj :return) (int 7)))
    (install! "pbd-redefine" "cardinality" :redefine "()I" 0 :observe
              (fn [_self _args _method-name]
                (swap! fired conj :redefine)
                (int 99)))
    (install! "pbd-throw" "get" :throw "(I)Z" 1 :observe
              (fn [_ctx] (swap! fired conj :throw) nil))

    (println "PROD_STATUS" (pr-str (select-keys (reg/install-status! "pbd-entry")
                                              [:woven-count :pending? :target-loader])))

    ;; Each position calls the hooked method first and only then checks the
    ;; counter: the advice cannot have fired before the call happens.
    ;; :entry — the advice fires and the original body still runs.
    (let [still-empty (.isEmpty (BitSet. 8))]
      (want! :entry)
      (when-not still-empty
        (fail! "the original isEmpty() body did not run; :entry must instrument" 5)))

    ;; :return — the bridge's value becomes the result.
    (let [sized (.size (one-bit))]
      (want! :return)
      (when-not (= 7 sized)
        (fail! (str ":return did not replace the value; size=" sized " expected 7") 6)))

    ;; :redefine — true body replacement: the bridge decides the result and
    ;; the original body never runs.
    (let [card (.cardinality (one-bit))]
      (want! :redefine)
      (when-not (= 99 card)
        (fail! (str ":redefine did not replace the body; cardinality=" card
                    " expected 99")
               7)))

    ;; :throw — the advice observes the throwable and the exception still
    ;; reaches the caller. Only a negative index is out of range; a positive
    ;; one past the end reads as false and never throws.
    (let [thrown (try
                   (.get (BitSet. 8) -1)
                   nil
                   (catch Throwable t t))]
      (want! :throw)
      (when-not (instance? IndexOutOfBoundsException thrown)
        (fail! (str "expected IndexOutOfBoundsException from BitSet.get(-1), got "
                    (pr-str thrown))
               9)))

    ;; Collision: the class loading path, hooked on purpose.
     ;;
     ;; java.io.FileInputStream.read is how the JVM turns bytes into a Class, so
     ;; an advice on it runs while the JVM is loading -- and the advice body needs
     ;; classes of its own (the spec lookup, the bridge). The bridge below
     ;; re-enters the hooked method the way class loading would, which is the
     ;; tightest form of that cycle: same thread, same method, inside the advice.
     ;;
     ;; This section is a MEASUREMENT, not an assertion, and it is deliberately
     ;; not a gate. Across identical runs on one JDK the nested count came back
     ;; both 0 and 1, so the guard's behaviour here depends on whether the advice
     ;; path happened to be warm: a bridge-driven re-entry is always cut (put
     ;; `(if false ::reentered ...)` in place of the check and this reports
     ;; nested > 0), but a re-entry that arrives through the advice's own class
     ;; loading is not reliably cut, because that cycle closes in the generated
     ;; stub's per-call Var.intern -- above the guard, before hk-onEntry runs.
     ;;
     ;; The guard's deterministic coverage lives in the retransform driver,
     ;; which re-enters an ordinary target. Gating on this one would mean
     ;; gating on a coin flip.
     (let [depth  (atom 0)
           outer  (atom 0)
           nested (atom 0)
           reentry-file (doto (java.io.File/createTempFile "nihilite-reentry" ".bin")
                          (.deleteOnExit))
           bridge (fn [_ctx]
                    (let [d (swap! depth inc)]
                      (try
                        (if (= d 1)
                          (do (swap! outer inc)
                              (with-open [in (java.io.FileInputStream. reentry-file)]
                                (let [ba (byte-array 8)]
                                  (.read in ba 0 8))))
                          (swap! nested inc))
                        (finally
                          (swap! depth dec)))))
           buf (byte-array 8)]
      (spit reentry-file "01234567")
      (reg/install! {:id              "pbd-reentry"
                     :target-internal "java/io/FileInputStream"
                     :method-name     "read"
                     :descriptor      "([BII)I"
                     :position        :entry
                     :arity           3
                     :action          :observe
                     :bridge          bridge
                     :note            "class-loading-path re-entrancy, measured"})
      (with-open [in (java.io.FileInputStream. reentry-file)]
        (.read in buf 0 8))
      (when-not (= "01234567" (String. buf "UTF-8"))
        (fail! "the hooked read did not return the file's bytes" 12))
      (println "REENTRY_STATUS" (pr-str {:outer  @outer
                                         :nested @nested
                                         :note   "measurement only; nested varies 0 or 1 across runs"}))
      (reg/uninstall! "pbd-reentry"))

    (println "DRIVER_PASS all four positions install and fire on"
             " bootstrap-loader classes through the production path")
    (System/exit 0)))

(defn -main [& args]
  (pbd-main args))
