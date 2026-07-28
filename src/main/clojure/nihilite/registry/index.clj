(ns nihilite.registry.index
  "Concurrent indexes that back the P0 hook registry.

   Three ConcurrentHashMaps:
     by-id      <String id>             → spec map
     by-target  <String class-internal> → CopyOnWriteArrayList<spec>
     by-method  <String method-key>     → CopyOnWriteArrayList<spec>

   `by-target` is what the ByteBuddy HookTypeMatcher iterates on
   per class-load; `by-method` is the descriptor-keyed P0 lookup
   target. The two are maintained in lock-step by
   `nihilite.registry.install`."
  (:import [java.util.concurrent ConcurrentHashMap
                                 CopyOnWriteArrayList]
           [java.util.concurrent.atomic AtomicLong]))

;; The actual map objects live here. Other sub-namespaces
;; require this ns and access the maps through the accessors
;; defined below; that way every reference goes through one
;; symbol, and any future swap (e.g. Caffeine) is local.

(defonce ^:private by-id
  (ConcurrentHashMap.))

(defonce ^:private by-target
  (ConcurrentHashMap.))

(defonce ^:private by-method
  (ConcurrentHashMap.))

(defonce ^:private ^AtomicLong sequence-counter
  (AtomicLong.))

;; ---------------------------------------------------------------------------
;; Public index accessors (used by install / dispatch)
;; ---------------------------------------------------------------------------

(defn get-by-id
  "The id → spec ConcurrentHashMap. Zero-arg accessor; the map
   itself is the return value."
  ^ConcurrentHashMap [] by-id)

(defn get-by-target
  "The class-internal → spec-list ConcurrentHashMap."
  ^ConcurrentHashMap [] by-target)

(defn get-by-method
  "The method-key → spec-list ConcurrentHashMap."
  ^ConcurrentHashMap [] by-method)

;; ---------------------------------------------------------------------------
;; Bucket helpers
;; ---------------------------------------------------------------------------

(defn bucket
  "Get-or-create the CopyOnWrite list for `target-internal`."
  ^java.util.List [t]
  (or (.get by-target t)
      (let [fresh (CopyOnWriteArrayList.)]
        (if (nil? (.putIfAbsent by-target t fresh))
          fresh
          (.get by-target t)))))

(defn method-bucket
  "Get-or-create the CopyOnWrite list for the canonical
   method-key string."
  ^java.util.List [mk]
  (or (.get by-method mk)
      (let [fresh (CopyOnWriteArrayList.)]
        (if (nil? (.putIfAbsent by-method mk fresh))
          fresh
          (.get by-method mk)))))

(defn next-sequence
  []
  (.incrementAndGet ^AtomicLong sequence-counter))

(defn clear-all!
  "Drop every spec. Test/diagnostic only."
  []
  (.clear by-id)
  (.clear by-target)
  (.clear by-method)
  nil)
