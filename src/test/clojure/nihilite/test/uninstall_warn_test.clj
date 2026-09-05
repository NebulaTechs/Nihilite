(ns nihilite.test.uninstall-warn-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.api :as api]
            [nihilite.test.fixtures :as fx])
  (:import [java.util.logging Logger Level LogRecord]
           [nihilite.test CapturingLogHandler]))

(defn- entry-spec [id]
  {:id id
   :target-internal "java/lang/String"
   :method-name "length"
   :descriptor "()I"
   :position :entry
   :action :observe
   :bridge (fn [_] nil)})

(defn- capture-bridge-warn
  [f]
  (let [logger (Logger/getLogger "nihilite.hooks.Bridge")
        handler (CapturingLogHandler.)
        old-level (.getLevel logger)]
    (.addHandler logger handler)
    (try
      (.setLevel logger Level/ALL)
      (f handler)
      (finally
        (.setLevel logger old-level)
        (.removeHandler logger handler)))))

(use-fixtures :each fx/reg-cleanup)

(deftest uninstall-without-instrumentation-emits-bridge-warn
  (api/install! (entry-spec "warn-test"))
  (capture-bridge-warn
    (fn [^CapturingLogHandler handler]
      (api/uninstall! "warn-test")
      (let [captured (.captured handler)]
        (is (pos? (count captured))
            "Bridge.uninstallSpec should emit at least one WARN record when no Instrumentation is registered")
        (is (some #(re-find #"no Instrumentation" (.getMessage ^LogRecord %)) captured)
            "at least one WARN message should mention 'no Instrumentation'")))))

(deftest uninstall-without-instrumentation-emits-registry-warn
  (api/install! (entry-spec "reg-warn-test"))
  (let [reg-handler (CapturingLogHandler.)
        reg-logger (Logger/getLogger "nihilite.registry")
        old-level (.getLevel reg-logger)]
    (.addHandler reg-logger reg-handler)
    (try
      (.setLevel reg-logger Level/ALL)
      (api/uninstall! "reg-warn-test")
      (let [captured (.captured reg-handler)]
        (is (some #(re-find #"0 classes retransformed" (.getMessage ^LogRecord %)) captured)
            "registry should emit WARN with '0 classes retransformed' when retransform count is 0"))
      (finally
        (.setLevel reg-logger old-level)
        (.removeHandler reg-logger reg-handler)))))