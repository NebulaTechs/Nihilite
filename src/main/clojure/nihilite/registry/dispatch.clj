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
        {:hook-id      (.-spec-id ev)
         :self        (.-self ev)
         :args        (.-args ev)
         :phase       (.-phase ev)
         :return-value (.-return-value ev)
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
      (throw (exc/cancelled!)))))

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

(def ^:private boxed-primitives
  "descriptor return char -> the wrapper class, so a bridge value can be
   narrowed to what the target method actually returns."
  {\I Integer
   \J Long
   \Z Boolean
   \B Byte
   \C Character
   \S Short
   \F Float
   \D Double})

(defn- descriptor-return-class
  "The class a method with this descriptor returns, or nil when the
   descriptor is absent or its return type is void.

   Parses only what is needed: the substring after the closing paren of the
   parameter list. `Lname;` and `[type` forms come back as a string, not a
   Class, because resolving them would mean loading the type -- which, on a
   hook that is being installed precisely to observe a class-loading path, is
   the thing most likely to re-enter the advice."
  [^String descriptor]
  (when (and descriptor (<= 3 (count descriptor)))
    (let [close (.indexOf ^String descriptor (int \)))
          rt    (when (>= close 0) (subs descriptor (inc close)))]
      (when (and rt (seq rt))
        (let [c (first rt)]
          (cond
            (= \V c) nil
            (= \L c) (subs rt 1 (dec (count rt)))
            (= \[ c) rt
            (contains? boxed-primitives c) (boxed-primitives c)
            :else rt))))))

(defn coerce-return
  "Narrows a bridge's return value to the target method's return type.

   The advice forwarders return Object, and AssignReturned.ToReturned /
   @Advice.Return(DYNAMIC) write that Object into the target's return slot,
   which is a CHECKCAST to the target type. A Clojure literal boxes to Long,
   so `(fn [] 7)` against an `int`-returning method throws
   ClassCastException: Long cannot be cast to Integer -- from inside the woven
   method, naming neither the hook nor the spec.

   Numeric narrowing is what a caller means here, so do it in Clojure where the
   error can name the spec. A value that genuinely cannot represent the target
   type is left alone, and the caller rejects it with
   :nihilite/invalid-modify-value."
  [rv ^String descriptor]
  (let [target (descriptor-return-class descriptor)]
    (cond
      (nil? rv) rv
      (nil? target) rv
      (string? target) rv
      (instance? target rv) rv
      ;; rv is a Number standing in for a narrower or wider numeric return
      (and (number? rv) (contains? (set (vals boxed-primitives)) target))
      (condp identical? target
        Integer (.intValue ^Number rv)
        Long (.longValue ^Number rv)
        Short (.shortValue ^Number rv)
        Byte (.byteValue ^Number rv)
        Double (.doubleValue ^Number rv)
        Float (.floatValue ^Number rv)
        rv)
      :else rv)))

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
                (let [rv' (coerce-return rv (or (:source-descriptor s)
                                                 (:source-descriptor spec)))]
                  (when-not (modify-value-compatible? rv' original)
                    (throw (ex-info
                             (str ":modify bridge for spec " (:id s) " returned "
                                  (.getName (class rv'))
                                  ", which cannot replace the target's "
                                  (.getName (class original))
                                  ". A :modify bridge must RETURN the"
                                  " replacement value (it may take ctx as its"
                                  " single argument, but must not return it).")
                             {:nihilite/kind :nihilite/invalid-modify-value
                              :nihilite/id (:id s)
                              :nihilite/returned (class rv')
                              :nihilite/original (class original)})))
                  (reset! result rv')
                  (reset! modified? true)
                  (reset! decided? true)))

              (= action :cancel)
              (do (call-cancel! event) (reset! decided? true))

              (= action :subscriber)
              (do (call-cancel! event) (reset! decided? true))
              :else nil)))
        (when @modified?
          (when-let [r (stats/get-stats spec-id)]
            (swap! (:modified r) inc)))
        @result)
      original)))

(defn dispatch-throw-for-spec
  [spec-id self args throwable]
  (when-let [spec (reg/lookup spec-id)]
    (let [bucket (reg/spec-bucket spec)
          event  (assoc (->hook-event spec self args nil) :throwable throwable)]
      (walk-bucket bucket event spec-id))))

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
            (coerce-return (bridge-fn self args method-name)
                           (or (:source-descriptor spec) descriptor))
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
