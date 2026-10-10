(ns nihilite.builder.registry.stats
  "Per-spec counter records.
   Pure data layer — depends on no other nihilite namespace. Used by
   nihilite.builder.registry (install! / uninstall! / clear! to seed/remove
   counter records) and nihilite.builder.registry.dispatch (bump-*/get-stats
   during event firing).

   The driver-observation atoms that used to live here moved to
   nihilite.test.driver-observe on 2026-10-10 — no src/main code read
   them, so they were test-only state shipping in the uberjar."
  (:import [java.util.concurrent ConcurrentHashMap]))

(defrecord StatsRecord
  ;; last-ns and max-ns were timing fields for a latency measurement that
  ;; nothing performs. :fired, :modified, :cancelled and :exceptions are the
  ;; four install-status! reports, and all four are incremented.
  [fired modified cancelled exceptions])

(defonce ^:private stats-index
  (ConcurrentHashMap.))

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
