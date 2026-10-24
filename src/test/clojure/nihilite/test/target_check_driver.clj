(ns nihilite.test.target-check-driver
  "Characterisation driver: install! now refuses a spec whose target method
   does not exist.

   WHY THIS DRIVER EXISTS. The registry validated the SHAPE of a spec: the
   descriptor had to be a well-formed JVM method descriptor, the arity had
   to agree with it, the action and the position had to be legal. Shape is
   not existence. A spec naming a method that is not there registered
   successfully, reported a woven count, and never fired -- the
   \"registers but never fires\" mode docs/hook-limits.md documents, reached
   by a route the reader would not expect because everything about the spec
   looked right.

   install! now asks the JVM whether the method is there before the spec
   is registered, using the loaded-class list the Instrumentation already
   holds. It never loads a class to find out: resolving a name means
   loading the class, and a hook installed to observe a class-loading path
   re-enters its own advice when the verification does it.

   WHAT IS ASSERTED.

     1. A spec naming a method that does not exist is rejected, with
        :nihilite/target-method-not-found, and NOTHING is registered --
        lookup returns nil and no status record claims a hook.

     2. A spec naming a method that exists with a different descriptor is
        rejected, with :nihilite/target-descriptor-mismatch, and nothing
        is registered.

     3. A spec naming a method that does exist is accepted, as before.

     4. A spec naming a class that is not loaded is ACCEPTED. The target
        may load later, and a hook for a class that has not arrived yet is
        a legitimate thing to install. A check that rejected it would
        narrow the deployment surface, which is the opposite of what the
        check is for.

     5. The check does not register a hook as a side effect: a rejected
        install leaves the registry exactly as it was.

   The first three are the point. The fourth is the boundary that keeps
   the check from being a regression, and it is why the check reads the
   loaded-class list rather than resolving the name.

   Driver shape follows nihilite.test.weaving-contract-driver: a real
   Instrumentation, the production registry/install! path, assertions on
   what the registry accepted rather than on a woven count, fail! +
   System/exit with a distinct non-zero code per assertion family, and
   println+flush after every measurement so a crash still leaves the
   numbers in the log."
  (:require [nihilite.builder.api :as api]
            [nihilite.builder.registry :as reg]
            [nihilite.builder.registry.dispatch])
  (:import [net.bytebuddy.agent ByteBuddyAgent])
  (:gen-class
    :name nihilite.test.targetCheckDriver
    :prefix "tcd-"
    :main true))

(def ^:private target-internal "java/util/BitSet")
(def ^:private method-name "size")
(def ^:private descriptor "()I")

(defn- fail!
  [why code]
  (println "DRIVER_FAIL target-check:" why)
  (flush)
  (System/exit code))

(defn- spec
  [id method-name descriptor]
  {:id id
   :target-internal target-internal
   :method-name method-name
   :descriptor descriptor
   :position :entry
   :action :observe
   :bridge (fn [_ctx] nil)})

(defn- kind-of
  "The :nihilite/kind of the ex-info a call threw, or nil if it threw
   something else."
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      (:nihilite/kind (ex-data e)))
    (catch Throwable t
      (str "not-an-ex-info: " (.getName (class t))))))

(defn- assert-rejected!
  [label spec expected-kind]
  (let [kind (kind-of #(api/install! spec))]
    (when-not (= expected-kind kind)
      (fail! (str label ": expected " expected-kind ", got " (pr-str kind)) 10))
    ;; Nothing may be registered after a rejection. lookup answers from
    ;; the registry, so a nil here means the spec never got in.
    (when (some? (reg/lookup (:id spec)))
      (fail! (str label ": the spec was registered despite being rejected") 11))
    (when (:registered? (reg/install-status! (:id spec)))
      (fail! (str label ": the status record still claims the spec is registered") 12))
    (println "  ASSERT rejected " label " with" expected-kind
             "and nothing was registered")
    (flush)))

(defn- assert-accepted!
  [label spec]
  (let [result (try
                 (api/install! spec)
                 (catch Throwable t
                   (fail! (str label ": install! threw "
                               (.getName (class t)) ": " (.getMessage t)) 20)))]
    (when-not (true? result)
      (fail! (str label ": install! returned " (pr-str result)
                  ", expected true for a new id") 21))
    (when (nil? (reg/lookup (:id spec)))
      (fail! (str label ": install! returned true but the spec is not in the registry") 22))
    (println "  ASSERT accepted " label)
    (flush))
  (reg/clear!)
  nil)

(defn tcd-main [& _args]
  (let [inst (ByteBuddyAgent/install)
        Agent (Class/forName "nihilite.trainer.Agent")
        premain (.getDeclaredMethod Agent "premain"
                                    (into-array Class [String
                                                       java.lang.instrument.Instrumentation]))]
    (.setAccessible premain true)
    (.invoke premain nil (object-array [nil inst])))
  (try
    ;; The target has to be loaded for the check to have anything to read.
    ;; BitSet is in the JDK and is loaded in any Clojure runtime, but
    ;; forName makes the ordering explicit rather than incidental.
    (Class/forName "java.util.BitSet")
    (catch Throwable t
      (fail! (str "could not load the target class: " (.getMessage t)) 30)))
  (println "DRIVER_SETUP target=" target-internal
           " method=" method-name
           " descriptor=" descriptor
           " -- the target class is loaded, so the check has a class list to read")
  (flush)
  ;; The method that exists and the descriptor it really has.
  (assert-accepted! "the real method"
                    (spec "tcd-ok" method-name descriptor))
  ;; A method that is not there.
  (assert-rejected! "a method that does not exist"
                    (spec "tcd-absent" "noSuchMethod" "()I")
                    :nihilite/target-method-not-found)
  ;; A method that is there, with a descriptor it does not have.
  (assert-rejected! "a descriptor the method does not have"
                    (spec "tcd-wrong-desc" method-name "()J")
                    :nihilite/target-descriptor-mismatch)
  ;; A class that is not loaded. The check must let it through: the target
  ;; may load later, and rejecting it would narrow what can be hooked.
  (let [unloaded (spec "tcd-unloaded" "size" "()I")]
    (try
      (api/install! (assoc unloaded :target-internal "no/such/Class/Anywhere"))
      (println "  ASSERT accepted a spec whose target class is not loaded")
      (flush)
      (catch Throwable t
        (fail! (str "a spec whose target class is not loaded was rejected: "
                    (.getName (class t)) ": " (.getMessage t)
                    ". The check must not narrow the deployment surface.") 40))))
  (reg/clear!)
  (println "DRIVER_PASS target-check — install! rejects a method that does"
           " not exist and a descriptor the method does not have, accepts a"
           " method that does exist, and accepts a target class that is not"
           " loaded yet")
  (flush)
  (System/exit 0))
