(ns nihilite.registry.dispatch
  "Hook-event dispatch and redefine-dispatcher wiring. The kernel's
   generated advice classes and the redefine delegation method call
   into the public functions here (dispatch-for-spec, dispatch-return-
   for-spec, dispatch-throw-for-spec, dispatch-redefine) via
   `clojure.java.api.Clojure/var`. lookup-spec-for-call lives here too
   so advice.clj can resolve it the same way.

   Owns the `redefine-dispatcher-ref` atom: install-redefine-dispatcher!
   sets it (called from nihilite.kernel.worker at agent-init time),
   nihilite.kernel.dispatcher reads it via Clojure/var."
  (:require [clojure.tools.logging :as log]
            [nihilite.registry :as reg]
            [nihilite.registry.stats :as stats]))

(defonce ^:private redefine-dispatcher-ref (atom nil))

(defn redefine-dispatcher
  "Returns the redefine dispatcher fn installed by
   install-redefine-dispatcher!, or nil when the worker has not yet
   booted. Used by nihilite.kernel.dispatcher."
  []
  @redefine-dispatcher-ref)

(defn ->hook-event
  "Construct HookEvent. :cancelled? / :cancel! are closures over AtomicBoolean."
  [spec self args return-value]
  (let [pos (:position spec)
        cell (java.util.concurrent.atomic.AtomicBoolean.)
        cancel-fn    (fn [v] (.set cell (boolean v)))
        cancelled-fn (fn [] (.get cell))]
    (reg/map->HookEvent
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
       :sequence     (reg/next-sequence)
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
         (stats/bump-exception! (or per-spec-id (:spec-id ev)))
         ::no-return)))))

(defn- safe-bridge
  [spec]
  (when (instance? clojure.lang.IFn (:bridge spec))
    ^clojure.lang.IFn (:bridge spec)))

(defn- call-cancel! [ev]
  (when-let [cb (:cancel! ev)] (cb true)))

(defn ->ctx [x]
  (cond
    (instance? nihilite.registry.HookContext x) x
    (instance? nihilite.registry.HookEvent x)
    (let [ev ^nihilite.registry.HookEvent x]
      (reg/map->HookContext
        {:hookId      (.-spec-id ev)
         :self        (.-self ev)
         :args        (.-args ev)
         :phase       (.-phase ev)
         :returnValue (.-return-value ev)
         :cancelled   ((.-cancelled? ev))}))
    :else nil))

(defn ctx-self        [x]          (when-some [c (->ctx x)] (:self c)))
(defn ctx-return      [x]          (when-some [c (->ctx x)] (.-returnValue ^nihilite.registry.HookContext c)))
(defn ctx-cancel!     [x value]    (cond
                                     (instance? nihilite.registry.HookContext x)
                                     (set! (.-cancelled ^nihilite.registry.HookContext x) (boolean value))
                                     (instance? nihilite.registry.HookEvent x)
                                     (let [ev ^nihilite.registry.HookEvent x]
                                       (when-let [c (.-cancel! ev)] (c (boolean value))))))
(defn ctx-cancelled?  [x]          (cond
                                     (instance? nihilite.registry.HookContext x) (.-cancelled ^nihilite.registry.HookContext x)
                                     (instance? nihilite.registry.HookEvent x)
                                     (let [c (.-cancelled? ^nihilite.registry.HookEvent x)]
                                       (if (fn? c) (boolean (c)) (boolean c)))
                                     :else false))

(defn- walk-bucket
  [bucket event _spec-id]
  (reduce (fn [acc s]
            (if acc
              (reduced acc)
              (let [action (or (:action s) :observe)
                    f      (safe-bridge s)]
                (dispatch-one! f event (:id s))
                (stats/bump-fired! (:id s))
                (cond
                  (= action :cancel)
                  (do (call-cancel! event)
                      :nihilite/short-circuit)

                  (= action :subscriber)
                  (do (call-cancel! event)
                      nil)

                  :else nil))))
          nil
          bucket))

(defn dispatch-for-spec
  [spec-id self args]
  (try
    (when-let [spec (reg/lookup spec-id)]
      (let [bucket (reg/spec-bucket spec)
            event  (->hook-event spec self args nil)]
        (walk-bucket bucket event spec-id)))
    (catch Throwable t
      (try (log/error t "registry dispatch-for-spec failed (id=" spec-id ")")
           (catch Throwable _)))))

(defn dispatch-return-for-spec
  [spec-id self args original]
  (try
    (if-let [spec (reg/lookup spec-id)]
      (let [bucket (reg/spec-bucket spec)
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
            (stats/bump-fired! (:id s))
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
          (when-let [r (stats/get-stats spec-id)]
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
    (when-let [spec (reg/lookup spec-id)]
      (let [bucket (reg/spec-bucket spec)
            event  (assoc (->hook-event spec self args nil) :throwable throwable)]
        (walk-bucket bucket event spec-id)))
    (catch Throwable t
      (try (log/error t "registry dispatch-throw-for-spec failed (id=" spec-id ")")
           (catch Throwable _)))))

(defn lookup-spec-for-call
  ([^String class-internal ^String method-name parameter-count
    ^String descriptor position]
   (let [mk (when (and (some? descriptor) (not (empty? descriptor)))
              (reg/method-key class-internal method-name descriptor))
         mb (when mk (.get (reg/get-by-method) mk))
         pos-kw (when position (reg/normalize-position position))]
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
   (let [b (.get (reg/get-by-target) class-internal)]
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

(defn dispatch-redefine
  [host-internal method-name self args descriptor]
  (try
    (let [param-count (count args)
          spec-id     (lookup-spec-for-call host-internal method-name param-count descriptor :redefine)]
      (if-let [spec (and spec-id (reg/lookup spec-id))]
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
        (fn [dispatch-ifn]
          (reset! redefine-dispatcher-ref dispatch-ifn))))
  ([setter]
   (let [dispatch-ifn
         (fn [host-internal method-name self args descriptor]
           (dispatch-redefine host-internal method-name self args descriptor))]
     (reset! redefine-dispatcher-ref dispatch-ifn)
     (setter dispatch-ifn)
     :installed)))
