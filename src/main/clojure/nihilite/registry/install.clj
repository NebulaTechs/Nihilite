(ns nihilite.registry.install
  "Spec lifecycle: install! / uninstall! / matching / snapshot /
   list-ids. Maintains `by-id` / `by-target` / `by-method` indexes
   in lock-step. install! pre-creates the StatsRecord so the
   first dispatch cannot race the stats index."
  (:require [clojure.tools.logging :as log]
            [nihilite.registry.spec :as rs]
            [nihilite.registry.index :as ix]
            [nihilite.registry.stats :as stats])
  (:import [nihilite.registry.spec HookSpec]))

(defn install!
  "Add or replace spec by :id. Returns true on install, false on replace. Use install-fresh! for strict."
  [spec]
  (let [{:keys [id target-internal method-name position arity bridge note
                descriptor action tag capture-stack? max-depth sample-rate]} spec
        spec-id (some-> id str)
        spec-target (some-> target-internal str)
        spec-method (some-> method-name str)
        spec-pos    (rs/normalize-position position)
        spec-arity  (when arity (int arity))
        spec-desc   (some-> descriptor str)
        spec-action (if (contains? spec :action)
                     (rs/normalize-action action)
                     :observe)
        spec-tag    (some-> tag str)
        spec-capture? (boolean capture-stack?)
        spec-max-depth (cond
                         (nil? max-depth)   32
                         (and (number? max-depth) (<= 1 max-depth 256))
                         (int max-depth)
                         :else
                         (throw (ex-info ":max-depth must be in [1, 256]"
                                         {:nihilite/kind :nihilite/bad-max-depth
                                          :nihilite/max-depth max-depth})))
        spec-sample-rate (cond
                           (nil? sample-rate) 0.01
                           (and (number? sample-rate) (<= 0.0 sample-rate 1.0))
                           (double sample-rate)
                           :else
                           (throw (ex-info ":sample-rate must be in [0.0, 1.0]"
                                           {:nihilite/kind :nihilite/bad-sample-rate
                                            :nihilite/sample-rate sample-rate})))
        desc-missing? (or (nil? spec-desc) (empty? spec-desc))
        spec-method-key (when-not desc-missing?
                          (rs/method-key spec-target spec-method spec-desc))
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
    (when (and (some? spec-action)
               (not (contains? rs/ACTIONS spec-action)))
      (throw (ex-info (str ":action must be one of " (vec rs/ACTIONS))
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
    (when (and (#{:throw} spec-pos)
               (#{:modify :cancel} spec-action))
      (throw (ex-info (str ":action :modify/:cancel invalid on :position :throw "
                           "(id=" spec-id ")")
                      {:nihilite/kind :nihilite/invalid-action-on-throw
                       :nihilite/id spec-id
                       :nihilite/action spec-action
                       :nihilite/position spec-pos})))
    (when (and (#{:invoke-before :invoke-return :invoke-throw} spec-pos)
               (#{:modify :cancel} spec-action))
      (throw (ex-info (str ":action :modify/:cancel invalid on :invoke-* position "
                           "(id=" spec-id ")")
                      {:nihilite/kind :nihilite/invalid-action-on-invoke
                       :nihilite/id spec-id
                       :nihilite/action spec-action
                       :nihilite/position spec-pos})))
    (let [norm-spec (assoc spec
                           :id spec-id
                           :target-internal spec-target
                           :method-name spec-method
                           :position spec-pos
                           :arity spec-arity
                           :action spec-action
                           :tag spec-tag
                           :capture-stack? spec-capture?
                           :max-depth spec-max-depth
                           :sample-rate spec-sample-rate
                           :method-key spec-method-key
                           :source-class spec-source-class
                           :source-descriptor spec-desc)
          prev (.put (ix/get-by-id) (:id norm-spec) norm-spec)
          replaced? (some? prev)]
      (when replaced?
        (let [prev-bucket (.get (ix/get-by-target) (:target-internal prev))]
          (when prev-bucket (.remove prev-bucket prev)))
        (when-let [pmk (:method-key prev)]
          (let [pmb (.get (ix/get-by-method) pmk)]
            (when pmb (.remove pmb prev)))))
      (.add (ix/bucket (:target-internal norm-spec)) norm-spec)
      (when-let [mk (:method-key norm-spec)]
        (.add (ix/method-bucket mk) norm-spec))
      ;; On replace, StatsRecord is preserved so cumulative counters survive.
      (when-not replaced?
        (stats/ensure-stats spec-id))
      (if replaced?
        (do (log/info "hook replaced:" (:id norm-spec)
                      "target=" (:target-internal norm-spec)
                      "method=" (:method-name norm-spec))
            false)
        (do (log/info "hook registered:" (:id norm-spec)
                      "target=" (:target-internal norm-spec)
                      "method=" (:method-name norm-spec)
                      "@" (:position norm-spec)
                      "action=" (:action norm-spec)
                      (when-let [t (:tag norm-spec)]
                        (str " tag=" t))
                      (when-let [n (:note norm-spec)] (str "// " n)))
            true)))))

(defn uninstall!
  "Remove a spec by id. Returns true if removed, false if missing."
  [id]
  (let [by-id     (ix/get-by-id)
        by-target (ix/get-by-target)
        by-method (ix/get-by-method)]
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
        (log/info "hook removed:" (:id removed))
        true))))

(defn install-fresh!
  "Strict install!: throws :duplicate-spec-id if :id exists. Alias: install-new!."
  [spec]
  (let [{:keys [id] :as m} spec
        spec-id (str id)]
    (when (.get (ix/get-by-id) spec-id)
      (throw (ex-info (str ":id " spec-id " already installed; "
                           "use install! (replace) or uninstall! first")
                      {:nihilite/kind :nihilite/duplicate-spec-id
                       :nihilite/id   spec-id
                       :nihilite/spec spec})))
    (install! m)))

(def install-new! install-fresh!)

(defn clear!
  "Drop every spec. Test/diagnostic only."
  []
  (ix/clear-all!))

(defn matching
  "Return live list of specs targeting target-internal. Stable snapshot at call time."
  ^java.util.List [target-internal]
  (let [b (.get (ix/get-by-target) target-internal)]
    (if b (vec b) [])))

(defn snapshot
  "Defensive copy of all (id → spec) pairs. Diagnostic."
  []
  (let [m (java.util.HashMap.)]
    (.putAll m (ix/get-by-id))
    m))

(defn list-ids
  "Sorted seq of registered spec ids."
  []
  (sort (vec (.keySet (ix/get-by-id)))))
