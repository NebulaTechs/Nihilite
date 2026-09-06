(ns nihilite.registry
  "Generic, loader-agnostic registry of hook specs + dispatch helpers.
   Contracts: install! true=fresh/false=replaced; uninstall! true=removed/false=missing;
   install-fresh! throws :duplicate-spec-id; dispatch-* are ByteBuddy entry points;
   install-redefine-dispatcher! bridges via reflection to avoid load-order cycle."
  (:require [clojure.tools.logging :as log])
  (:import [java.util.concurrent ConcurrentHashMap CopyOnWriteArrayList]
           [java.util.concurrent.atomic AtomicBoolean AtomicLong]
           [java.lang.instrument Instrumentation]
           [nihilite.hooks Bridge]))

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

(defn- get-by-id     ^ConcurrentHashMap [] by-id)
(defn- get-by-target ^ConcurrentHashMap [] by-target)
(defn- get-by-method ^ConcurrentHashMap [] by-method)

(defn- get-or-create-bucket ^java.util.List [^ConcurrentHashMap m k]  (or (.get m k)
      (let [fresh (CopyOnWriteArrayList.)]
        (if (nil? (.putIfAbsent m k fresh))
          fresh
          (.get m k)))))

(defn- bucket        ^java.util.List [t]  (get-or-create-bucket by-target t))
(defn- method-bucket ^java.util.List [mk] (get-or-create-bucket by-method mk))

(defn- retransform-loaded-matching!
  [^String target-internal]
  (when-let [^Instrumentation inst (nihilite.agent.Agent/currentInstrumentation)]
    (let [dot-name (.replace ^String target-internal "/" ".")]
      (try
        (let [candidates (->> (.getAllLoadedClasses inst)
                              (filter (fn [^Class c]
                                        (and c (.equals dot-name (.getName c)))))
                              vec)
              modifiable (filter (fn [^Class c] (.isModifiableClass inst c)) candidates)]
          (when (seq modifiable)
            (try
              (.retransformClasses inst (into-array Class (vec modifiable)))
              (log/debug "retransform-loaded-matching! retransformed"
                         (count modifiable) "class(es) for target=" target-internal)
              (catch java.lang.instrument.UnmodifiableClassException _
                (log/warn "retransform-loaded-matching! could not retransform"
                          target-internal " (UnmodifiableClassException)"))
              (catch Throwable t
                (log/warn t "retransform-loaded-matching! retransform failed for"
                          target-internal)))))
        (catch Throwable t
          (log/warn t "retransform-loaded-matching! getAllLoadedClasses failed for"
                    target-internal))))))

(defn- next-sequence [] (.incrementAndGet ^AtomicLong sequence-counter))

(defn- clear-all! []
  (.clear by-id)
  (.clear by-target)
  (.clear by-method)
  nil)

(defrecord StatsRecord
  [fired modified cancelled exceptions last-ns max-ns])

(defonce ^:private ^ConcurrentHashMap stats-index
  (ConcurrentHashMap.))

(defn- fresh-record ^StatsRecord []
  (->StatsRecord (atom 0) (atom 0) (atom 0) (atom 0) (atom 0) (atom 0)))

(defn ensure-stats ^StatsRecord [spec-id]
  (let [id (str spec-id)
        existing ^StatsRecord (.get stats-index id)]
    (if (nil? existing)
      (let [created (fresh-record)]
        (if (nil? (.putIfAbsent stats-index id created))
          created
          ^StatsRecord (.get stats-index id)))
      existing)))

(defn get-stats ^StatsRecord [spec-id]
  (.get stats-index (str spec-id)))

(defn remove-stats [spec-id]
  (some? (.remove stats-index (str spec-id))))

(defn stats-snapshot []
  (into {} stats-index))

(defn- stats-clear! []
  (.clear stats-index)
  nil)

(defn- bump-fired!      [spec-id] (when-let [r (get-stats spec-id)] (swap! (:fired r) inc)))
(defn- bump-exception!  [spec-id] (when-let [r (get-stats spec-id)] (swap! (:exceptions r) inc)))

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

(defn- ->hook-event
  "Construct HookEvent. :cancelled?/:cancel! are closures over AtomicBoolean."
  [spec self args return-value]
  (let [pos (:position spec)
        cell (AtomicBoolean.)
        cancel-fn    (fn [v] (.set cell (boolean v)))
        cancelled-fn (fn [] (.get cell))]
    (map->HookEvent
      {:spec-id      (:id spec)
       :source       {:class         (or (:source-class spec)
                                          (:target-internal spec))
                      :internal      (:target-internal spec)
                      :method        (:method-name spec)
                      :descriptor    (:source-descriptor spec)
                      :action        (:action spec)
                      :method-key    (:method-key spec)}
       :phase        pos
       :self         self
       :args         (or args (object-array 0))
       :return-value return-value
       :throwable    nil
       :cancelled?   cancelled-fn
       :cancel!      cancel-fn
       :thread-name  (.getName (Thread/currentThread))
       :timestamp-ns (System/nanoTime)
       :sequence     (next-sequence)
       :note         (:note spec)})))

