(ns examples.actions.init
  "Compare the four built-in actions through observable public results.

   Each action uses a measured-safe java.util.BitSet target. Observe leaves
   the original boolean result intact, modify replaces an int result, cancel
   short-circuits with the project's cancellation exception, and subscriber
   records cancellation while allowing the original boolean result to return."
  (:require [nihilite.builder.api :as api]))

(def ^:private hook-ids
  {:observe "actions-observe-is-empty"
   :modify "actions-modify-size"
   :cancel "actions-cancel-cardinality"
   :subscriber "actions-subscriber-get"})

(def ^:private expected-counter-deltas
  {:observe {:fired 1 :modified 0 :cancelled 0 :exceptions 0}
   :modify {:fired 1 :modified 1 :cancelled 0 :exceptions 0}
   :cancel {:fired 1 :modified 0 :cancelled 1 :exceptions 0}
   :subscriber {:fired 1 :modified 0 :cancelled 1 :exceptions 0}})

(def ^:private cancellation-exception-class-name
  "nihilite.crafter.HookCancelledException")

(Class/forName "java.util.BitSet")

(def ^:private observe-target (java.util.BitSet.))
(def ^:private modify-target (java.util.BitSet.))
(def ^:private cancel-target (java.util.BitSet.))
(def ^:private subscriber-target (java.util.BitSet.))

(defn- check! [condition message data]
  (when-not condition
    (throw (ex-info message data))))

(defn- install-action! [spec]
  (let [installed? (api/install! spec)]
    (when-not (true? installed?)
      (throw (ex-info "the actions example requires a new hook installation"
                      {:nihilite/kind :nihilite/action-example-install-failed
                       :hook-id (:id spec)
                       :installed? installed?})))))

(defn- status-counter [statuses action counter]
  (let [value (get-in statuses [action counter])]
    (when-not (number? value)
      (throw (ex-info "the actions example status is missing a runtime counter"
                      {:nihilite/kind :nihilite/action-example-status-invalid
                       :action action
                       :counter counter
                       :statuses statuses})))
    (long value)))

(defn- counter-delta [before after action counter]
  (- (status-counter after action counter)
     (status-counter before action counter)))

(defn- check-counter-deltas! [before after]
  (doseq [[action expected-counters] expected-counter-deltas
          [counter expected-delta] expected-counters]
    (let [actual-delta (counter-delta before after action counter)]
      (check! (= expected-delta actual-delta)
              (str "the " (name action) " action has an unexpected "
                   (name counter) " delta")
              {:nihilite/kind :nihilite/action-example-counter-mismatch
               :action action
               :counter counter
               :expected expected-delta
               :actual actual-delta
               :before before
               :after after}))))

(defn- exercise-cancel! []
  (let [outcome (try
                  {:result (.cardinality cancel-target)}
                  (catch RuntimeException exception
                    {:exception exception}))]
    (if-let [exception (:exception outcome)]
      (do
        (check! (= cancellation-exception-class-name
                   (.getName (class exception)))
                "the cancel action did not expose the project's cancellation exception"
                {:nihilite/kind :nihilite/action-example-cancel-exception
                 :hook-id (:cancel hook-ids)
                 :expected cancellation-exception-class-name
                 :actual (.getName (class exception))})
        exception)
      (throw (ex-info "the cancel action did not short-circuit the original body"
                      {:nihilite/kind :nihilite/action-example-cancel-returned
                       :hook-id (:cancel hook-ids)
                       :result (:result outcome)})))))

(defn action-statuses
  "Return the public install and runtime status for each built-in action."
  []
  (reduce-kv (fn [statuses action hook-id]
                (assoc statuses action (api/install-status! hook-id)))
              {}
              hook-ids))

