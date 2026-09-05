(ns nihilite.debug
  "Diagnostics for hook specs: why a hook is not firing, recent fire
   traces, and woven-bytecode export."
  (:require [clojure.java.io :as jio]
            [nihilite.registry :as reg])
  (:import [java.io FileOutputStream]
           [java.lang.instrument Instrumentation]
           [nihilite.agent Agent]))

(defn why-firing?
  [id]
  (let [spec (reg/lookup id)]
    (cond
      (nil? spec)
      {:firing? false :reason :unknown-spec :id (str id)}

      :else
      (let [target (:target-internal spec)
            method  (:method-name spec)
            desc    (:descriptor spec)
            loaded? (try
                      (Class/forName (.replace target \/ \.) false
                                     (clojure.lang.RT/baseLoader))
                      true
                      (catch Throwable _ false))
            stats   (reg/get-stats id)]
        (cond
          (not loaded?)
          {:firing? false :reason :class-not-loaded :target target}

          :else
          {:firing? true
           :reason :ok
           :target target
           :method method
           :descriptor desc
           :stats (when stats
                    {:fired      @(:fired stats)
                     :modified   @(:modified stats)
                     :cancelled  @(:cancelled stats)
                     :exceptions @(:exceptions stats)
                     :last-ns    @(:last-ns stats)
                     :max-ns     @(:max-ns stats)})})))))

(defn trace-last-fires
  ([] (trace-last-fires 20))
  ([n]
   (let [all (reg/trace-snapshot)
         cnt (count all)]
     (if (<= cnt n)
       all
       (subvec all (- cnt n))))))

(defn dump-bytecode
  [class-name ^String path]
  (let [^Instrumentation inst (Agent/currentInstrumentation)]
    (when inst
      (let [cls (try
                  (Class/forName class-name false (clojure.lang.RT/baseLoader))
                  (catch Throwable _ nil))]
        (when cls
          (let [loader (.getClassLoader cls)
                res (str (.replace class-name \. \/) ".class")
                in  (.getResourceAsStream loader res)]
            (when in
              (with-open [out (FileOutputStream. path)]
                (jio/copy in out))
              path)))))))