(defn- dispatch-one!
  ([ifn ev] (dispatch-one! ifn ev nil))
  ([ifn ev per-spec-id]
   (if (nil? ifn)
     ::no-return
     (try
       (ifn ev)
       (catch Throwable t
         (try (log/error t "observer threw (id=" (or per-spec-id (:spec-id ev)) ")")
              (catch Throwable _))
         (bump-exception! (or per-spec-id (:spec-id ev)))
         ::no-return)))))

(defn- safe-bridge
  [spec]
  (when (instance? clojure.lang.IFn (:bridge spec))
    ^clojure.lang.IFn (:bridge spec)))

(defn- call-cancel! [ev]
  (when-let [cb (:cancel! ev)] (cb true)))

(defn- ->ctx [x]
  (cond
    (instance? HookContext x) x
    (instance? HookEvent x)
    (map->HookContext
      {:hookId      (.-spec-id ^HookEvent x)
       :self        (.-self ^HookEvent x)
       :args        (.-args ^HookEvent x)
       :phase       (.-phase ^HookEvent x)
       :returnValue (.-return-value ^HookEvent x)
       :cancelled   ((.-cancelled? ^HookEvent x))})
    :else nil))

(defn ctx-self        [x]          (when-some [c (->ctx x)] (:self c)))
(defn ctx-return      [x]          (when-some [c (->ctx x)] (.-returnValue ^HookContext c)))
(defn ctx-cancel!     [x value]    (cond
                                     (instance? HookContext x)
                                     (set! (.-cancelled ^HookContext x) (boolean value))
                                     (instance? HookEvent x)
                                     (let [ev ^HookEvent x]
                                       (when-let [c (.-cancel! ev)] (c (boolean value))))))
