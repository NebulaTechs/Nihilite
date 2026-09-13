(ns nihilite.registry
  "Generic, loader-agnostic registry of hook specs + state.

   install! / uninstall! / install-fresh! / clear! / replace-bridge! /
   install-status! are the data operations. Spec-event dispatch lives
   in nihilite.registry.dispatch; per-spec counters in
   nihilite.registry.stats. Keeping these split lets the data store
   stay focused on idempotent ops and concurrent triple-write
   atomicity (locking over by-id / by-target / by-method). This file
   is the single source of truth for the records (HookSpec /
   HookContext / HookEvent) and the position/action normalization."
  (:require [clojure.tools.logging :as log]
            [nihilite.registry.stats :as stats])
  (:import [java.util.concurrent ConcurrentHashMap CopyOnWriteArrayList]
           [java.util.concurrent.atomic AtomicLong]
           [java.lang.instrument Instrumentation]))

(defonce ^:private actions-registry
  (atom #{:observe :modify :cancel :subscriber}))

(defn registered-actions
  []
  @actions-registry)

(defn register-action!
  [action-key]
  (when (not (keyword? action-key))
    (throw (ex-info "action key must be a keyword"
                    {:nihilite/kind :nihilite/invalid-action-key
                     :nihilite/action action-key})))
  (swap! actions-registry conj action-key)
  action-key)

(defrecord HookSpec
  [id target-internal method-name position arity bridge note
   action method-key source-class source-descriptor tag])

(defrecord HookContext
  [hookId self args phase returnValue cancelled])

(defrecord HookEvent
  [spec-id source phase self args return-value throwable
   cancelled? cancel! thread-name timestamp-ns sequence note stack])

(defn method-key
  [class-internal method-name descriptor]
  (str class-internal "/" method-name "#" descriptor))

(defn normalize-position
  [p]
  (cond
    (keyword? p) p
    (string? p)  (case (.toUpperCase ^String p)
                   "ENTRY"    :entry
                   "RETURN"   :return
                   "THROW"    :throw
                   "REDEFINE" :redefine
                   :entry)
    :else        :entry))

(defn normalize-action
  [a]
  (cond
    (nil? a)     :observe
    (keyword? a) a
    (string? a)  (keyword (.toLowerCase ^String a))
    :else        a))

(defn spec
  ([id target-internal method-name position arity bridge note]
   (spec id target-internal method-name position arity bridge note nil :observe nil))
  ([id target-internal method-name position arity bridge note descriptor]
   (spec id target-internal method-name position arity bridge note descriptor :observe nil))
  ([id target-internal method-name position arity bridge note descriptor action]
   (spec id target-internal method-name position arity bridge note descriptor action nil))
  ([id target-internal method-name position arity bridge note descriptor action tag]
   (let [tid  (str target-internal)
         mn   (str method-name)
         desc (when descriptor (str descriptor))
         mk   (when (and desc (not (empty? desc)))
                (method-key tid mn desc))
         sc   (when mk (.replace ^String tid "/" "."))
         pos  (normalize-position position)
         act  (normalize-action action)]
     (map->HookSpec {:id                (str id)
                     :target-internal   tid
                     :method-name       mn
                     :position          pos
                     :arity             (when arity (int arity))
                     :bridge            bridge
                     :note              (str note)
                     :action            act
                     :method-key        mk
                     :source-class      sc
                     :source-descriptor desc
                     :tag               tag}))))

(defonce ^:private by-id
  (ConcurrentHashMap.))
(defonce ^:private by-target
  (ConcurrentHashMap.))
(defonce ^:private by-method
  (ConcurrentHashMap.))
(defonce ^:private ^Object registry-lock
  (Object.))
(defonce ^:private ^AtomicLong sequence-counter
  (AtomicLong.))

(defn get-by-id     ^ConcurrentHashMap [] by-id)
(defn get-by-target ^ConcurrentHashMap [] by-target)
(defn get-by-method ^ConcurrentHashMap [] by-method)

(defn next-sequence [] (.incrementAndGet ^AtomicLong sequence-counter))

(defn- get-or-create-bucket ^java.util.List [^ConcurrentHashMap m k]
  (or (.get m k)
      (let [fresh (CopyOnWriteArrayList.)]
        (if (nil? (.putIfAbsent m k fresh))
          fresh
          (.get m k)))))

(defn bucket        ^java.util.List [t]  (get-or-create-bucket by-target t))
(defn method-bucket ^java.util.List [mk] (get-or-create-bucket by-method mk))

(defn spec-bucket
  [spec]
  (if-let [mk (:method-key spec)]
    (some-> (.get by-method mk) seq)
    (some-> (.get by-target (:target-internal spec)) seq)))

(defn retransform-loaded-matching!
  [^String target-internal]
  (let [lookup-fn (resolve 'nihilite.kernel.agent/agent-currentInstrumentation)
        inst (when lookup-fn (lookup-fn))]
    (when inst
      (let [^Instrumentation inst inst
            dot-name (.replace ^String target-internal "/" ".")]
        (try
          (let [candidates (->> (.getAllLoadedClasses inst)
                                (filter (fn [^Class c]
                                          (and c (.equals dot-name (.getName c)))))
                                (filter (fn [^Class c] (.isModifiableClass inst c))))]
            (when (seq candidates)
              (.retransformClasses inst (into-array Class (vec candidates)))
              (log/debug "retransform-loaded-matching! retransformed"
                         (count candidates) "class(es) for target=" target-internal)))
          (catch java.lang.instrument.UnmodifiableClassException _
            (log/warn "retransform-loaded-matching! could not retransform"
                      target-internal " (UnmodifiableClassException)"))
          (catch Throwable t
            (log/warn t "retransform-loaded-matching! retransform failed for"
                      target-internal)))))))

(defonce ^:private status-index
  (java.util.concurrent.ConcurrentHashMap.))

(defn- status-record
  ^java.util.concurrent.atomic.AtomicReference [spec-id]
  (let [id (str spec-id)
        existing (.get status-index id)]
    (if (nil? existing)
      (let [created (java.util.concurrent.atomic.AtomicReference.
                      {:spec-id     id
                       :registered? true
                       :woven-count 0
                       :pending?    true
                       :last-error  nil})]
        (if (nil? (.putIfAbsent status-index id created))
          created
          (.get status-index id)))
      existing)))

(defn- record-status!
  [spec-id f]
  (let [ref ^java.util.concurrent.atomic.AtomicReference (status-record spec-id)]
    (.set ref (f (.get ref)))
    nil))

(defn- mark-installed! [spec-id count]
  (record-status! spec-id
    (fn [cur]
      (assoc cur :woven-count (long count)
                  :pending?    (zero? (long count))
                  :registered? true))))

(defn- mark-uninstalled! [spec-id count]
  (record-status! spec-id
    (fn [cur]
      (assoc cur :woven-count (long count)
                  :pending?    false
                  :registered? false))))

(defn- mark-error! [spec-id ex-msg]
  (record-status! spec-id
    (fn [cur]
      (assoc cur :last-error ex-msg))))

(defn install-status!
  [id]
  (let [id (str id)
        ref (.get status-index id)]
    (if (nil? ref)
      {:spec-id id :registered? false :woven-count 0 :pending? false :last-error nil}
      (let [m (.get ^java.util.concurrent.atomic.AtomicReference ref)]
        (assoc m :spec-id id)))))

(defn install!
  [spec]
  (let [{:keys [id target-internal method-name position arity
                descriptor action tag]} spec
        spec-id    (some-> id str)
        spec-target (some-> target-internal str)
        spec-method (some-> method-name str)
        spec-pos    (normalize-position position)
        spec-arity  (when arity (int arity))
        spec-desc   (some-> descriptor str)
        spec-action (if (contains? spec :action) (normalize-action action) :observe)
        spec-tag    (some-> tag str)
        desc-missing? (or (nil? spec-desc) (empty? spec-desc))
        spec-method-key (when-not desc-missing?
                          (method-key spec-target spec-method spec-desc))
        spec-source-class (when-not desc-missing?
                            (.replace ^String spec-target "/" "."))]
    (when (empty? spec-id)
      (throw (ex-info ":id required for HookSpec"
                      {:nihilite/kind :nihilite/missing-id
                       :nihilite/spec spec})))
    (when (empty? spec-target)
      (throw (ex-info ":target-internal required for HookSpec"
                      {:nihilite/kind :nihilite/missing-target
                       :nihilite/spec spec})))
    (when (empty? spec-method)
      (throw (ex-info ":method-name required for HookSpec"
                      {:nihilite/kind :nihilite/missing-method
                       :nihilite/spec spec})))
    (when (and (some? arity) (or (not (integer? arity)) (neg? arity)))
      (throw (ex-info ":arity must be a non-negative integer or nil"
                      {:nihilite/kind :nihilite/bad-arity
                       :nihilite/spec spec})))
    (when (and (some? tag) (empty? spec-tag))
      (throw (ex-info ":tag must be a non-empty string when present"
                      {:nihilite/kind :nihilite/bad-tag
                       :nihilite/id spec-id})))
    (when desc-missing?
      (throw (ex-info (str ":descriptor required for HookSpec id=" spec-id)
                      {:nihilite/kind :nihilite/missing-descriptor
                       :nihilite/id spec-id
                       :nihilite/target spec-target
                       :nihilite/method spec-method})))
    (when (and (some? spec-action) (not (contains? (registered-actions) spec-action)))
      (throw (ex-info (str ":action must be one of " (vec (registered-actions)))
                      {:nihilite/kind :nihilite/invalid-action
                       :nihilite/id spec-id
                       :nihilite/action spec-action})))
    (when (and (= spec-pos :redefine)
               (#{:modify :cancel} spec-action))
      (throw (ex-info (str ":action :modify|:cancel invalid on :position :redefine "
                            "(id=" spec-id ")")
                      {:nihilite/kind :nihilite/invalid-action-on-redefine
                       :nihilite/id spec-id
                       :nihilite/action spec-action
                       :nihilite/position spec-pos})))
    (when (and (= spec-action :cancel) (not= spec-pos :entry))
      (throw (ex-info (str ":action :cancel requires :position :entry "
                            "(got " spec-pos ")")
                      {:nihilite/kind :nihilite/cancel-requires-entry
                       :nihilite/id spec-id
                       :nihilite/action spec-action
                       :nihilite/position spec-pos})))
    (when (and (= spec-action :subscriber)
               (not (#{:entry :return :throw} spec-pos)))
      (throw (ex-info (str ":action :subscriber only valid at :position :entry/:return/:throw "
                            "(got " spec-pos ")")
                      {:nihilite/kind :nihilite/subscriber-requires-entry
                       :nihilite/id spec-id
                       :nihilite/action spec-action
                       :nihilite/position spec-pos})))
    (when (and (= spec-pos :throw)
               (#{:modify :cancel} spec-action))
      (throw (ex-info (str ":action :modify/:cancel invalid on :position :throw "
                            "(id=" spec-id ")")
                      {:nihilite/kind :nihilite/invalid-action-on-throw
                       :nihilite/id spec-id
                       :nihilite/action spec-action
                       :nihilite/position spec-pos})))
    (when (#{:invoke-before :invoke-return :invoke-throw} spec-pos)
      (throw (ex-info (str ":position " spec-pos " is reserved/removed; "
                            "use :entry/:return/:throw/:redefine")
                      {:nihilite/kind :nihilite/invalid-position
                       :nihilite/id spec-id
                       :nihilite/position spec-pos})))
    (let [norm-spec (assoc spec
                           :id spec-id
                           :target-internal spec-target
                           :method-name spec-method
                           :position spec-pos
                           :arity spec-arity
                           :action spec-action
                           :tag spec-tag
                           :method-key spec-method-key
                           :source-class spec-source-class
                           :source-descriptor spec-desc)]
      (locking registry-lock
        (let [prev (.put by-id (:id norm-spec) norm-spec)
              replaced? (some? prev)]
          (when replaced?
            (let [prev-bucket (.get by-target (:target-internal prev))]
              (when prev-bucket (.remove prev-bucket prev)))
            (when-let [pmk (:method-key prev)]
              (let [pmb (.get by-method pmk)]
                (when pmb (.remove pmb prev)))))
          (.add (bucket (:target-internal norm-spec)) norm-spec)
          (when-let [mk (:method-key norm-spec)]
            (.add (method-bucket mk) norm-spec))
          (when-not replaced?
            (stats/ensure-stats spec-id))
          (if replaced?
            (do (log/info "hook replaced:" (:id norm-spec)
                          "target=" (:target-internal norm-spec)
                          "method=" (:method-name norm-spec))
                (mark-installed! (:id norm-spec) 0)
                false)
            (do (log/info "hook registered:" (:id norm-spec)
                          "target=" (:target-internal norm-spec)
                          "method=" (:method-name norm-spec)
                          "@" (:position norm-spec)
                          "action=" (:action norm-spec)
                          (when-let [t (:tag norm-spec)] (str " tag=" t))
                          (when-let [n (:note norm-spec)] (str "// " n)))
                (mark-installed! (:id norm-spec) 0)
                (retransform-loaded-matching! spec-target)
                true)))))))

(defn uninstall!
  [id]
  (let [by-id     (get-by-id)
        by-target (get-by-target)
        by-method (get-by-method)]
    (locking registry-lock
      (when-let [removed (.remove by-id (str id))]
        (let [b (.get by-target (:target-internal removed))]
          (when b (.remove b removed))
          (when (and b (.isEmpty b))
            (.remove by-target (:target-internal removed) b))
          (when-let [mk (:method-key removed)]
            (let [mb (.get by-method mk)]
              (when mb (.remove mb removed))
              (when (and mb (.isEmpty mb))
                (.remove by-method mk mb))))
          (stats/remove-stats (:id removed))
          (let [count (try
                        ((resolve 'nihilite.kernel.installer/uninstall-spec!) (str id))
                        (catch Throwable t
                          (mark-error! (:id removed) (.getMessage t))
                          (throw (ex-info (str "uninstall retransform failed for id=" id)
                                          {:nihilite/kind :nihilite/uninstall-failed
                                           :nihilite/id   id
                                           :nihilite/cause (.getMessage t)}
                                          t))))]
            (mark-uninstalled! (:id removed) count)
            (if (zero? count)
              (log/warn "hook removed from registry but 0 classes retransformed"
                        "(agent not armed or class not loaded):" (:id removed))
              (log/info "hook removed:" (:id removed) "retransformed=" count "class(es)"))
            true))))))

(defn install-fresh!
  [spec]
  (let [{:keys [id] :as m} spec
        spec-id (str id)]
    (when (.get by-id spec-id)
      (throw (ex-info (str ":id " spec-id " already installed; "
                            "use install! (replace) or uninstall! first")
                      {:nihilite/kind :nihilite/duplicate-spec-id
                       :nihilite/id   spec-id
                       :nihilite/spec spec})))
    (install! m)))

(defn clear!
  []
  (locking registry-lock
    (.clear by-id)
    (.clear by-target)
    (.clear by-method)
    (stats/clear!)))

(defn matching
  ^java.util.List [target-internal]
  (let [b (.get by-target target-internal)]
    (if b (vec b) [])))

(defn list-ids
  []
  (sort (vec (.keySet by-id))))

(defn lookup
  [id]
  (.get by-id (str id)))

(defn replace-bridge!
  [id new-bridge]
  (let [by-id ^java.util.concurrent.ConcurrentHashMap by-id
        k (str id)]
    (loop []
      (let [cur ^clojure.lang.IPersistentMap (.get by-id k)]
        (if (nil? cur)
          false
          (let [updated (assoc cur :bridge new-bridge)]
            (if (.replace by-id k cur updated)
              (do (log/info "hook bridge swapped:" k) true)
              (recur))))))))
