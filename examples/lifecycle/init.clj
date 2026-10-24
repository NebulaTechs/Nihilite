(ns examples.lifecycle.init
  "Teaching example for the complete public hook lifecycle.

   The example installs an entry hook on `java.util.BitSet.size()I`, calls
   the target, inspects the public installation status, replaces the live
   bridge, calls the target again, and removes the hook. It finishes by
   checking that both public lookup and the public spec list no longer
   contain the example hook.

   Every mutating API call checks its return value. A failed lifecycle step
   throws immediately, and a failure before the explicit uninstall triggers
   cleanup without hiding the original exception."
  (:require [nihilite.builder.api :as api]))

(def hook-id
  "Public identifier used by the lifecycle example and its probe."
  "lifecycle-bitset-size")

(def ^:private target
  (java.util.BitSet. 1))

(def ^:private bridge-calls
  (atom {:initial 0 :swapped 0}))

(defn- require-true
  "Return `value` when it is true, otherwise fail with `operation` details."
  [value operation]
  (when-not (true? value)
    (throw (ex-info (str "lifecycle example failed to " operation)
                    {:hook-id hook-id
                     :operation operation
                     :value value})))
  value)

(defn- initial-bridge
  "Record calls made through the bridge installed by `install!`."
  [_context]
  (swap! bridge-calls update :initial inc))

(defn- swapped-bridge
  "Record calls made through the bridge installed by `swap-bridge!`."
  [_context]
  (swap! bridge-calls update :swapped inc))

(defn- invoke-target!
  "Invoke the hooked `java.util.BitSet.size()I` method once."
  []
  (.size target))

(defn- install-hook!
  "Install a fresh hook and require the public API to report success."
  []
  (when (api/lookup hook-id)
    (throw (ex-info "lifecycle example requires an unregistered hook id"
                    {:hook-id hook-id})))
  (require-true
   (api/install!
    {:id              hook-id
     :target-internal "java/util/BitSet"
     :method-name     "size"
     :descriptor      "()I"
     :position        :entry
     :action          :observe
     :bridge          initial-bridge
     :note            "demonstrate install, inspect, swap, and uninstall"})
   "install the hook"))

(defn- swap-bridge!
  "Replace the live bridge and require the public API to report success."
  []
  (require-true (api/swap-bridge! hook-id swapped-bridge)
                "swap the bridge"))

(defn- uninstall-hook!
  "Remove the hook and require the public API to report success."
  []
  (require-true (api/uninstall! hook-id)
                "uninstall the hook"))

(defn- verify-cleanup!
  "Return the public cleanup state after the hook has been uninstalled."
  []
  (let [remaining-spec (api/lookup hook-id)
        spec-ids (api/list-specs)]
    (when-not (nil? remaining-spec)
      (throw (ex-info "lifecycle example hook remains in public lookup"
                      {:hook-id hook-id
                       :remaining-spec remaining-spec})))
    (when (some #(= hook-id %) spec-ids)
      (throw (ex-info "lifecycle example hook remains in the public spec list"
                      {:hook-id hook-id
                       :spec-ids spec-ids})))
    {:remaining-spec remaining-spec
     :spec-ids spec-ids}))

(defn exercise
  "Run and verify the complete public hook lifecycle.

   The returned map contains the status observed while the hook was active,
   the bridge call counts before and after the swap, and the public cleanup
   state observed after uninstall."
  []
  (reset! bridge-calls {:initial 0 :swapped 0})
  (let [installed? (install-hook!)]
    (try
      (invoke-target!)
      (let [status-before-swap (api/install-status! hook-id)
            calls-before-swap @bridge-calls
            swapped? (swap-bridge!)
            _ (invoke-target!)
            calls-after-swap @bridge-calls
            status-after-swap (api/install-status! hook-id)
            uninstalled? (uninstall-hook!)
            cleanup (verify-cleanup!)]
        {:hook-id hook-id
         :installed? installed?
         :status-before-swap status-before-swap
         :bridge-calls-before-swap calls-before-swap
         :swapped? swapped?
         :bridge-calls-after-swap calls-after-swap
         :status-after-swap status-after-swap
         :uninstalled? uninstalled?
         :lookup-after-uninstall (:remaining-spec cleanup)
         :spec-ids-after-uninstall (:spec-ids cleanup)})
      (catch Throwable failure
        (try
          (when (api/lookup hook-id)
            (require-true (api/uninstall! hook-id)
                          "clean up the hook after a failed lifecycle step"))
          (catch Throwable cleanup-failure
            (.addSuppressed failure cleanup-failure)))
        (throw failure)))))
