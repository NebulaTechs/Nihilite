(ns nihilite.observers.status
  "`hooks/status` operator-facing query. Returns a snapshot
   map (NOT a live atom); cheap to call."
  (:require [nihilite.registry :as reg]
            [nihilite.registry.stats :as stats]))

(defn- spec-row [spec]
  (let [id (:id spec)
        r  (stats/get-stats id)]
    {:id         id
     :target     (:target-internal spec)
     :method     (:method-name spec)
     :position   (:position spec)
     :arity      (:arity spec)
     :action     (:action spec)
     :tag        (:tag spec)
     :fired      (if r @(:fired r) 0)
     :modified   (if r @(:modified r) 0)
     :cancelled  (if r @(:cancelled r) 0)
     :exceptions (if r @(:exceptions r) 0)
     :last-ns    (if r @(:last-ns r) 0)
     :max-ns     (if r @(:max-ns r) 0)}))

(defn- take-progress [sub]
  (let [target (:fire-target sub)]
    (when (and target (pos? target))
      (let [n @(:fired sub)]
        (if (>= n target) 1.0 (double (/ n target)))))))

(defn- subscription-row [sub]
  {:id            (:id sub)
   :name          (:name sub)
   :sink          (:sink sub)
   :fired         @(:fired sub)
   :exception     @(:exception sub)
   :sink-errors   @(:sink-errors sub)
   :cancelled     @(:cancelled sub)
   :take-progress  (take-progress sub)})

(defn hooks-status
  "Return a snapshot of every spec + every subscription."
  []
  ;; `subscriptions` is a private atom in subscriber.clj; resolve at
  ;; call time to avoid a circular require between the two namespaces.
  (let [sub-var (resolve 'nihilite.observers.subscriber/subscriptions)
        subs    (when sub-var
                  (let [v @sub-var]
                    (mapv subscription-row (if (map? v) (vals v) []))))
        specs   (mapv spec-row (.values ^java.util.Map (reg/snapshot)))]
    {:total-specs         (count specs)
     :total-subscriptions (count subs)
     :specs               specs
     :subscriptions       (or subs [])}))