(defn ctx-cancelled?  [x]          (cond
                                     (instance? HookContext x) (.-cancelled ^HookContext x)
                                     (instance? HookEvent x)
                                     (let [c (.-cancelled? ^HookEvent x)]
                                       (if (fn? c) (boolean (c)) (boolean c)))
                                     :else false))

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
        (let [prev (.put (get-by-id) (:id norm-spec) norm-spec)
              replaced? (some? prev)]
          (when replaced?
            (let [prev-bucket (.get (get-by-target) (:target-internal prev))]
              (when prev-bucket (.remove prev-bucket prev)))
            (when-let [pmk (:method-key prev)]
              (let [pmb (.get (get-by-method) pmk)]
                (when pmb (.remove pmb prev)))))
          (.add (bucket (:target-internal norm-spec)) norm-spec)
          (when-let [mk (:method-key norm-spec)]
            (.add (method-bucket mk) norm-spec))
          (when-not replaced?
            (ensure-stats spec-id))
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
          (remove-stats (:id removed))
          (let [count (try
                        (Bridge/uninstallSpec (str id))
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
    (when (.get (get-by-id) spec-id)
      (throw (ex-info (str ":id " spec-id " already installed; "
                            "use install! (replace) or uninstall! first")
                      {:nihilite/kind :nihilite/duplicate-spec-id
                       :nihilite/id   spec-id
                       :nihilite/spec spec})))
    (install! m)))

(defn clear!
  []
  (locking registry-lock
    (clear-all!)
    (stats-clear!)))

(defn matching
  ^java.util.List [target-internal]
  (let [b (.get (get-by-target) target-internal)]
    (if b (vec b) [])))

(defn list-ids
  []
  (sort (vec (.keySet (get-by-id)))))

(defn lookup
  [id]
  (.get (get-by-id) (str id)))

(defn replace-bridge!
  [id new-bridge]
  (let [by-id ^java.util.concurrent.ConcurrentHashMap (get-by-id)
        k (str id)]
    (loop []
      (let [cur ^clojure.lang.IPersistentMap (.get by-id k)]
        (if (nil? cur)
          false
          (let [updated (assoc cur :bridge new-bridge)]
            (if (.replace by-id k cur updated)
              (do (log/info "hook bridge swapped:" k) true)
              (recur))))))))

(defn- spec-bucket
  [spec]
  (if-let [mk (:method-key spec)]
    (some-> (.get (get-by-method) mk) seq)
    (some-> (.get (get-by-target) (:target-internal spec)) seq)))

(defn lookup-spec-for-call
  ([^String class-internal ^String method-name parameter-count
    ^String descriptor position]
   (let [mk (when (and (some? descriptor) (not (empty? descriptor)))
              (method-key class-internal method-name descriptor))
         mb (when mk (.get (get-by-method) mk))
         pos-kw (when position (normalize-position position))]
     (cond
       mb
       (let [pcnt (int parameter-count)]
         (some (fn [s]
                 (let [ar (:arity s)
                       sp (:position s)]
                   (when (and (or (nil? ar) (= ar pcnt))
                              (or (nil? pos-kw) (= sp pos-kw)))
                     (:id s))))
               mb))
       :else
       (lookup-spec-for-call class-internal method-name parameter-count))))
  ([^String class-internal ^String method-name parameter-count _descriptor]
   (lookup-spec-for-call class-internal method-name parameter-count))
  ([^String class-internal method-name parameter-count]
   (let [b (.get (get-by-target) class-internal)]
     (when b
       (let [iname (str method-name)
             pcnt  (int parameter-count)]
         (some (fn [s]
                 (let [mn (:method-name s)
                       ar (:arity s)]
                   (when (and (= mn iname)
                              (or (nil? ar) (= ar pcnt)))
                     (:id s))))
               b))))))

(defn- walk-bucket
  [bucket event _spec-id]
  (reduce (fn [acc s]
            (if acc
              (reduced acc)
              (let [action (or (:action s) :observe)
                    f      (safe-bridge s)]
                (dispatch-one! f event (:id s))
                (bump-fired! (:id s))
                (cond
                  (= action :cancel)
                  (do (call-cancel! event)
                      ::short-circuit)

                  (= action :subscriber)
                  (do (call-cancel! event)
                      nil)

                  :else nil))))
          nil
          bucket))

(defn dispatch-for-spec
  [spec-id self args]
  (try
    (when-let [spec (lookup spec-id)]
      (let [bucket (spec-bucket spec)
            event  (->hook-event spec self args nil)]
        (walk-bucket bucket event spec-id)))
    (catch Throwable t
      (try (log/error t "registry dispatch-for-spec failed (id=" spec-id ")")
           (catch Throwable _)))))

(defn dispatch-return-for-spec
  [spec-id self args original]
  (try
    (if-let [spec (lookup spec-id)]
      (let [bucket (spec-bucket spec)
            event  (->hook-event spec self args original)
            result (atom original)
            decided? (atom false)
            modified? (atom false)]
        (doseq [s bucket
                :while (and (not @decided?)
                            (not (ctx-cancelled? event)))]
          (let [action (or (:action s) :observe)
                f (safe-bridge s)
                rv (dispatch-one! f event)]
            (bump-fired! (:id s))
            (cond
              (and (= action :modify) (some? rv))
              (do (reset! result rv)
                  (reset! modified? true)
                  (reset! decided? true))

              (= action :cancel)
              (do (call-cancel! event) (reset! decided? true))

              (= action :subscriber)
              (do (call-cancel! event) (reset! decided? true))
              :else nil)))
        (when @modified?
          (when-let [r (get-stats spec-id)]
            (swap! (:modified r) inc)))
        @result)
      original)
    (catch Throwable t
      (try (log/error t "registry dispatch-return-for-spec failed (id=" spec-id ")")
           (catch Throwable _))
      original)))

(defn dispatch-throw-for-spec
  [spec-id self args throwable]
  (try
    (when-let [spec (lookup spec-id)]
      (let [bucket (spec-bucket spec)
            event  (assoc (->hook-event spec self args nil) :throwable throwable)]
        (walk-bucket bucket event spec-id)))
    (catch Throwable t
      (try (log/error t "registry dispatch-throw-for-spec failed (id=" spec-id ")")
           (catch Throwable _)))))

(defn dispatch-redefine
  [host-internal method-name self args descriptor]
  (try
    (let [param-count (count args)
          spec-id     (lookup-spec-for-call host-internal method-name param-count descriptor :redefine)]
      (if-let [spec (and spec-id (lookup spec-id))]
        (if-let [bridge-fn (safe-bridge spec)]
          (try
            (bridge-fn self args method-name)
            (catch Throwable t
              (log/error t "bridge redefine-fire failed (id=" spec-id ")")
              (throw t)))
          (throw (IllegalStateException.
                   (str "no bridge fn for spec id " spec-id))))
        (throw (IllegalStateException.
                 (str "no spec for " host-internal "/" method-name "/" param-count)))))
    (catch Throwable t
      (throw t))))

(defn install-redefine-dispatcher!
  ([] (install-redefine-dispatcher!
        (let [bridge-class (Class/forName "nihilite.hooks.Bridge")
              method (.getMethod bridge-class "installRedefineDispatcher"
                                 (into-array Class [Object]))]
          (fn [dispatch-ifn] (.invoke method nil (object-array [dispatch-ifn]))))))
  ([setter]
   (let [dispatch-ifn
         (fn [host-internal method-name self args descriptor]
           (dispatch-redefine host-internal method-name self args descriptor))]
     (setter dispatch-ifn)
     :installed)))
