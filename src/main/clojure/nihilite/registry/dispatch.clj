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
            [nihilite.registry.index :as index]
            [nihilite.registry.stats :as stats]
            [nihilite.kernel.exceptions :as exc]))

(defonce ^:private redefine-dispatcher-ref (atom nil))

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
    (catch nihilite.kernel.HookCancelledException _
      (throw (exc/cancelled!)))
    (catch Throwable t
      (try (log/error t "registry dispatch-for-spec failed (id=" spec-id ")")
           (catch Throwable _)))
    (finally nil)))

(defn- modify-value-compatible?
  "Whether `rv` can legally replace `original` as the target method's
   return value.

   The advice is woven with @Advice.Return(typing = DYNAMIC), so whatever
   the bridge returns is cast by the JVM to the target method's return
   type. A bridge that returns the wrong shape (the common slip is
   returning `ctx` itself) would otherwise surface as a bare
   ClassCastException from inside the woven method, with no hint about
   which hook or which spec caused it. Checked here instead so the error
   names the spec.

   Null and primitives-unboxing cases: a primitive target is only
   compatible when the value is a boxed instance of the wrapper."
  [rv original]
  (or (nil? rv)
      (nil? original)
      (instance? (class original) rv)))

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
              (do
                (when-not (modify-value-compatible? rv original)
                  (throw (ex-info
                           (str ":modify bridge for spec " (:id s) " returned "
                                (.getName (class rv))
                                ", which cannot replace the target's "
                                (.getName (class original))
                                ". A :modify bridge must RETURN the"
                                " replacement value (it may take ctx as its"
                                " single argument, but must not return it).")
                           {:nihilite/kind :nihilite/invalid-modify-value
                            :nihilite/id (:id s)
                            :nihilite/returned (class rv)
                            :nihilite/original (class original)})))
                (reset! result rv)
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
    ;; An invalid :modify value is a programming error the user must see;
    ;; do not degrade to the original value, which would hide it.
    (catch clojure.lang.ExceptionInfo e
      (if (= :nihilite/invalid-modify-value (:nihilite/kind (ex-data e)))
        (throw e)
        (do (try (log/error e "registry dispatch-return-for-spec failed (id=" spec-id ")")
                 (catch Throwable _))
            original)))
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
  "Find the spec id that should handle a call to
   `class-internal`/`method-name` with `parameter-count` arguments.

   `position` selects which hook serves the call. It is REQUIRED for
   correctness whenever more than one spec targets the same method:
   without it an :entry lookup can return a :redefine spec on the same
   method, and the :redefine bridge — which takes (self args method-name)
   — then gets invoked with the 1-argument ctx and throws ArityException.

   The 4-argument form keeps `position` nil for callers that genuinely do
   not know it, matching the first spec whose method and arity agree."
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
       (lookup-spec-for-call class-internal method-name parameter-count position))))
  ([^String class-internal method-name parameter-count position]
   (let [b (index/live-bucket class-internal)
         pos-kw (when position (reg/normalize-position position))]
     (when b
       (let [iname (str method-name)
             pcnt  (int parameter-count)]
         (some (fn [s]
                 (let [mn (:method-name s)
                       ar (:arity s)
                       sp (:position s)]
                   (when (and (= mn iname)
                              (or (nil? ar) (= ar pcnt))
                              (or (nil? pos-kw) (= sp pos-kw)))
                     (:id s))))
               b))))))

(defn dispatch-redefine
  "Runs the :redefine bridge and returns its value.

   The bridge takes three arguments — (self, args, method-name) — not the
   1-argument ctx the other positions receive, because a :redefine hook
   REPLACES the method body: it has no event to observe, only the call
   itself and whatever it chooses to return.

   The return value is what the woven method returns. Returning nil keeps
   the advice's own fallback (the method returns null), so a :redefine
   bridge that wants a value must return one."
  [host-internal method-name self args descriptor]
  (try
    (let [param-count (count args)
          spec-id     (lookup-spec-for-call host-internal method-name param-count descriptor :redefine)]
      (if-let [spec (and spec-id (reg/lookup spec-id))]
        (if-let [bridge-fn (safe-bridge spec)]
          (try
            (stats/bump-fired! spec-id)
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
