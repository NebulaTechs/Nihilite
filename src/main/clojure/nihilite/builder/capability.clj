(ns nihilite.builder.capability
  "A read-only report of what this JVM can do, and what Nihilite has done to it.

   install-status! answers \"what happened to the hook I installed\". This
   answers the two questions around it: \"can this JVM do that at all\" and
   \"is the agent actually armed\". A hook that reports :woven-count 0 and
   :pending? true looks identical whether the agent is missing, the target
   class is not loaded, or the JVM does not support redefinition, and those
   three need three different fixes.

   THE REPORT IS READ-ONLY. Reading it never installs an agent, never
   attaches to the running JVM, and never retransforms a class. A
   diagnostic that changes the thing it diagnoses is worse than no
   diagnostic, because the answer it gives is then a fact about a
   different JVM than the one being asked about. Everything here is a
   question asked of the JVM as it already is:

     - the Instrumentation the registry holds, if any
     - what that Instrumentation says it supports
     - the JVM's own module and attach facts
     - which entry point armed the agent, when one did

   THE ENTRY POINT IS RECORDED, NOT GUESSED. agent.clj's arm-agent! knows
   whether it ran from premain or from agentmain, and the difference
   matters: premain runs on the application's main thread and agentmain
   runs on the Attach Listener, and the two have different classloader
   and timing behaviour. The label is published where the arming happens
   and read back here, so the report says where it came from rather than
   inferring it.

   Everything that can fail does fail quietly rather than loudly, because
   this is a report: a JVM that will not answer a question about itself
   gets :unknown for that answer, not an exception that would replace the
   report with a stack trace."
  (:require [clojure.string :as str])
  (:import [java.lang.instrument Instrumentation]
           [java.lang.management ManagementFactory]
           [java.lang ModuleLayer]))

(defn- property-or
  [name fallback]
  (let [v (System/getProperty name)]
    (if (or (nil? v) (str/blank? v)) fallback v)))

(defn- module-present?
  "Whether the boot layer has the named module.

   The java.instrument module is the one that matters: it holds the
   Instrumentation API, and a JVM image without it cannot host an agent
   at all. A JVM with the module but without jdk.attach can still run an
   agent -- it just cannot attach one at runtime."
  [module-name]
  (try
    (some? (.findModule (ModuleLayer/boot) module-name))
    (catch Throwable _ false)))
(defn- attach-api-present?
  "Whether com.sun.tools.attach is reachable from the system classloader.
   Checked with initialize=false semantics by resolving the class without
   initialising it: a getSystemClassLoader that cannot see the class means
   the jdk.attach module is not in the image."
  []
  (try
    (Class/forName "com.sun.tools.attach.VirtualMachine" false
                   (ClassLoader/getSystemClassLoader))
    true
    (catch ClassNotFoundException _ false)
    (catch LinkageError _ false)
    (catch SecurityException _ false)))

(defn- attach-provider-count
  "How many AttachProviders this JVM offers, or -1 when the question could
   not be answered."
  []
  (try
    (let [provider-type (Class/forName "com.sun.tools.attach.spi.AttachProvider"
                                        false
                                        (ClassLoader/getSystemClassLoader))
          providers (.invoke (.getMethod provider-type "providers"
                                                      (into-array Class []))
                             nil (object-array []))]
      (if (instance? java.util.List providers)
        (count providers)
        -1))
    (catch java.lang.ReflectiveOperationException _ -1)
    (catch LinkageError _ -1)
    (catch SecurityException _ -1)))

(defn- relevant-jvm-arguments
  "The JVM arguments that bear on instrumentation, for a support log."
  []
  (try
    (->> (.getInputArguments (ManagementFactory/getRuntimeMXBean))
         (filter (fn [^String a]
                   (let [l (str/lower-case a)]
                     (or (str/includes? l "javaagent")
                         (str/includes? l "attach")
                         (str/includes? l "jdwp")
                         (str/includes? l "nihilite")))))
         vec)
    (catch LinkageError _ [])
    (catch SecurityException _ [])))

(defn- instrumentation-facts
  "What the Instrumentation says it supports, or nil when there is none."
  [^Instrumentation inst]
  (when inst
    {:redefine-classes-supported? (.isRedefineClassesSupported inst)
     :retransform-classes-supported? (.isRetransformClassesSupported inst)}))

