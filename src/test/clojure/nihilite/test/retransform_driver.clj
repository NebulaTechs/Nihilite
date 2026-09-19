(ns nihilite.test.retransform-driver
  "Replaces src/test/java/nihilite/test/retransformDriver.java.

   Validates the full ByteBuddy advice + redefine path against a
   generated `nihilite.test.retransform_driver$DummyTarget` class. Each
   hook phase targets a different method because MethodDelegation and
   Advice on the same method clobber each other.

   Invoked from build.clj as `java nihilite.test.retransformDriver`.
   Driver class itself uses plain ns-form `:gen-class` (no annotations
   or array params); only the inner DummyTarget needs the reflection
   `generate-class` path because it ships alongside the driver.

   Run via gen-class to keep the driver protocol stable across builds
   without a Java source file."
  (:require [nihilite.registry :as reg]
            [nihilite.registry.stats :as stats]
            [nihilite.api :as api])
  (:import [net.bytebuddy.agent ByteBuddyAgent])
(:gen-class
    :name nihilite.test.retransformDriver
    :prefix "td-"
    :methods
    [[probe [long] String]
     [probeReturn [] String]
     [probeRedef [] String]
     [probeCancel [] String]
     [probeThrow [] String]
     [throwObserved [] int]
     [bodyExecutedAfterCancel [] boolean]]
    :main true))

;; Driver state (matches the volatile fields of the old Java driver).

(def ^:private entered (atom 0))
(def ^:private return-mutated (atom 0))
(def ^:private redefined (atom 0))

;; DummyTarget class options, generated alongside this driver.

(def ^:private dummy-target-class
  "nihilite.test.retransform_driver.DummyTarget")

(def ^:private dummy-target-internal
  "nihilite/test/retransform_driver/DummyTarget")

(defn- generate-class-bytes! [options]
  (let [opts (merge {:load-impl-ns true} options)
        generate-class (Class/forName "clojure.core$generate_class")
        invoke-static (.getDeclaredMethod generate-class "invokeStatic"
                                         (into-array Class [Object]))]
    (.setAccessible invoke-static true)
    (let [[cname bytecode] (.invoke invoke-static nil (object-array [opts]))]
      (clojure.lang.Compiler/writeClassFile cname bytecode)
      cname)))

(defn- generate-dummy-target! []
  (let [string-cls (Class/forName "java.lang.String")
        int-cls Integer/TYPE
        boolean-cls Boolean/TYPE
        mk (fn [mname pclasses rclass]
             (with-meta (vector mname pclasses rclass) {:static true}))]
    (generate-class-bytes!
     {:name dummy-target-class
      :prefix "dt-"
      :impl-ns "nihilite.test.retransform-driver"
      :main false
      :methods
      [(mk (with-meta (symbol "probe") {}) [int-cls] string-cls)
       (mk (with-meta (symbol "probeReturn") {}) [] string-cls)
       (mk (with-meta (symbol "probeRedef") {}) [] string-cls)
       (mk (with-meta (symbol "probeCancel") {}) [] string-cls)
       (mk (with-meta (symbol "probeThrow") {}) [] string-cls)
       (mk (with-meta (symbol "throwObserved") {}) [] int-cls)
       (mk (with-meta (symbol "bodyExecutedAfterCancel") {}) [] boolean-cls)]})))

(defn gen-all!
  "Generates nihilite.test.retransform_driver.DummyTarget into *compile-path*."
  []
  (generate-dummy-target!)
  nil)

;; DummyTarget stub bodies (gen-class forwards static methods to these vars).

(defn dt-probe [x] (str "original-" x))
(defn dt-probeReturn [] "untouched-return")
(defn dt-probeRedef [] "SHOULD-NEVER-BE-SEEN")
(defn dt-probeCancel []
  (reset! stats/driver-body-executed-after-cancel? true)
  "should-never-see")
(defn dt-probeThrow [] (throw (IllegalStateException. "driver-probe-throw")))
(defn dt-throwObserved [] (int @stats/driver-throw-observed))
(defn dt-bodyExecutedAfterCancel [] (boolean @stats/driver-body-executed-after-cancel?))

;; Spec bridge implementations.

(defn- entry-handler [_ctx]
  (.println System/err (str "DEBUG entry-handler called ctx=" (str _ctx)))
  (swap! entered inc)
  nil)

(defn- return-handler [_ctx]
  (swap! return-mutated inc)
  "MUTATED-BY-DRIVER")

(defn- redefine-handler [_self _args _method-name]
  (swap! redefined inc)
  "REDEFINED-BY-DRIVER")

