(ns examples.positions.init
  "Demonstrate all four hook positions with measured-safe BitSet targets.

   Entry, return, and throw bridges receive one hook context. A redefine
   bridge receives (self args method-name), and its return value becomes the
   target method's result without running the original body."
  (:require [nihilite.builder.api :as api])
  (:import [java.util BitSet]))

(def ^:private hook-ids
  {:entry    "positions-entry"
   :return   "positions-return"
   :throw    "positions-throw"
   :redefine "positions-redefine"})

(def ^:private observations (atom {}))
(def ^:private exercise-results (atom {}))

(defn- one-bit-set []
  (doto (BitSet. 8)
    (.set 3)))

(defn- empty-set []
  (BitSet. 8))

(def ^:private original-cardinality
  "The cardinality before the redefine hook replaces the method body."
  (.cardinality (one-bit-set)))

(def ^:private original-size
  "The size before the return hook starts observing the method result."
  (.size (one-bit-set)))

(defn- install-hook! [spec]
  (let [installed? (api/install! spec)]
    (when-not (true? installed?)
      (throw (ex-info "the positions example requires a new hook installation"
                      {:nihilite/kind :nihilite/example-install-failed
                       :hook-id (:id spec)
                       :installed? installed?})))))

(install-hook!
 {:id              (:entry hook-ids)
  :target-internal "java/util/BitSet"
  :method-name     "isEmpty"
  :descriptor      "()Z"
  :arity           0
  :position        :entry
  :action          :observe
  :bridge          (fn [ctx]
                     (swap! observations assoc :entry
                            {:phase (:phase ctx)
                             :args-count (alength ^objects (:args ctx))}))
  :note            "entry observes the call before the original body runs"})

(install-hook!
 {:id              (:return hook-ids)
  :target-internal "java/util/BitSet"
  :method-name     "size"
  :descriptor      "()I"
  :arity           0
  :position        :return
  :action          :observe
  :bridge          (fn [ctx]
                     (swap! observations assoc :return
                            {:phase (:phase ctx)
                             :return-value (:return-value ctx)}))
  :note            "return observes the original method result"})

(install-hook!
 {:id              (:throw hook-ids)
  :target-internal "java/util/BitSet"
  :method-name     "get"
  :descriptor      "(I)Z"
  :arity           1
  :position        :throw
  :action          :observe
  :bridge          (fn [ctx]
                     (swap! observations assoc :throw
                            {:phase (:phase ctx)
                             :throwable (:throwable ctx)}))
  :note            "throw observes the exception thrown by get(-1)"})

(install-hook!
 {:id              (:redefine hook-ids)
  :target-internal "java/util/BitSet"
  :method-name     "cardinality"
  :descriptor      "()I"
  :arity           0
  :position        :redefine
  :action          :observe
  :bridge          (fn [_self _args method-name]
                     (swap! observations assoc :redefine
                            {:method-name method-name})
                     (int 99))
  :note            "redefine replaces cardinality with the bridge result"})

(defn- out-of-range-get []
  (try
    (.get (BitSet. 8) -1)
    (catch IndexOutOfBoundsException throwable throwable)))

(defn exercise!
  "Exercise all four positions and return their observable results."
  []
  (reset! observations {})
  (let [entry-result    (.isEmpty (empty-set))
        return-result   (.size (one-bit-set))
        throwable       (out-of-range-get)
        redefine-result (.cardinality (one-bit-set))
        observed        @observations
        result          {:entry    {:event (get observed :entry)
                                   :result entry-result}
                        :return   {:event (get observed :return)
                                   :original-result original-size
                                   :result return-result}
                        :throw    {:event (get observed :throw)
                                   :result throwable}
                        :redefine {:event (get observed :redefine)
                                   :original-result original-cardinality
                                   :result redefine-result}}]
    (reset! exercise-results result)
    (println "[examples.positions.init] :entry fired before the original body;"
             "original isEmpty result =" entry-result)
    (println "[examples.positions.init] :return observed original size ="
             (get-in result [:return :event :return-value]))
    (println "[examples.positions.init] :throw observed"
             (.getName (class throwable)))
    (println "[examples.positions.init] :redefine replaced original cardinality"
             original-cardinality "with" redefine-result)
    (flush)
    result))

(defn results
  "Return the results produced by the most recent exercise."
  []
  @exercise-results)

(defn statuses
  "Return the public install-side and runtime-side status for each hook."
  []
  (into {}
        (map (fn [[position hook-id]]
               [position (api/install-status! hook-id)])
             hook-ids)))

(println "[examples.positions.init] loaded. Call"
         "(examples.positions.init/exercise!) to exercise all four positions.")
(flush)
