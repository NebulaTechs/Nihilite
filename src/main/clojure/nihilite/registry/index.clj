(ns nihilite.registry.index
  "The read side of the registry, split out so the weaving machinery depends
   on a query surface rather than on the registry's mutation internals.

   Two things live here, and only two:

     - which specs target a given class
     - a counter that changes whenever those specs change

   Both exist for one consumer. The transformer has to answer \"does this
   class have any hook, and if not, can I remember that answer\" for every
   class the JVM loads, which is why it caches a negative result and needs a
   signal telling it when to stop trusting the cache. Nothing else needs
   either.

   Writing stays in nilitite.registry: install!, uninstall! and clear! own the
   lock and the mutation, and they call in here to update the index. That
   keeps the by-target map and the revision counter next to the operations
   that must keep them consistent, while the kernel only ever sees queries."
  (:import [java.util.concurrent ConcurrentHashMap CopyOnWriteArrayList]
           [java.util.concurrent.atomic AtomicLong]))

(defonce ^:private by-target
  (ConcurrentHashMap.))

(defonce ^:private ^AtomicLong revision-counter
  (AtomicLong. 0))

(defn bucket
  "The mutable spec list for `target-internal`, created on first use."
  ^java.util.List [target-internal]
  (or (.get by-target target-internal)
      (let [fresh (CopyOnWriteArrayList.)]
        (if (nil? (.putIfAbsent by-target target-internal fresh))
          fresh
          (.get by-target target-internal)))))

(defn live-bucket
  "The mutable spec list for `target-internal`, or nil when it has none. This
   is the list itself, not a copy: callers that remove a spec must use it, or
   the removal applies to a snapshot and is lost."
  ^java.util.List [target-internal]
  (.get by-target target-internal))

(defn matching
  "Specs registered for `target-internal`. Never nil, so a caller iterating the
   result needs no nil check. A copy: safe to read while a mutation is in
   flight, useless for removing from."
  ^java.util.List [target-internal]
  (if-let [b (.get by-target target-internal)]
    (vec b)
    []))

(defn bump-revision!
  "Invalidates every cached negative match. Called on each mutation while the
   registry lock is held, so a weave never observes a bumped counter with an
   index that has not caught up."
  []
  (.incrementAndGet ^AtomicLong revision-counter)
  nil)

(defn revision
  "Current index revision. Changes exactly when `matching` could return
   something different."
  []
  (.get ^AtomicLong revision-counter))

(defn forget-target!
  "Drops the entry for `target-internal` when its last spec was removed. Takes
   the bucket the caller already removed the spec from, so this cannot race
   with a concurrent install adding a new one to a different bucket."
  [target-internal ^java.util.List b]
  (when (and b (.isEmpty b))
    (.remove by-target target-internal b))
  nil)

(defn clear!
  "Drops every index entry. The caller has already emptied the buckets."
  []
  (.clear by-target)
  nil)
