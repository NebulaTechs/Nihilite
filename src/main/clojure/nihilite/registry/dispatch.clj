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
       :source       {:class         (or (:internal-class spec)
                                          (:target-internal spec))
                       :internal      (:target-internal spec)
                       :method        (:method-name spec)
                       :descriptor    (:method-descriptor spec)
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

(defn- bi
  ([s] (java.math.BigInteger. ^String s))
  ([lo hi] [(java.math.BigInteger. ^String lo) (java.math.BigInteger. ^String hi)]))

(def ^:private min-max
  "The exact value range of each boxed primitive return type, as
   BigIntegers. Written as literals because a BigDecimal built from a
   double carries that double's approximation, which would make
   Integer/MAX_VALUE itself look out of range. Float and Double are
   absent: they can hold anything, so narrowing to them cannot truncate."
  {Integer (bi "-2147483648" "2147483647")
   Long    (bi "-9223372036854775808" "9223372036854775807")
   Short   (bi "-32768" "32767")
   Byte    (bi "-128" "127")})

(defn- exact-rv
  "The value of rv as an exact BigDecimal, or nil when it has no exact
   integer form and so cannot be range-checked.

   A Double or Float is checked only when it is mathematically integral:
   1e20 narrows to Integer/MAX_VALUE silently, which is exactly the bug
   this guards, and its double value IS the integer 10^20 exactly enough
   to compare against the range. A non-integral Double (1.5) has no
   integer value to check, so it narrows as the JVM does."
  [rv]
  (cond
    (instance? java.math.BigInteger rv) (bigdec rv)
    (instance? java.math.BigDecimal rv)
    (if (== (.compareTo (biginteger rv) (.toBigInteger rv)) 0)
      (bigdec rv)
      nil)
    (instance? Long rv)   (bigdec rv)
    (instance? Integer rv) (bigdec rv)
    (instance? Short rv)  (bigdec rv)
    (instance? Byte rv)   (bigdec rv)
    (instance? clojure.lang.BigInt rv) (bigdec rv)
    (instance? Double rv)
    (if (or (Double/isNaN rv) (Double/isInfinite rv)
            (not (== rv (Math/floor (double rv)))))
      nil
      (bigdec rv))
    (instance? Float rv)
    (if (or (Float/isNaN rv) (Float/isInfinite rv)
            (not (== rv (Math/floor (double rv)))))
      nil
      (bigdec (double rv)))
    :else nil))

(defn- representable?
  "Whether the exact value fits the target wrapper type. Widening always
   fits, so only the narrowing direction is checked. A target with no
   range (Boolean, Character, Float, Double) can never truncate."
  [^java.math.BigDecimal exact ^Class target]
  (let [bounds (min-max target)]
    (if (nil? bounds)
      true
      (let [[^java.math.BigInteger lo ^java.math.BigInteger hi] bounds
            i (.toBigIntegerExact exact)]
        (and (>= (compare i lo) 0) (<= (compare i hi) 0))))))

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


(def ^:private descriptor-primitive-codes
  "JVM descriptor type code -> the primitive's Class, for building an
   array type one dimension at a time. Names match boxed-primitives."
  {\I Integer/TYPE
   \J Long/TYPE
   \Z Boolean/TYPE
   \B Byte/TYPE
   \C Character/TYPE
   \S Short/TYPE
   \F Float/TYPE
   \D Double/TYPE})

(defn- resolve-component-type
  "Resolves one non-array type name, or nil. Accepts both the internal
   slash form a descriptor carries (java/util/BitSet) and the binary
   dotted form, because Class/forName only understands the latter."
  [^String name]
  (try
    (when (seq name)
      (Class/forName (.replace name "/" ".") false (clojure.lang.RT/baseLoader)))
    (catch Throwable _ nil)))

(defn- resolve-type-name
  "Resolves a JVM type from a descriptor to a Class, or nil when it cannot
   be resolved. Used only where a return-value check needs the declared
   type and has no other source for it.

   Handles the array forms a descriptor spells `[I` / `[[Lfoo;`. Those are
   not class names: Class/forName rejects every array spelling, so an
   array return type has to be built a dimension at a time with
   Class/arrayClass. Missing that left a String bridge value unchecked
   against an int[]-returning method.

   Returns nil for a type no loader here can see, which the caller treats
   as 'cannot check' rather than 'must reject' -- the alternative would
   reject every hook on a class the hook's own loader has not loaded yet.

   Loads with initialize=false so resolving a name never runs the type's
   static initialiser: a :return bridge is inside the target method when
   this runs, so a static initialiser that touches the hooked method
   would re-enter the advice."
  [^String name]
  (if (and (seq name) (= \[ (.charAt name 0)))
    (let [dims  (count (take-while #(= \[ %) name))
          comp  (subs name dims)
          inner (if (= \L (.charAt comp 0))
                   (when-let [semi (.indexOf comp (int \;))]
                     (subs comp 1 semi))
                   comp)]
      (if-let [base (or (descriptor-primitive-codes (.charAt inner 0))
                        (resolve-component-type inner))]
        (loop [k dims c base]
          ;; Array/newInstance hands back an instance; its class is the
          ;; array type, and the instance is discarded.
          (if (pos? k)
            (recur (dec k) (.getClass ^Object (java.lang.reflect.Array/newInstance c 0)))
            c))
        nil))
    (resolve-component-type name)))

(defn- modify-value-compatible?
  "Whether `rv` can legally replace the target method's return value.

   The advice is woven with @Advice.Return(typing = DYNAMIC), so whatever
   the bridge returns is cast by the JVM to the target method's return
   type. A bridge that returns the wrong shape (the common slip is
   returning `ctx` itself) would otherwise surface as a bare
   ClassCastException from inside the woven method, with no hint about
   which hook or which spec caused it. Checked here instead so the error
   names the spec.

   `descriptor` is the target method's own descriptor and is the only
   trustworthy source of the declared return type. An earlier version
   took the previous call's value instead, which meant that whenever the
   target returned null the check degenerated to `(instance? nil rv)` --
   true for every rv, so a bridge returning an Integer against a
   MyThing-returning method passed here and then threw a bare CCE from
   inside the woven method. A method may return null on one call and a
   real value on the next, so the check passed or failed depending on
   data, not on the bridge.

   Falls back to comparing against `original` only when the declared
   type cannot be determined, which is the case for an array or a
   malformed descriptor."
  [rv original ^String descriptor]
  (or (nil? rv)
      (let [target (descriptor-return-class descriptor)]
        (cond
          (string? target)
          (if-let [cls (resolve-type-name target)]
            (instance? cls rv)
            (or (nil? original) (instance? (class original) rv)))

          (some? target)
          ;; A primitive target. coerce-return already narrowed a Number
          ;; to the target's own wrapper, so only a non-Number can still
          ;; be wrong here -- a String passes through coerce-return
          ;; untouched and would reach the woven method as a CCE.
          (instance? target rv)

          :else
          ;; void, or no descriptor to read: nothing to check against.
          true))))

(defn coerce-return
  "Narrows a bridge's return value to the target method's return type.

   The advice forwarders return Object, and AssignReturned.ToReturned /
   @Advice.Return(DYNAMIC) write that Object into the target's return slot,
   which is a CHECKCAST to the target type. A Clojure literal boxes to Long,
   so `(fn [] 7)` against an `int`-returning method throws
   ClassCastException: Long cannot be cast to Integer -- from inside the woven
   method, naming neither the hook nor the spec.

   Numeric narrowing is what a caller means here, so do it in Clojure where the
   error can name the spec. A value that cannot represent the target type is
   REJECTED, not truncated: JVM's .byteValue/.intValue saturate silently
   (300 -> 44, 1e20 -> Integer/MAX_VALUE, NaN -> 0), so a bridge asking for a
   byte and handing back 300 would otherwise change what the woven method
   returns with no signal at all. Rejection carries the same
   :nihilite/invalid-modify-value kind the caller's own check uses, so one
   handler covers both."
  [rv ^String descriptor]
  (let [target (descriptor-return-class descriptor)]
    (cond
      (nil? rv) rv
      (nil? target) rv
      (string? target) rv
      (instance? target rv) rv
      ;; rv is a Number standing in for a narrower or wider numeric return
      (and (number? rv) (contains? (set (vals boxed-primitives)) target))
      (let [narrowed (condp identical? target
                       Integer (.intValue ^Number rv)
                       Long (.longValue ^Number rv)
                       Short (.shortValue ^Number rv)
                       Byte (.byteValue ^Number rv)
                       Double (.doubleValue ^Number rv)
                       Float (.floatValue ^Number rv)
                       rv)
            exact    (exact-rv rv)]
        (if (and exact (not (representable? exact target)))
          (throw (ex-info
                   (str ":modify bridge returned " (pr-str rv) " ("
                        (.getName (class rv)) "), which does not fit the target's "
                        (.getName ^Class target) " return type. Narrowing would "
                        "truncate it silently, so the value is rejected instead.")
                   {:nihilite/kind :nihilite/invalid-modify-value
                    :nihilite/returned (class rv)
                    :nihilite/narrowed (class narrowed)
                    :nihilite/target target
                    :nihilite/descriptor descriptor}))
          narrowed))
      :else rv)))

(defn dispatch-return-for-spec
  [spec-id self args original]
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
            (let [desc (or (:method-descriptor s) (:method-descriptor spec))
                  rv'  (coerce-return rv desc)]
              (when-not (modify-value-compatible? rv' original desc)
                (throw (ex-info
                         (str ":modify bridge for spec " (:id s) " returned "
                              (.getName (class rv'))
                              ", which cannot replace the target's "
                              (:method-descriptor spec)
                              " return value. A :modify bridge must RETURN the"
                              " replacement value (it may take ctx as its"
                              " single argument, but must not return it).")
                         {:nihilite/kind :nihilite/invalid-modify-value
                          :nihilite/id (:id s)
                          :nihilite/returned (class rv')
                          :nihilite/descriptor desc})))
              (reset! result rv')
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
    original))

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
   bridge that wants a value must return one.

   The value is checked against the target method's declared return type
   the same way a :modify bridge's is. A :redefine hook has no previous
   value to compare against -- it replaced the body, so nothing ran
   before it -- which made this the one path where a wrong-shaped return
   reached the woven method unchecked and surfaced as a bare
   ClassCastException naming neither the hook nor the spec."
  [host-internal method-name self args descriptor]
  (try
    (let [param-count (count args)
          spec-id     (lookup-spec-for-call host-internal method-name param-count descriptor :redefine)]
      (if-let [spec (and spec-id (reg/lookup spec-id))]
        (if-let [bridge-fn (safe-bridge spec)]
          (try
            (stats/bump-fired! spec-id)
            (let [desc (or (:method-descriptor spec) descriptor)
                  rv   (coerce-return (bridge-fn self args method-name) desc)]
              (when-not (modify-value-compatible? rv nil desc)
                (throw (ex-info
                         (str ":redefine bridge for spec " spec-id " returned "
                              (.getName (class rv))
                              ", which cannot be the target's " desc
                              " return value. A :redefine bridge must RETURN"
                              " a value the target method can return.")
                         {:nihilite/kind :nihilite/invalid-redefine-value
                          :nihilite/id spec-id
                          :nihilite/returned (class rv)
                          :nihilite/descriptor desc})))
              rv)
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
