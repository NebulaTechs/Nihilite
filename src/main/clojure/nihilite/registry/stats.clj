(ns nihilite.registry.stats
  "Per-spec counter records + driver-observation atoms.
   Pure data layer — depends on no other nihilite namespace. Used by
   nihilite.registry (install! / uninstall! / clear! to seed/remove
   counter records) and nihilite.registry.dispatch (bump-*/get-stats
   during event firing)."
  (:import [java.util.concurrent ConcurrentHashMap]))

(defrecord StatsRecord
  ;; last-ns and max-ns were timing fields for a latency measurement that
  ;; nothing performs. :fired, :modified, :cancelled and :exceptions are the
  ;; four install-status! reports, and all four are incremented.
  [fired modified cancelled exceptions])

(defonce ^:private stats-index
  (ConcurrentHashMap.))

(def driver-throw-observed (atom 0))
(def driver-body-executed-after-cancel? (atom false))
(def driver-redefine-body-executed?
  "Set by the retransform driver's :redefine target body. A :redefine hook
   wraps the method, so this must stay false — it is what proves the
   original body is genuinely replaced rather than merely having its
   return value overridden."
  (atom false))

(defn increment-throw-observed!
  "Bumps the throw observation counter used by nihilite.test.retransformDriver.
   Reset by clear-driver-state!."
  []
  (swap! driver-throw-observed inc))

(defn clear-driver-state!
  "Resets driver observation counters. Called between driver test phases."
  []
  (reset! driver-throw-observed 0)
  (reset! driver-body-executed-after-cancel? false)
  (reset! driver-redefine-body-executed? false))

(defn- fresh-record ^StatsRecord []
  (->StatsRecord (atom 0) (atom 0) (atom 0) (atom 0)))

(defn ensure-stats ^StatsRecord [spec-id]
  (let [id (str spec-id)
        existing ^StatsRecord (.get ^ConcurrentHashMap stats-index id)]
    (if (nil? existing)
      (let [created (fresh-record)]
        (if (nil? (.putIfAbsent ^ConcurrentHashMap stats-index id created))
          created
          ^StatsRecord (.get ^ConcurrentHashMap stats-index id)))
      existing)))

(defn get-stats ^StatsRecord [spec-id]
  (.get ^ConcurrentHashMap stats-index (str spec-id)))

(defn remove-stats [spec-id]
  (some? (.remove ^ConcurrentHashMap stats-index (str spec-id))))

(defn stats-snapshot []
  (into {} stats-index))

(defn clear!
  []
  (.clear ^ConcurrentHashMap stats-index)
  nil)

(defn bump-fired!      [spec-id] (when-let [r (get-stats spec-id)] (swap! (:fired r) inc)))
(defn bump-exception!  [spec-id] (when-let [r (get-stats spec-id)] (swap! (:exceptions r) inc)))
(defn bump-cancelled!  [spec-id] (when-let [r (get-stats spec-id)] (swap! (:cancelled r) inc)))
