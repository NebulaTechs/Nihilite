(ns probe.examples.actions
  "Deterministic verification probe for the four-action comparison example.

   The example is loaded by absolute path before this probe, so its namespace
   and public functions are resolved from the runtime namespace registry."
  (:require [nihilite.builder.api :as api]))

(def ^:private example-namespace-symbol 'examples.actions.init)

(def ^:private expected-counter-deltas
  {:observe {:fired 1 :modified 0 :cancelled 0 :exceptions 0}
   :modify {:fired 1 :modified 1 :cancelled 0 :exceptions 0}
   :cancel {:fired 1 :modified 0 :cancelled 1 :exceptions 0}
   :subscriber {:fired 1 :modified 0 :cancelled 1 :exceptions 0}})

(def ^:private expected-actions
  #{:observe :modify :cancel :subscriber})

(defn- check! [condition message data]
  (when-not condition
    (throw (ex-info message data))))

(defn- resolve-example-namespace! []
  (or (find-ns example-namespace-symbol)
      (throw (ex-info "the actions example namespace is not loaded"
                      {:nihilite/kind :nihilite/actions-probe-namespace-missing
                       :namespace example-namespace-symbol}))))

(defn- resolve-example-var! [example-ns symbol-name]
  (or (ns-resolve example-ns symbol-name)
      (throw (ex-info "the actions example is missing a public function"
                      {:nihilite/kind :nihilite/actions-probe-var-missing
                       :namespace example-namespace-symbol
                       :symbol symbol-name}))))

(defn- status-counter [statuses action counter]
  (let [value (get-in statuses [action counter])]
    (when-not (number? value)
      (throw (ex-info "the actions example status is missing a runtime counter"
                      {:nihilite/kind :nihilite/actions-probe-status-invalid
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
              {:nihilite/kind :nihilite/actions-probe-counter-mismatch
               :action action
               :counter counter
               :expected expected-delta
               :actual actual-delta
               :before before
               :after after}))))

(defn- cancellation-exception-class! []
  (try
    (Class/forName "nihilite.crafter.HookCancelledException")
    (catch ClassNotFoundException exception
      (throw (ex-info "the project's cancellation exception class is unavailable"
                      {:nihilite/kind :nihilite/actions-probe-cancel-class-missing
                       :class-name "nihilite.crafter.HookCancelledException"}
                      exception)))))

(let [example-ns (resolve-example-namespace!)
      exercise-actions! (resolve-example-var! example-ns 'exercise-actions!)
      action-statuses (resolve-example-var! example-ns 'action-statuses)
      cleanup-actions! (resolve-example-var! example-ns 'cleanup-actions!)
      cleanup-results (atom nil)]
  (try
    (let [before (action-statuses)
          results (exercise-actions!)
          after (action-statuses)
          registered-specs (set (api/list-specs))
          cancellation-class (cancellation-exception-class!)
          cancel-exception (get-in results [:cancel :exception])]
      (check! (true? (get-in results [:observe :result]))
              "observe did not preserve the original BitSet.isEmpty result"
              {:nihilite/kind :nihilite/actions-probe-observe-result
               :expected true
               :actual (get-in results [:observe :result])})
      (check! (= 7 (get-in results [:modify :result]))
              "modify did not replace the BitSet.size result"
              {:nihilite/kind :nihilite/actions-probe-modify-result
               :expected 7
               :actual (get-in results [:modify :result])})
      (check! (some? cancel-exception)
              "cancel did not expose an exception"
              {:nihilite/kind :nihilite/actions-probe-cancel-result
               :results results})
      (check! (= "nihilite.crafter.HookCancelledException"
                 (get-in results [:cancel :exception-class]))
              "cancel exposed the wrong exception class"
              {:nihilite/kind :nihilite/actions-probe-cancel-class
               :expected "nihilite.crafter.HookCancelledException"
               :actual (get-in results [:cancel :exception-class])})
      (check! (instance? cancellation-class cancel-exception)
              "cancel did not expose the project's cancellation exception"
              {:nihilite/kind :nihilite/actions-probe-cancel-instance
               :expected cancellation-class
               :actual (class cancel-exception)})
      (check! (false? (get-in results [:subscriber :result]))
              "subscriber did not preserve the original BitSet.get result"
              {:nihilite/kind :nihilite/actions-probe-subscriber-result
               :expected false
               :actual (get-in results [:subscriber :result])})
      (check! (true? (get-in results [:subscriber :fired?]))
              "subscriber did not record a fired event"
              {:nihilite/kind :nihilite/actions-probe-subscriber-fired
               :results results})
      (check! (true? (get-in results [:subscriber :cancelled?]))
              "subscriber did not record a cancelled event"
              {:nihilite/kind :nihilite/actions-probe-subscriber-cancelled
               :results results})
      (doseq [action expected-actions]
        (let [status (get after action)
              spec-id (:spec-id status)]
          (check! (some? status)
                  "an action is missing its public status"
                  {:nihilite/kind :nihilite/actions-probe-status-missing
                   :action action
                   :statuses after})
          (check! (true? (:registered? status))
                  "an action hook is not registered"
                  {:nihilite/kind :nihilite/actions-probe-not-registered
                   :action action
                   :status status})
          (check! (= 1 (:woven-count status))
                  "an action hook was not woven exactly once"
                  {:nihilite/kind :nihilite/actions-probe-woven-count
                   :action action
                   :expected 1
                   :actual (:woven-count status)})
          (check! (false? (:pending? status))
                  "an action hook is still pending"
                  {:nihilite/kind :nihilite/actions-probe-pending
                   :action action
                   :status status})
          (check! (nil? (:last-error status))
                  "an action hook has an installation error"
                  {:nihilite/kind :nihilite/actions-probe-install-error
                   :action action
                   :status status})
          (check! (contains? registered-specs spec-id)
                  "an action hook is absent from the public spec list"
                  {:nihilite/kind :nihilite/actions-probe-spec-list
                   :action action
                   :spec-id spec-id
                   :registered-specs registered-specs})))
      (check-counter-deltas! before after))
    (finally
      (reset! cleanup-results (cleanup-actions!))))
  (check! (= {:observe true
              :modify true
              :cancel true
              :subscriber true}
             @cleanup-results)
          "the actions example did not clean up every hook"
          {:nihilite/kind :nihilite/actions-probe-cleanup
           :expected {:observe true
                      :modify true
                      :cancel true
                      :subscriber true}
           :actual @cleanup-results})
  (println "nihilite:example-pass actions")
  (flush))