(defn- entry-cancel-handler [ctx]
  (let [cancel (resolve 'nihilite.registry.dispatch/ctx-cancel!)]
    (cancel ctx true))
  nil)

(defn- throw-handler [_ctx]
  (stats/increment-throw-observed!)
  nil)

(defn- install-all! []
  ((requiring-resolve 'nihilite.registry.dispatch/install-redefine-dispatcher!))
  (reg/clear!)
  (reg/install! {:id "driver-entry"
                 :target-internal dummy-target-internal
                 :method-name "probe"
                 :position :entry
                 :arity 1
                 :descriptor "(I)Ljava/lang/String;"
                 :bridge entry-handler
                 :note "driver :entry"})
  (reg/install! {:id "driver-return"
                 :target-internal dummy-target-internal
                 :method-name "probeReturn"
                 :position :return
                 :arity 0
                 :descriptor "()Ljava/lang/String;"
                 :action :modify
                 :bridge return-handler
                 :note "driver :return"})
  (reg/install! {:id "driver-redefine"
                 :target-internal dummy-target-internal
                 :method-name "probeRedef"
                 :position :redefine
                 :arity 0
                 :descriptor "()Ljava/lang/String;"
                 :bridge redefine-handler
                 :note "driver :redefine"})
  (reg/install! {:id "driver-entry-cancel"
                 :target-internal dummy-target-internal
                 :method-name "probeCancel"
                 :position :entry
                 :arity 0
                 :descriptor "()Ljava/lang/String;"
                 :action :cancel
                 :bridge entry-cancel-handler
                 :note "driver :entry :cancel"})
  (reg/install! {:id "driver-throw"
                 :target-internal dummy-target-internal
                 :method-name "probeThrow"
                 :position :throw
                 :arity 0
                 :descriptor "()Ljava/lang/String;"
                 :bridge throw-handler
                 :note "driver :throw"}))

(defn- fail! [why code]
  (println "retransformDriver FAIL:" why)
  (System/exit code))

(defn- target-class []
  (Class/forName dummy-target-class))

(defn- run-once! [inst]
  (let [target (target-class)
        probe (.getDeclaredMethod target "probe" (into-array Class [Integer/TYPE]))
        probeReturn (.getDeclaredMethod target "probeReturn" (into-array Class []))
        probeRedef (.getDeclaredMethod target "probeRedef" (into-array Class []))
        probeCancel (.getDeclaredMethod target "probeCancel" (into-array Class []))
        probeThrow (.getDeclaredMethod target "probeThrow" (into-array Class []))]

    ;; probe(int) -- :entry fires
    ;; First, explicitly test if HookAdvice's vars are bound (debug):
    (let [hook-var (try (resolve 'nihilite.kernel.advice/hk-onEntry)
                        (catch Throwable e
                          (.println System/err (str "DEBUG resolve hk-onEntry threw: " (.getMessage e)))
                          nil))]
      (.println System/err (str "DEBUG hk-onEntry var=" (when hook-var (str hook-var)) " bound=" (when hook-var (.isBound ^clojure.lang.Var hook-var))))
      (when hook-var
        (try
          (.invoke ^clojure.lang.Var hook-var "smoke" nil nil (object-array []))
          (.println System/err "DEBUG hk-onEntry direct invoke ok")
          (catch Throwable t
            (.println System/err (str "DEBUG hk-onEntry direct invoke threw: " (.getMessage t)))))))
    (let [result (try
                   (.invoke probe nil (object-array [(int 1)]))
                   (catch Throwable t
                     (.println System/err (str "DEBUG probe invoke threw: " (.getClass t) " " (.getMessage t)))
                     (when-let [c (.getCause t)]
                       (.println System/err (str "DEBUG probe cause: " (.getClass c) " " (.getMessage c)))
                       (when-let [c2 (.getCause c)]
                         (.println System/err (str "DEBUG probe cause2: " (.getClass c2) " " (.getMessage c2)))))
                     (throw t)))]
      (when (not= @entered 1) (fail! (str "ENTERED=" @entered " expected 1") 3))
      (when (not= "original-1" result) (fail! (str "probe was \"" result "\" expected \"original-1\"") 4)))

    ;; probeReturn() -- :return mutates
    (let [r (.invoke probeReturn nil (object-array []))]
      (when (not= @return-mutated 1) (fail! (str "RETURN_MUTATED=" @return-mutated " expected 1") 5))
      (when (not= "MUTATED-BY-DRIVER" r) (fail! (str "probeReturn was \"" r "\" expected \"MUTATED-BY-DRIVER\"") 6)))

    ;; probeRedef() -- body replaced
    (let [r (.invoke probeRedef nil (object-array []))]
      (when (not= @redefined 1) (fail! (str "REDEFINED=" @redefined " expected 1") 7))
      (when (not= "REDEFINED-BY-DRIVER" r) (fail! (str "probeRedef was \"" r "\" expected \"REDEFINED-BY-DRIVER\"") 8)))

    ;; probeCancel() -- :entry :cancel short-circuits
    (try
      (.invoke probeCancel nil (object-array []))
      (fail! "probeCancel returned normally; expected HookCancelledException" 12)
      (catch java.lang.reflect.InvocationTargetException ite
        (when (not (instance? nihilite.kernel.HookCancelledException (.getCause ite)))
          (fail! (str "probeCancel cause was "
                      (when-let [c (.getCause ite)] (.getName (class c)))
                      " expected nihilite.kernel.HookCancelledException") 13))))
    (stats/clear-driver-state!)
    (when @stats/driver-body-executed-after-cancel? (fail! "probeCancel host body executed; short-circuit broken" 14))

    ;; probeThrow() -- :throw observes
    (try
      (.invoke probeThrow nil (object-array []))
      (fail! "probeThrow returned normally; expected throw" 15)
      (catch java.lang.reflect.InvocationTargetException ite
        (let [c (.getCause ite)]
          (when (not (and (instance? IllegalStateException c)
                          (= "driver-probe-throw" (.getMessage ^IllegalStateException c))))
            (fail! (str "probeThrow cause was "
                        (when c (str (.getName (class c)) ":" (.getMessage c)))
                        " expected IllegalStateException:driver-probe-throw") 16)))))
    (when (not= @stats/driver-throw-observed 1)
      (fail! (str "THROW_OBSERVED=" @stats/driver-throw-observed " expected 1") 17))

    ;; retransform and re-fire all three
    (try
      (.retransformClasses inst (into-array Class [target]))
      (catch Throwable t (fail! (str "retransformClasses threw " t) 9)))

    (.invoke probe nil (object-array [(int 2)]))
    (.invoke probeReturn nil (object-array []))
    (.invoke probeRedef nil (object-array []))

    (when (not (and (= @entered 2) (= @return-mutated 2) (= @redefined 2)))
      (fail! (str "after retransform ENTERED=" @entered
                  " RETURN_MUTATED=" @return-mutated " REDEFINED=" @redefined) 10))

    ;; swap-bridge
    (let [entered-before @entered
          swapped-handler (fn [_ctx] (swap! entered inc) nil)]
      (api/swap-bridge! "driver-entry" swapped-handler)
      (.invoke probe nil (object-array [(int 3)]))
      (when (not= @entered (inc entered-before))
        (fail! (str "ENTERED after swap-bridge = " @entered " expected " (inc entered-before)) 24))
      (let [looked-spec (reg/lookup "driver-entry")
            current-bridge (:bridge looked-spec)]
        (when (not (clojure.lang.Util/identical current-bridge swapped-handler))
          (fail! "post-swap bridge is not swappedHandler" 25))))

    ;; post-retransform cancel + throw
    (try
      (.invoke probeCancel nil (object-array []))
      (fail! "post-retransform probeCancel returned normally" 18)
      (catch java.lang.reflect.InvocationTargetException ite
        (when (not (instance? nihilite.kernel.HookCancelledException (.getCause ite)))
          (fail! (str "post-retransform cancel cause was "
                      (when-let [c (.getCause ite)] (.getName (class c)))) 19))))
    (stats/clear-driver-state!)
    (try
      (.invoke probeThrow nil (object-array []))
      (fail! "post-retransform probeThrow returned normally" 20)
      (catch java.lang.reflect.InvocationTargetException ite
        (when (not (instance? IllegalStateException (.getCause ite)))
          (fail! (str "post-retransform throw cause was "
                      (when-let [c (.getCause ite)] (.getName (class c)))) 21))))
    (when (not= @stats/driver-throw-observed 2)
      (fail! (str "post-retransform THROW_OBSERVED=" @stats/driver-throw-observed " expected 2") 22))
    (when @stats/driver-body-executed-after-cancel?
      (fail! "post-retransform probeCancel body still executed" 23))))

(defn- dump-transformer []
  (proxy [java.lang.instrument.ClassFileTransformer] []
    (transform [_ loader class-name class-being-redefined protection-domain bytes]
      (when (and class-name (.endsWith ^String class-name "DummyTarget"))
        (let [path (str "target/" (.replace ^String class-name "." "/") "_woven.class")]
          (try
            (let [f (java.io.File. path)]
              (when-not (.exists (.getParentFile f))
                (.mkdirs (.getParentFile f)))
              (clojure.java.io/copy (java.io.ByteArrayInputStream. bytes) f)
              (.println System/err (str "DEBUG dumped " path " size=" (alength bytes))))
            (catch Throwable t
              (.println System/err (str "DEBUG dump fail: " (.getMessage t)))))))
      nil)))

(defn td-main [& _args]
  (let [inst (ByteBuddyAgent/install)]
    ((requiring-resolve 'nihilite.registry.dispatch/install-redefine-dispatcher!))
    ((requiring-resolve 'nihilite.kernel.installer/install) inst)
    ;; register the dumper AFTER ByteBuddy so we capture the woven OUTPUT bytes
    (.addTransformer inst (dump-transformer) true)
    (install-all!)
    (run-once! inst)
    (reg/clear!)
    (when (not (empty? (reg/list-ids)))
      (fail! "list-ids not empty after clear" 11))
    (println "DRIVER_PASS retransform + :return-mutation + :redefine-substitution + :entry-cancel + :throw-observation + swap-bridge all proven")
    (System/exit 0)))

(when *compile-files*
  (gen-all!))