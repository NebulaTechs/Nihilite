(ns nihilite.registry.stats
  "Per-spec runtime statistics. Live in a parallel
   `ConcurrentHashMap<spec-id, StatsRecord>`, NOT on HookSpec —
   `^{:transient true}` defrecord-field metadata is not a Clojure
   convention (would be passed to the JVM as an unknown
   annotation)."
  (:import [java.util.concurrent ConcurrentHashMap]))

(defrecord StatsRecord
  [fired modified cancelled exceptions last-ns max-ns])

(defonce ^:private ^ConcurrentHashMap stats-index
  (ConcurrentHashMap.))

(defn- fresh-record
  ^StatsRecord []
  (->StatsRecord (atom 0) (atom 0) (atom 0) (atom 0) (atom 0) (atom 0)))

(defn ensure-stats
  "Get-or-create the StatsRecord for `spec-id`. Idempotent."
  ^StatsRecord [spec-id]
  (let [id (str spec-id)
        existing ^StatsRecord (.get stats-index id)]
    (if (nil? existing)
      (let [created (fresh-record)]
        (if (nil? (.putIfAbsent stats-index id created))
          created
          ^StatsRecord (.get stats-index id)))
      existing)))

(defn get-stats
  "Return the StatsRecord for `spec-id`, or nil."
  ^StatsRecord [spec-id]
  (.get stats-index (str spec-id)))

(defn remove-stats
  "Drop StatsRecord for spec-id. Returns true if removed."
  [spec-id]
  (some? (.remove stats-index (str spec-id))))

(defn snapshot
  "Defensive copy of {spec-id → StatsRecord}."
  []
  (let [m (java.util.HashMap.)]
    (.putAll m stats-index)
    m))

(defn clear!
  "Drop every StatsRecord. Test/diagnostic only."
  []
  (.clear stats-index)
  nil)

(defn bump-fired!
  [spec-id]
  (when-let [r (get-stats spec-id)]
    (swap! (:fired r) inc)))

(defn bump-modified!
  [spec-id]
  (when-let [r (get-stats spec-id)]
    (swap! (:modified r) inc)))

(defn bump-cancelled!
  [spec-id]
  (when-let [r (get-stats spec-id)]
    (swap! (:cancelled r) inc)))

(defn bump-exception!
  [spec-id]
  (when-let [r (get-stats spec-id)]
    (swap! (:exceptions r) inc)))

(defn record-elapsed!
  "Store `elapsed-ns` to `:last-ns`; bump `:max-ns` if greater."
  [spec-id elapsed-ns]
  (when-let [r (get-stats spec-id)]
    (swap! (:last-ns r) (constantly elapsed-ns))
    (let [cur @(:max-ns r)]
      (when (> elapsed-ns cur)
        (swap! (:max-ns r) (constantly elapsed-ns))))))