(defn exercise-actions!
  "Exercise all four actions and return their distinct observable results.

   The cancel result exposes the cancellation exception. The subscriber
   result includes its fired and cancelled state while returning the original
   BitSet.get result."
  []
  (let [before (action-statuses)
        observe-result (.isEmpty observe-target)
        modify-result (.size modify-target)
        cancel-exception (exercise-cancel!)
        subscriber-result (.get subscriber-target 0)
        after (action-statuses)
        subscriber-fired? (= 1 (counter-delta before after :subscriber :fired))
        subscriber-cancelled? (= 1 (counter-delta before after :subscriber :cancelled))]
    (check! (true? observe-result)
            "the observe action changed the original boolean result"
            {:nihilite/kind :nihilite/action-example-observe-result
             :hook-id (:observe hook-ids)
             :expected true
             :actual observe-result})
    (check! (= 7 modify-result)
            "the modify action did not replace the original int result"
            {:nihilite/kind :nihilite/action-example-modify-result
             :hook-id (:modify hook-ids)
             :expected 7
             :actual modify-result})
    (check! (false? subscriber-result)
            "the subscriber action changed the original boolean result"
            {:nihilite/kind :nihilite/action-example-subscriber-result
             :hook-id (:subscriber hook-ids)
             :expected false
             :actual subscriber-result})
    (check! subscriber-fired?
            "the subscriber action did not record a fired event"
            {:nihilite/kind :nihilite/action-example-subscriber-not-fired
             :hook-id (:subscriber hook-ids)})
    (check! subscriber-cancelled?
            "the subscriber action did not record a cancelled event"
            {:nihilite/kind :nihilite/action-example-subscriber-not-cancelled
             :hook-id (:subscriber hook-ids)})
    (check-counter-deltas! before after)
    {:observe {:result observe-result}
     :modify {:result modify-result}
     :cancel {:exception cancel-exception
              :exception-class (.getName (class cancel-exception))}
     :subscriber {:result subscriber-result
                  :fired? subscriber-fired?
                  :cancelled? subscriber-cancelled?}}))

(defn cleanup-actions!
  "Uninstall all four action hooks and return each action's removal result."
  []
  (let [results (atom [])
        failures (atom [])]
    (doseq [[action hook-id] hook-ids]
      (try
        (swap! results conj [action (api/uninstall! hook-id)])
        (catch Throwable exception
          (swap! failures conj {:action action
                                :hook-id hook-id
                                :exception exception}))))
    (if-let [failure (first @failures)]
      (throw (ex-info "the actions example could not uninstall every hook"
                      {:nihilite/kind :nihilite/action-example-cleanup-failed
                       :failures @failures}
                      (:exception failure)))
      (into {} @results))))

(install-action!
 {:id (:observe hook-ids)
  :target-internal "java/util/BitSet"
  :method-name "isEmpty"
  :descriptor "()Z"
  :position :entry
  :action :observe
  :bridge (fn [_ctx] nil)
  :note "observe an entry action while preserving the original result"})

(install-action!
 {:id (:modify hook-ids)
  :target-internal "java/util/BitSet"
  :method-name "size"
  :descriptor "()I"
  :position :return
  :action :modify
  :bridge (fn [_ctx] 7)
  :note "modify a return action with a compatible int replacement"})

(install-action!
 {:id (:cancel hook-ids)
  :target-internal "java/util/BitSet"
  :method-name "cardinality"
  :descriptor "()I"
  :position :entry
  :action :cancel
  :bridge (fn [_ctx] nil)
  :note "cancel an entry action and short-circuit the original body"})

(install-action!
 {:id (:subscriber hook-ids)
  :target-internal "java/util/BitSet"
  :method-name "get"
  :descriptor "(I)Z"
  :position :entry
  :action :subscriber
  :bridge (fn [_ctx] nil)
  :note "record subscriber cancellation while preserving the original result"})

(println "[examples.actions.init] loaded. Call"
         "(examples.actions.init/exercise-actions!),"
         "(examples.actions.init/action-statuses), and"
         "(examples.actions.init/cleanup-actions!).")
(flush)
