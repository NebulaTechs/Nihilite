(ns probe.examples.lifecycle
  "Deterministic verification probe for the lifecycle example.

   The example is loaded from its file before this probe runs. Its namespace
   and public vars are resolved from the runtime namespace registry, so the
   probe does not depend on examples/ being present on the classpath."
  (:require [nihilite.builder.api :as api]))

(def ^:private example-namespace-symbol 'examples.lifecycle.init)

(defn- resolve-example-var
  "Resolve `symbol-name` in the already-loaded example namespace."
  [symbol-name]
  (let [example-namespace (find-ns example-namespace-symbol)]
    (when-not example-namespace
      (throw (ex-info "lifecycle example namespace is not loaded"
                      {:namespace example-namespace-symbol})))
    (or (ns-resolve example-namespace symbol-name)
        (throw (ex-info "lifecycle example var is missing"
                        {:namespace example-namespace-symbol
                         :symbol symbol-name})))))

(defn- check!
  "Throw with `message` and `data` when `condition` is not true."
  [condition message data]
  (when-not condition
    (throw (ex-info message data))))

(let [exercise-var (resolve-example-var 'exercise)
      hook-id-var (resolve-example-var 'hook-id)
      exercise @exercise-var
      hook-id @hook-id-var
      result (exercise)
      active-status (:status-before-swap result)
      final-status (:status-after-swap result)]
  (check! (map? result)
          "the lifecycle exercise did not return its observed state"
          {:result result})
  (check! (= hook-id (:hook-id result))
          "the lifecycle exercise returned the wrong hook id"
          {:expected hook-id
           :actual (:hook-id result)})
  (check! (true? (:installed? result))
          "the lifecycle exercise did not install the hook"
          {:installed? (:installed? result)})
  (check! (true? (:swapped? result))
          "the lifecycle exercise did not swap the bridge"
          {:swapped? (:swapped? result)})
  (check! (true? (:uninstalled? result))
          "the lifecycle exercise did not uninstall the hook"
          {:uninstalled? (:uninstalled? result)})
  (check! (= {:initial 1 :swapped 0}
             (:bridge-calls-before-swap result))
          "the first target invocation did not reach only the initial bridge"
          {:bridge-calls (:bridge-calls-before-swap result)})
  (check! (= {:initial 1 :swapped 1}
             (:bridge-calls-after-swap result))
          "the second target invocation did not reach the swapped bridge"
          {:bridge-calls (:bridge-calls-after-swap result)})
  (check! (and (number? (:fired active-status))
               (pos? (:fired active-status)))
          "the lifecycle hook did not fire while active"
          {:status active-status})
  (check! (and (number? (:woven-count active-status))
               (pos? (:woven-count active-status)))
          "the lifecycle hook was not woven while active"
          {:status active-status})
  (check! (false? (:pending? active-status))
          "the lifecycle hook was still pending while active"
          {:status active-status})
  (check! (true? (:registered? active-status))
          "the lifecycle hook was not registered while active"
          {:status active-status})
  (check! (nil? (:last-error active-status))
          "the lifecycle hook recorded an installation error"
          {:status active-status})
  (check! (and (number? (:fired final-status))
               (number? (:fired active-status))
               (> (:fired final-status) (:fired active-status)))
          "the second target invocation did not fire the lifecycle hook"
          {:active-status active-status
           :final-status final-status})
  ;; The type of the list is not the point: that the hook is GONE from it is,
  ;; and the next assertion says so. list-specs returns a list, so a
  ;; vector? check here failed on an empty list -- the exact case where the
  ;; hook is correctly absent.
  (check! (sequential? (:spec-ids-after-uninstall result))
          "the lifecycle exercise did not report its public spec list"
          {:spec-ids (:spec-ids-after-uninstall result)})
  (check! (nil? (:lookup-after-uninstall result))
          "the lifecycle hook remained in the exercise lookup result"
          {:lookup-after-uninstall (:lookup-after-uninstall result)})
  (check! (not (some #(= hook-id %) (:spec-ids-after-uninstall result)))
          "the lifecycle hook remained in the exercise spec list"
          {:hook-id hook-id
           :spec-ids (:spec-ids-after-uninstall result)})
  (check! (nil? (api/lookup hook-id))
          "the lifecycle hook remained in public lookup after uninstall"
          {:hook-id hook-id})
  (check! (not (some #(= hook-id %) (api/list-specs)))
          "the lifecycle hook remained in the public spec list after uninstall"
          {:hook-id hook-id
           :spec-ids (api/list-specs)})
  (println "nihilite:example-pass lifecycle")
  (flush))
