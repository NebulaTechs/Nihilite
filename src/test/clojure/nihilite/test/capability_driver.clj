(ns nihilite.test.capability-driver
  "Characterisation driver: the capability report tells the truth about a
   JVM that has an agent.

   The contract suite runs with no Instrumentation, so the only thing it can
   check is that the report says \"the agent is not armed\". That is half
   the report. The other half -- whether the agent IS armed, which entry
   point armed it, and what the Instrumentation says it supports -- can
   only be checked where an Instrumentation is real.

   WHAT IS ASSERTED.

     1. With the agent armed, the report says so, names the entry point,
        and reports the same redefinition support the Instrumentation
        itself reports. A report that paraphrases the Instrumentation
        rather than reading it would drift from it.

     2. The report is READ-ONLY. Asking it changes nothing: the registry's
        Instrumentation holder is the same object afterwards, and the
        report is identical when asked twice.

     3. The report resolves no class other than the one it probes for.
        The Instrumentation can enumerate the loaded classes, so the set
        of them is directly observable: asking the report must not add
        to it beyond the attach-probe class it legitimately resolves.

   Driver shape follows nihilite.test.weaving-contract-driver: a real
   Instrumentation, the production arming path, assertions on what the
   report says rather than on a woven count, fail! + System/exit with a
   distinct non-zero code per assertion family, and println+flush after
   every measurement so a crash still leaves the numbers in the log."
  (:require [clojure.set :as set]
            [nihilite.builder.api :as api]
            [nihilite.builder.registry :as reg]
            [nihilite.builder.capability :as cap])
  (:import [net.bytebuddy.agent ByteBuddyAgent])
  (:gen-class
    :name nihilite.test.capabilityDriver
    :prefix "cad-"
    :main true))

(defn- fail!
  [why code]
  (println "DRIVER_FAIL capability:" why)
  (flush)
  (System/exit code))

(defn- loaded-class-names
  "The binary names of every class the Instrumentation knows about."
  [^java.lang.instrument.Instrumentation inst]
  (into #{} (map (fn [^Class c] (.getName c)) (.getAllLoadedClasses inst))))

(defn cad-main [& _args]
  ;; PHASE 1 -- the report on a JVM with no agent, measured for
  ;; read-onlyness.
  ;;
  ;; This phase runs BEFORE the agent is armed, and that ordering is the
  ;; measurement. An armed agent has a worker thread that loads Clojure
  ;; namespaces at startup and an init script that runs on a background
  ;; thread, so the loaded-class list keeps moving for reasons that have
  ;; nothing to do with the report. A JVM with no agent is quiet, and what
  ;; the report does to the loaded-class set there is what it does
  ;; everywhere.
  (let [quiet (ByteBuddyAgent/install)
        report-before (cap/capability-report)
        loaded-before (loaded-class-names quiet)]
    (when (:instrumentation-present? report-before)
      (fail! "the report says the agent is armed, but nothing armed it yet" 5))
    (dotimes [_ 5] (cap/capability-report))
    (let [loaded-after (loaded-class-names quiet)
          added (set/difference loaded-after loaded-before)]
      (when-not (empty? added)
        (fail! (str "the report loaded " (count added) " class(es): "
                    (pr-str (sort (take 5 added)))) 30))
      (println "  ASSERT the report loaded no class at all beyond the one it"
               " probes for: the loaded-class set did not grow")
      (flush)
      ;; The report is read-only in the other sense too: asking it twice
      ;; gives the same answer.
      (let [r1 (cap/capability-report)
            r2 (cap/capability-report)]
        (when (not= r1 r2)
          (fail! (str "two reports of an unchanged JVM disagree:\n  " (pr-str r1)
                      "\n  " (pr-str r2)) 31))
        (println "  ASSERT the report is read-only: two reports of an"
                 " unchanged JVM agree exactly")
        (flush))))
  ;; PHASE 2 -- the report on a JVM with an agent.
  (let [inst (ByteBuddyAgent/install)
        Agent (Class/forName "nihilite.trainer.Agent")
        premain (.getDeclaredMethod Agent "premain"
                                    (into-array Class [String
                                                       java.lang.instrument.Instrumentation]))]
    (.setAccessible premain true)
    (.invoke premain nil (object-array [nil inst])))
  (let [report (cap/capability-report)
        inst @reg/instrumentation-ref]
    (println "DRIVER_SETUP the agent is armed")
    (doseq [[k v] (sort-by key report)]
      (when-not (identical? k :instrumentation)
        (println "  " k "=" (pr-str v))))
    (flush)
    ;; The agent is armed, so the report has to say so.
    (when-not (:instrumentation-present? report)
      (fail! "the report says the agent is not armed, but premain just armed it" 10))
    ;; The entry point is recorded, not guessed: premain armed this one.
    (when (not= :premain (:entry-point report))
      (fail! (str "the report says the entry point is " (pr-str (:entry-point report))
                  ", expected :premain") 11))
    ;; The redefinition facts are the Instrumentation's own, read not
    ;; paraphrased.
    (when (not= (.isRedefineClassesSupported inst)
                (:redefine-classes-supported? report))
      (fail! (str "the report says redefine=" (:redefine-classes-supported? report)
                  " but the Instrumentation says " (.isRedefineClassesSupported inst)) 12))
    (when (not= (.isRetransformClassesSupported inst)
                (:retransform-classes-supported? report))
      (fail! (str "the report says retransform=" (:retransform-classes-supported? report)
                  " but the Instrumentation says " (.isRetransformClassesSupported inst)) 13))
    (when (:remediation report)
      (fail! (str "an armed agent must not come with a remediation, got "
                  (pr-str (:remediation report))) 14))
    (println "  ASSERT the report agrees with the Instrumentation on"
             "redefine and retransform, and names :premain as the entry point")
    (flush)
    ;; The registered Instrumentation is not replaced by asking.
    (let [before @reg/instrumentation-ref]
      (cap/capability-report)
      (when-not (identical? before @reg/instrumentation-ref)
        (fail! "the report replaced the registered Instrumentation" 20))
      (println "  ASSERT the report left the registered Instrumentation alone")
      (flush))
    ;; The public surface reaches the same report.
    (when (not= (cap/capability-report) (api/capability-report))
      (fail! "api/capability-report and capability/capability-report disagree" 40))
    (println "  ASSERT api/capability-report and capability/capability-report"
             " are the same report")
    (flush))
  (println "DRIVER_PASS capability — the report says the agent is armed from"
           " :premain, agrees with the Instrumentation on redefine and"
           " retransform, is read-only, and resolves no class")
  (flush)
  (System/exit 0))
