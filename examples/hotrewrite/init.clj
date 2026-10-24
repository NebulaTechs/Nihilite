(ns examples.hotrewrite.init
  "Demonstrate hot rewrite: swap-bridge! replaces a hook's bridge at
   runtime without restarting the JVM.

   The target is `java.util.BitSet.size`, a measured-safe bootstrap target.
   The point of the example is the `swap-bridge!` pattern: rewiring a live
   bridge at runtime so consecutive calls reach different versions."
  (:require [nihilite.builder.api :as api]))

(def ^:private hook-id "hotrewrite-bitset-size")
(def ^:private calls-by-version (atom {:v1 0 :v2 0}))
;; Load BitSet before install! so the target class can be retransformed.
(def ^:private target (java.util.BitSet. 1))

(defn- count-call! [version]
  (swap! calls-by-version update version inc))

(def ^:private v1-bridge
  (fn [_]
    (count-call! :v1)))

(def ^:private v2-bridge
  (fn [_]
    (count-call! :v2)))

(def ^:private installed?
  (api/install!
   {:id              hook-id
    :target-internal "java/util/BitSet"
    :method-name     "size"
    :descriptor      "()I"
    :position        :entry
    :action          :observe
    :bridge          v1-bridge
    :note            "bridge v1; swap-bridge! replaces it with bridge v2"}))

(when-not installed?
  (throw (ex-info "failed to install hotrewrite example hook"
                  {:hook-id hook-id
                   :result installed?})))

(defn call-target!
  "Calls the hooked BitSet.size method once."
  []
  (.size target))

(defn rewrite-to-v2!
  "Replaces the live bridge with bridge v2."
  []
  (if (api/swap-bridge! hook-id v2-bridge)
    true
    (throw (ex-info "failed to swap hotrewrite example bridge"
                    {:hook-id hook-id}))))

(defn call-counts
  "Returns the call count for each bridge version."
  []
  @calls-by-version)

(defn status
  "Returns the public install and runtime status for the example hook."
  []
  (api/install-status! hook-id))

(println "[examples.hotrewrite.init] loaded. Call"
         "(examples.hotrewrite.init/call-target!), then"
         "(examples.hotrewrite.init/rewrite-to-v2!), then"
         "(examples.hotrewrite.init/call-target!) again.")
(flush)