(defn- loaded-var
  "The var named by `sym`, when its namespace is ALREADY loaded, or nil.

   Deliberately not a `require`. Loading a namespace to read a var out of
   it is a side effect, and this report promises to have none: a require
   here would pull in a namespace, and with it the classes that namespace
   mentions, on a JVM where the report was supposed to change nothing.
   The namespace is loaded in every JVM that armed the agent, which is
   exactly the case this var answers for.

   find-ns takes a SYMBOL, and namespace/ hands back a string, so the
   symbol is built explicitly. Getting that wrong throws inside find-ns,
   and the catch below turns the throw into a nil -- which would read as
   \"the agent is not armed\" rather than as the error it is."
  [sym]
  (try
    (when-let [ns-sym (namespace sym)]
      (when (find-ns (symbol ns-sym))
        (resolve sym)))
    (catch Throwable _ nil)))

(defn- agent-state
  "The agent's own state, read without touching it.

   The Instrumentation is read through the registry's holder, which is the
   same one install! uses, so what the report says about the agent is what
   the registry believes. The entry-point label is read from the trainer,
   which records it when it arms."
  []
  (let [reg-var (loaded-var 'nihilite.builder.registry/instrumentation-ref)
        holder (when (instance? clojure.lang.Var reg-var) @reg-var)
        inst (when (instance? clojure.lang.Atom holder) @holder)
        ep-var (loaded-var 'nihilite.trainer.agent/entry-point)
        ep-holder (when (instance? clojure.lang.Var ep-var) @ep-var)]
    {:instrumentation-present? (some? inst)
     :instrumentation inst
     :entry-point (when (instance? clojure.lang.Atom ep-holder) @ep-holder)}))

(defn capability-report
  "What this JVM can do, and what Nihilite has done to it.

   Returns a map with these keys:

   ```clojure
   :instrumentation-present?        ;; whether an Instrumentation has been
                                    ;; registered, i.e. the agent is armed
   :entry-point                     ;; :premain, :agentmain, or nil when the
                                    ;; agent was not armed by an entry point
   :redefine-classes-supported?     ;; what the Instrumentation reports
   :retransform-classes-supported?  ;; what the Instrumentation reports
   :java-version, :java-vendor, :vm-name, :os-name, :os-arch
                                    ;; the JVM's own facts
   :java-instrument-module-present? ;; whether the boot layer has the
                                    ;; java.instrument module, which holds the
                                    ;; Instrumentation API itself
   :jdk-attach-present?             ;; whether com.sun.tools.attach is reachable
   :attach-provider-count           ;; how many AttachProviders the JVM offers,
                                    ;; -1 when the question could not be answered
   :relevant-jvm-arguments          ;; the JVM arguments that bear on
                                    ;; instrumentation
   :detail                          ;; a one-line summary
   :remediation                     ;; what to do when the agent is not armed,
                                    ;; or nil
   ```

   The report is read-only: it installs nothing, attaches to nothing, and
   retransforms nothing. Calling it twice on the same JVM gives the same
   answer unless the JVM itself changed."
  []
  (let [{:keys [instrumentation-present? instrumentation entry-point]}
        (agent-state)
        facts (instrumentation-facts instrumentation)
        jdk-attach? (attach-api-present?)
        providers (attach-provider-count)
        redefine? (:redefine-classes-supported? facts)
        retransform? (:retransform-classes-supported? facts)
        common {:java-version (property-or "java.version" "unknown")
                :java-vendor (property-or "java.vendor" "unknown")
                :vm-name (property-or "java.vm.name" "unknown")
                :os-name (property-or "os.name" "unknown")
                :os-arch (property-or "os.arch" "unknown")
                :java-instrument-module-present? (module-present? "java.instrument")
                :jdk-attach-present? jdk-attach?
                :attach-provider-count providers
                :relevant-jvm-arguments (relevant-jvm-arguments)}]
    (cond
      (and instrumentation-present? (false? redefine?))
      (merge common
             {:instrumentation-present? true
              :entry-point entry-point
              :redefine-classes-supported? false
              :retransform-classes-supported? retransform?
              :detail "the agent is armed, but this JVM does not support class redefinition"
              :remediation "redefinition is what Nihilite weaves with; a JVM without it cannot run hooks"})

      instrumentation-present?
      (merge common
             {:instrumentation-present? true
              :entry-point entry-point
              :redefine-classes-supported? redefine?
              :retransform-classes-supported? retransform?
              :detail (str "the agent is armed"
                           (when entry-point (str " from " entry-point)))
              :remediation nil})

      :else
      (merge common
             {:instrumentation-present? false
              :entry-point nil
              :redefine-classes-supported? nil
              :retransform-classes-supported? nil
              :detail "the agent is not armed, so no hook can weave"
              :remediation (if jdk-attach?
                             "the agent was not started; pass -javaagent:<nihilite.jar> to the JVM, or attach it at runtime"
                             "the agent was not started and jdk.attach is not in this JVM's image; pass -javaagent:<nihilite.jar> to the JVM")}))))
