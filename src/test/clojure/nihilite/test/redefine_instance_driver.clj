(ns nihilite.test.redefine-instance-driver
  "Replaces src/test/java/nihilite/test/redefineInstanceDriver.java.

   Validates :redefine on an instance method: the bridge receives the
   calling instance via @This (verified by comparing identity).

   Invoked from build.clj as `java nihilite.test.redefineInstanceDriver`."
  (:require [nihilite.registry :as reg])
  (:import [net.bytebuddy.agent ByteBuddyAgent]
           [java.lang.instrument Instrumentation])
  (:gen-class
   :name nihilite.test.redefineInstanceDriver
   :prefix "rid-"
   :main true))

(def ^:private captured (atom nil))

(defn- generate-class-bytes! [options]
  (let [generate-class (Class/forName "clojure.core$generate_class")
        invoke-static (.getDeclaredMethod generate-class "invokeStatic"
                                         (into-array Class [Object]))]
    (.setAccessible invoke-static true)
    (let [[cname bytecode] (.invoke invoke-static nil (object-array [options]))]
      (clojure.lang.Compiler/writeClassFile cname bytecode)
      cname)))

(defn- generate-target-class! []
  (generate-class-bytes!
   {:name "nihilite.test.redefine_instance_driver.probe_target"
    :prefix "pt-"
    :impl-ns "nihilite.test.redefine-instance-driver"
    :main false
    :methods
    [[(with-meta (symbol "probe") {}) []
      (Class/forName "java.lang.String")]]}))

(defn gen-all!
  "Generates nihilite.test.redefine_instance_driver.probe_target (the
   target class whose probe() gets :redefine'd) into *compile-path*."
  []
  (generate-target-class!)
  nil)

;; Target stub body.

(defn pt-probe []
  "ORIGINAL-COUNTER=0")

(defn- fail! [why code]
  (println "redefineInstanceDriver FAIL:" why)
  (System/exit code))

(defn -main [& _args]
  (let [inst (ByteBuddyAgent/install)
        Agent (Class/forName "nihilite.kernel.Agent")
        agentmain (.getDeclaredMethod Agent "agentmain"
                                       (into-array Class [String Instrumentation]))]
    (.setAccessible agentmain true)
    (.invoke agentmain nil (object-array [nil inst]))
    ((resolve 'nihilite.kernel.installer/install) inst)


    (let [bridge (fn [self _args _method-name]
                   (reset! captured self)
                   "REDEFINED-AT-ARITY0")
          spec {:id "inst-redef"
                :target-internal "nihilite/test/redefine_instance_driver/probe_target"
                :method-name "probe"
                :position :redefine
                :arity 0
                :descriptor "()Ljava/lang/String;"
                :bridge bridge}]
      (reg/clear!)
      (reg/install! spec))

    (let [target (Class/forName "nihilite.test.redefine_instance_driver.probe_target")
          instance (.newInstance target (object-array []))
          result (.invoke (.getDeclaredMethod target "probe" (into-array Class []))
                          instance (object-array []))]
      (when (not= "REDEFINED-AT-ARITY0" result)
        (fail! (str "probe was \"" result "\" expected \"REDEFINED-AT-ARITY0\"") 3))
      (let [c @captured]
        (when (nil? c) (fail! "bridge did not run (CAPTURED null)" 4))
        (when (not (clojure.lang.Util/identical c instance))
          (fail! (str "CAPTURED was " c " expected " instance) 5))))
    (reg/clear!)
    (println "DRIVER_PASS instance-method :redefine captures self via @This")
    (System/exit 0)))

(when *compile-files*
  (gen-all!))
