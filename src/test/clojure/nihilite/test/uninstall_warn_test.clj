(ns nihilite.test.uninstall-warn-test
  "The two WARNs a spec removal makes when nothing can be retransformed.

   The first assertion's message used to say \"Bridge.uninstallSpec\" — that
   class is gone (the Java layer was removed entirely; kernel/installer.clj
   replaced it). What it actually checks is installer/uninstall-spec-with-
   target!, so this namespace requires the installer itself. It used to pass
   without that require only because the contract runner shares one JVM with
   every other namespace, and something else had already loaded installer by
   the time this ran; run alone, `resolve` answered nil and uninstall! threw
   a NullPointerException instead of warning.

   The second assertion is the registry's own half of the same report."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.api :as api]
            ;; required for its side effect: the WARN under test is
            ;; installer/log-warn's, so installer has to be loaded
            [nihilite.kernel.installer]
            [nihilite.test.capturing-log-handler :as clh]
            [nihilite.test.fixtures :as fx])
  (:import [java.util.logging Logger Level LogRecord]))

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
  (let [logger (Logger/getLogger "nihilite.kernel.installer")
        handler (clh/make)
        proxy (:handler handler)
        old-level (.getLevel logger)]
    (.addHandler logger proxy)
    (try
      (.setLevel logger Level/ALL)
      (f handler)
      (finally
        (.setLevel logger old-level)
        (.removeHandler logger proxy)))))

(use-fixtures :each fx/reg-cleanup)

(deftest uninstall-without-instrumentation-emits-bridge-warn
  (api/install! (entry-spec "warn-test"))
  (capture-bridge-warn
    (fn [handler]
      (api/uninstall! "warn-test")
      (let [captured (clh/captured handler)]
        (is (pos? (count captured))
            "installer/uninstall-spec-with-target! should emit at least one WARN record when no Instrumentation is registered")
        (is (some #(re-find #"no Instrumentation" (.getMessage ^LogRecord %)) captured)
            "at least one WARN message should mention 'no Instrumentation'")))))

(deftest uninstall-without-instrumentation-emits-registry-warn
  (api/install! (entry-spec "reg-warn-test"))
  (let [reg-handler (clh/make)
        reg-proxy (:handler reg-handler)
        reg-logger (Logger/getLogger "nihilite.registry")
        old-level (.getLevel reg-logger)]
    (.addHandler reg-logger reg-proxy)
    (try
      (.setLevel reg-logger Level/ALL)
      (api/uninstall! "reg-warn-test")
      (let [captured (clh/captured reg-handler)]
        (is (some #(re-find #"0 classes retransformed" (.getMessage ^LogRecord %)) captured)
            "registry should emit WARN with '0 classes retransformed' when retransform count is 0"))
      (finally
        (.setLevel reg-logger old-level)
        (.removeHandler reg-logger reg-proxy)))))