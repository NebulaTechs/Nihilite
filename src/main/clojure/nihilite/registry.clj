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
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [nihilite.registry.index :as index]
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

;; One installed hook.
;;
;; `internal-class` and `method-descriptor` are DERIVED, not read from the
;; spec map: install! computes them from `:target-internal` and
;; `:descriptor`, which are the fields a caller actually supplies. They
;; were called `source-class` and `source-descriptor`, which read as if a
;; hook could name some method other than the one it targets -- it cannot.
;; `method-descriptor` holds the same string as `:descriptor`.
;;
;; Renamed from `source-class`/`source-descriptor`; a caller reading
;; api/lookup sees the new names.
(defrecord HookSpec
  [id target-internal method-name position arity bridge note
   action method-key internal-class method-descriptor tag])

(defrecord HookContext
  [hook-id self args phase return-value cancelled])

(defrecord HookEvent
  ;; `stack` was the one field nothing ever put a value in: ->hook-event builds
  ;; the map without it, so a bridge reading (:stack ctx) got nil and had no
  ;; way to tell that from a stack trace that was never taken. Dropping the
  ;; field is the same thing (:stack still answers nil), minus the pretence.
  ;; The rest are filled by ->hook-event and are read from user bridges.
  [spec-id source phase self args return-value throwable
   cancelled? cancel! thread-name timestamp-ns sequence note])

(defn method-key
  [class-internal method-name descriptor]
  (str class-internal "/" method-name "#" descriptor))

(defn ctx-return
  "Return value of a HookContext/HookEvent, or nil when absent.

   Keyword access, not `.getReturnValue`: Clojure 1.12 no longer emits
   field accessors for defrecord, so an accessor call on a record
   compiles (the hint degrades to reflection) and then throws
   `IllegalArgumentException: No matching field found` on every call.
   Verified against clojure-1.12.6.jar: emit-defrecord in
   clojure/core_deftype.clj emits only the IRecord/IHashEq/IObj/
   ILookup/IPersistentMap/Map/Serializable surface plus the record body,
   with no getXxx generation."
  [x]
  (cond
    (instance? HookContext x) (:return-value x)
    (instance? HookEvent x) (:return-value x)
    :else nil))

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

(defonce ^:private by-id
  (ConcurrentHashMap.))
(defonce ^:private by-method
  (ConcurrentHashMap.))
(defonce ^:private ^Object registry-lock
  (Object.))
(defonce ^:private ^AtomicLong sequence-counter
  (AtomicLong.))

(defn get-by-id     ^ConcurrentHashMap [] by-id)
(defn get-by-method ^ConcurrentHashMap [] by-method)

(defn next-sequence [] (.incrementAndGet ^AtomicLong sequence-counter))

(def ^:private redefine-dispatcher-timeout-ms
  "How long install! waits for the redefine dispatcher before giving up.
   The worker installs it a moment after premain returns; measured at 0-150ms
   on a cold JVM, so this is generous by two orders of magnitude."
  5000)

(defn- redefine-dispatcher-ready?
  "Whether the :redefine advice has something to call.

   Resolved through RT/var because registry cannot require dispatch --
   dispatch requires registry. Same reasoning as the agent's dynamic lookups:
   the reference has to survive a class loader that may not have loaded
   dispatch yet."
  []
  (let [v (clojure.lang.RT/var "nihilite.registry.dispatch"
                              "redefine-dispatcher-ref")]
    (and (some? v)
         (.isBound ^clojure.lang.Var v)
         (some? @(.deref ^clojure.lang.Var v)))))

(defn- await-redefine-dispatcher!
  "Blocks until the redefine dispatcher is installed, or throws.

   A :redefine hook REPLACES the method body, so the advice can only return
   the target's value by calling the bridge through the dispatcher. arm-agent!
   arms the transformer and then starts the worker that installs the
   dispatcher, so a :redefine install landing in that gap produces a method
   that returns the stub default until the worker catches up -- a wrong
   answer, not a missing one.

   Measured, the gap is 0-150ms on a cold JVM and a real retransform costs
   100ms+, so the normal path does not land inside it. Waiting anyway makes
   that a guarantee rather than a timing observation, and costs nothing when
   the dispatcher is already there, which is every install after the first.

   Bounded, then it throws: a missing dispatcher means no :redefine hook can
   work, and silently installing one that returns the stub default is the
   failure this project has already paid for."
  [spec-id]
  (if (redefine-dispatcher-ready?)
    true
    (let [deadline (+ (System/currentTimeMillis)
                      redefine-dispatcher-timeout-ms)]
      (loop []
        (cond
          (redefine-dispatcher-ready?) true
          (> (System/currentTimeMillis) deadline)
          (throw (ex-info
                   (str "the redefine dispatcher is not installed, so a "
                        ":redefine hook cannot dispatch to its bridge (id="
                        spec-id "). Nothing installs it unless the agent is "
                        "mounted with -javaagent: or attached to.")
                   {:nihilite/kind :nihilite/redefine-dispatcher-unavailable
                    :nihilite/id spec-id
                    :nihilite/timeout-ms redefine-dispatcher-timeout-ms}))
          :else (do (Thread/sleep 20) (recur)))))))

(defn- get-or-create-bucket ^java.util.List [^ConcurrentHashMap m k]
  (or (.get m k)
      (let [fresh (CopyOnWriteArrayList.)]
        (if (nil? (.putIfAbsent m k fresh))
          fresh
          (.get m k)))))

(defn method-bucket ^java.util.List [mk] (get-or-create-bucket by-method mk))

(defn spec-bucket
  "The specs sharing `spec`'s target/method, restricted to specs at the
   SAME position.

   Without the position filter a dispatch for one position would walk the
   other positions' specs too, and since :redefine bridges take
   (self args method-name) while every other position's bridge takes a
   single ctx, such a call throws ArityException."
  [spec]
  (let [pos (:position spec)
        bucket (if-let [mk (:method-key spec)]
                 (some-> (.get by-method mk) seq)
                 (some-> (index/matching (:target-internal spec)) seq))]
    (when (seq bucket)
      (seq (filter #(= pos (:position %)) bucket)))))

(defn- retransform-one!
  [^Instrumentation inst ^Class c]
  (.retransformClasses inst (into-array Class [c]))
  c)

(defn retransform-loaded-matching!
  "Retransforms every already-loaded, modifiable class whose name matches
   `target-internal` (slash-separated) so the armed AgentBuilder re-visits
   it against the current registry. Returns the number of classes
   retransformed — 0 when there is no Instrumentation or no matching class
   is loaded.

   Every loader tier is reachable: the advice is reached through an
   invokedynamic call site, so the woven body names no class the target's
   classloader would have to resolve.

   Each class is retransformed in its own call because retransform is
   batch-atomic: a single illegal class fails the whole batch, so one bad
   target would silently disable every other hook. Failures are collected
   and thrown after the remaining classes have been processed — per-class
   attribution without hiding anything."
  ([^String target-internal]
   (let [lookup-fn (resolve 'nihilite.kernel.agent/agent-currentInstrumentation)
         inst (when lookup-fn (lookup-fn))]
     (retransform-loaded-matching! target-internal inst)))
  ([^String target-internal ^Instrumentation inst]
   (if (or (nil? inst) (nil? target-internal))
     0
     (let [^Instrumentation inst inst
           dot-name (.replace ^String target-internal "/" ".")
           candidates (->> (.getAllLoadedClasses inst)
                           (filter (fn [^Class c]
                                     (and c (.equals dot-name (.getName c)))))
                           (filter (fn [^Class c] (.isModifiableClass inst c))))]
       (loop [remaining (vec candidates)
              done 0
              failed []]
         (if (empty? remaining)
           (do
             (when (seq failed)
               (throw (ex-info (str "retransform failed for "
                                    (count failed) " class(es) matching "
                                    target-internal)
                               {:target target-internal
                                :failed failed})))
             (log/debug "retransform-loaded-matching! retransformed"
                        done "class(es) for target=" target-internal)
             done)
           (let [^Class c (first remaining)
                 result (try
                          (retransform-one! inst c)
                          (catch Throwable t
                            (log/error t "retransform failed for class"
                                       (.getName c))
                            t))]
             (recur (subvec remaining 1)
                    (if (instance? Throwable result) done (inc done))
                    (if (instance? Throwable result)
                      (conj failed [(.getName c) (str result)])
                      failed)))))))))

(defonce ^:private status-index
  (java.util.concurrent.ConcurrentHashMap.))

(defonce ^:private platform-class-loader
  (ClassLoader/getPlatformClassLoader))

(defn- target-loader
  "Which classloader the loaded target class is defined by, as a keyword:
     :bootstrap  -- JDK core (java.*), loader is nil
     :platform   -- JDK platform modules, Clojure/ByteBuddy's own classes
     :app        -- application classpath (-cp / -jar)
     :unloaded   -- no matching class is currently loaded
     :unknown    -- the class is loaded but its loader cannot be determined

   Every tier is reachable: app-loader targets are dispatched through a plain
   static call to the advice, while bootstrap and platform targets go through
   invokedynamic so the call site carries no reference the target's loader
   would have to resolve."
  [^String target-internal]
  (let [dot-name (.replace target-internal "/" ".")
        klass (try
                (Class/forName dot-name false (ClassLoader/getSystemClassLoader))
                (catch ClassNotFoundException _ nil)
                (catch Throwable _ nil))]
    (cond
      (nil? klass) :unloaded
      :else (let [l (.getClassLoader ^Class klass)]
              (cond
                (nil? l) :bootstrap
                (and (some? platform-class-loader) (= platform-class-loader l)) :platform
                :else :app)))))

(defn- status-record
  ^java.util.concurrent.atomic.AtomicReference [spec-id]
  (let [id (str spec-id)
        existing (.get status-index id)]
    (if (nil? existing)
      (let [created (java.util.concurrent.atomic.AtomicReference.
                      {:spec-id       id
                       :registered?   true
                       :woven-count   0
                       :pending?      true
                       :target-loader :unknown
                       :last-error    nil})]
        (if (nil? (.putIfAbsent status-index id created))
          created
          (.get status-index id)))
      existing)))

(defn- record-status!
  [spec-id f]
  (let [ref ^java.util.concurrent.atomic.AtomicReference (status-record spec-id)]
    (.set ref (f (.get ref)))
    nil))

(defn- mark-installed! [spec-id target-internal count]
  (record-status! spec-id
    (fn [cur]
      (assoc cur :woven-count (long count)
                  :pending?    (zero? (long count))
                  :target-loader (target-loader target-internal)
                  :registered? true))))

(defn- mark-uninstalled! [spec-id count]
  (record-status! spec-id
    (fn [cur]
      (assoc cur :woven-count (long count)
                  :pending?    false
                  :registered? false))))

(defn bump-revision!
  "Bumps the index revision counter. Called on every mutating operation so the
   transformer's negative match-cache can detect stale entries and re-query the
   registry."
  []
  (index/bump-revision!))

(defn- mark-error! [spec-id ex-msg]
  (record-status! spec-id
    (fn [cur]
      (assoc cur :last-error ex-msg))))

(defn- counter-value
  "Reads a StatsRecord counter atom into a plain long, or nil when the record
   has no such field."
  [r k]
  (some-> ^clojure.lang.Atom (get r k) deref))

(defn install-status!
  "Install-side and runtime-side status for one spec.

   Install side (status-index): :registered?, :woven-count (how many loaded
   classes were retransformed), :pending?, :target-loader, :last-error.
   Runtime side (stats-index): :fired, :modified, :cancelled, :exceptions --
   how many times the advice has actually reached the bridge, changed a return
   value, short-circuited, or thrown.

   Both sides are reported because neither implies the other. A woven-count of 1
   says the bytes were rewritten, not that the advice will run: the advice can
   be entered and find no spec, or a woven call site can stay unlinked while the
   target method keeps executing. That is why :fired is reported next to
   :woven-count rather than derived from it.

   A :fired of 0 is NOT a verdict. It means no bridge has run yet, which is
   indistinguishable from a permanently dead hook without observing whether the
   target method is being called at all -- which the advice cannot see, because
   an advice that never runs is exactly the case where it has no data. Check
   :fired after the target method has demonstrably been called."
  [id]
  (let [id (str id)
        ref (.get status-index id)
        base (if (nil? ref)
               {:spec-id id :registered? false :woven-count 0 :pending? false :last-error nil}
               (assoc (.get ^java.util.concurrent.atomic.AtomicReference ref) :spec-id id))
        rec  (stats/get-stats id)]
    (if (nil? rec)
      (assoc base :fired 0 :modified 0 :cancelled 0 :exceptions 0)
      (assoc base
             :fired      (counter-value rec :fired)
             :modified   (counter-value rec :modified)
             :cancelled  (counter-value rec :cancelled)
             :exceptions (counter-value rec :exceptions)))))

(def ^:private primitive-descriptor-chars
  "The JVM's type codes a descriptor may spell directly. V is here
   because a return type may be void; it is not a legal parameter type,
   which valid-descriptor? does not distinguish, since a spec whose
   descriptor claims otherwise cannot match a real method either."
  #{\B \C \D \F \I \J \S \V \Z})

(defn- valid-object-type-name?
  "Whether a `L...;` body is a well-formed internal class name: a
   sequence of `/`-separated identifiers, each starting with a letter or
   `_` or `$`. Deliberately does not resolve the name -- resolving means
   loading the type, and a hook installed to observe a class-loading path
   would re-enter the advice."
  [^String n]
  (and (pos? (count n))
       ;; Every character must be legal in an identifier. Checking each
       ;; one, rather than only the first of each slash-separated part,
       ;; is what rejects the dotted binary form: a descriptor spells
       ;; java.lang.String as Ljava/lang/String;, and a name carrying a
       ;; dot could never match a method.
       (every? #(or (Character/isLetterOrDigit %)
                    (= % \_)
                    (= % \$)
                    (= % \/))
              n)
       (not (Character/isDigit (.charAt n 0)))
       (every? #(pos? (count %))
               (str/split n #"/" -1))))

(defn- valid-type-token?
  "Whether `tok` is one JVM type: `[` prefixes (any number) followed by
   a primitive letter, an object name in `L...;`, or -- for a return type
   only, which this cannot tell -- nothing else."
  [^String tok]
  (and (pos? (count tok))
       (let [i (loop [i 0]
                 (if (and (< i (count tok)) (= \[ (.charAt tok i)))
                   (recur (inc i))
                   i))]
         (if (< i (count tok))
           (let [c (.charAt tok i)]
             (cond
               (contains? primitive-descriptor-chars c)
               (= i (dec (count tok)))

               (= \L c)
               (let [semi (.lastIndexOf tok (int \;))]
                 (and (= semi (dec (count tok)))
                      (> semi (inc i))
                      (valid-object-type-name? (subs tok (inc i) semi))))

               :else false))
           false))))

(defn- split-type-tokens
  "Splits a run of concatenated JVM types into one string per type.

   Scanning by hand rather than by regex: a `L...;` name can contain
   letters that look like type codes, so only the grammar knows where a
   name ends, and a regex would have to encode the same grammar anyway."
  [^String s]
  (let [n (count s)]
    (loop [i 0
           start 0
           out []]
      (cond
        (>= i n)
        (if (= start n) out (conj out (subs s start)))

        (= \[ (.charAt s i))
        (recur (inc i) start out)

        (= \L (.charAt s i))
        (let [semi (.indexOf s (int \;) i)]
          (if (neg? semi)
            (conj out (subs s start))
            (recur (inc semi) (inc semi) (conj out (subs s start (inc semi))))))

        :else
        (recur (inc i) (inc i) (conj out (subs s start (inc i))))))))

(defn- valid-descriptor?
  "Whether `s` is a syntactically valid JVM method descriptor: `(`
   followed by zero or more parameter types, `)`, then exactly one return
   type.

   A malformed descriptor is not a harmless slip. It becomes the spec's
   :method-key and its :method-descriptor, and both decide which loaded
   method a hook matches: ByteBuddy matches on the descriptor and
   nilhotite.registry.index keys its buckets by it. A descriptor this
   rejects is one ByteBuddy cannot match either, so the hook would
   register successfully and never fire -- the 'registers but never
   fires' mode docs/hook-limits.md documents, reached by a route the
   reader would not expect.

   Parses only, never resolves."
  [^String s]
  (and (>= (count s) 3)
       (= 40 (int (.charAt s 0)))
       (let [close (.indexOf s (int 41))]
         (and (pos? close)
              (let [params (split-type-tokens (subs s 1 close))]
                (and (every? valid-type-token? params)
                     ;; V names void, which is a return type only.
                     (not-any? #(= 86 (int (.charAt ^String % 0))) params)))
              (valid-type-token? (subs s (inc close)))))))

(defn- descriptor-param-count
  "How many parameters `descriptor` declares, or nil when it declares none
   that could be counted. A descriptor is the authority on a method's
   shape, so this is the authority on arity too."
  [^String s]
  (let [close (.indexOf s (int 41))]
    (when (pos? close)
      (count (split-type-tokens (subs s 1 close))))))

(defn install!
  [spec]
  (let [{:keys [id target-internal method-name position arity
                descriptor action tag]} spec
        spec-id    (some-> id str)
        spec-target (some-> target-internal str)
        spec-method (some-> method-name str)
        spec-pos    (normalize-position position)
        ;; The descriptor already states the parameter count, so an
        ;; omitted :arity is redundant rather than unknown. Falling back
        ;; to it makes the two agree by construction, and
        ;; lookup-spec-for-call's (or (nil? ar) (= ar pcnt)) check
        ;; actually run for a caller that left :arity out.
        spec-arity  (cond
                      (some? arity) (int arity)
                      (and (string? descriptor) (pos? (count descriptor)))
                      (descriptor-param-count descriptor)
                      :else nil)
        spec-desc   (some-> descriptor str)
        spec-action (if (contains? spec :action) (normalize-action action) :observe)
        spec-tag    (some-> tag str)
        desc-missing? (or (nil? spec-desc) (empty? spec-desc))
        spec-method-key (when-not desc-missing?
                          (method-key spec-target spec-method spec-desc))
        spec-internal-class (when-not desc-missing?
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
    (when-not (valid-descriptor? spec-desc)
      (throw (ex-info (str ":descriptor is not a valid JVM method descriptor (id="
                            spec-id "): " (pr-str spec-desc))
                      {:nihilite/kind :nihilite/bad-descriptor
                       :nihilite/id spec-id
                       :nihilite/descriptor spec-desc
                       :nihilite/target spec-target
                       :nihilite/method spec-method})))
    (let [declared (descriptor-param-count spec-desc)]
      (when-not (nil? declared)
        (when (and (some? spec-arity) (not= spec-arity declared))
          (throw (ex-info
                   (str ":arity " spec-arity " contradicts " (pr-str spec-desc)
                        ", which declares " declared " parameter"
                        (when (not= 1 declared) "s") " (id=" spec-id ")")
                   {:nihilite/kind :nihilite/arity-descriptor-mismatch
                    :nihilite/id spec-id
                    :nihilite/arity spec-arity
                    :nihilite/descriptor spec-desc
                    :nihilite/declared-params declared
                    :nihilite/target spec-target
                    :nihilite/method spec-method})))))
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
                           :internal-class spec-internal-class
                           :method-descriptor spec-desc)]
       ;; A :redefine hook replaces the method body, so from the moment the
       ;; retransform lands the advice's only route to the bridge is the
       ;; dispatcher. Wait before touching any index, so a timeout leaves
       ;; nothing half-registered: a spec that is in by-id but never woven is
       ;; indistinguishable from a live hook until someone checks.
       (when (= :redefine spec-pos)
         (await-redefine-dispatcher! spec-id))
       (locking registry-lock
         (bump-revision!)
         (let [prev (.put by-id (:id norm-spec) norm-spec)
              replaced? (some? prev)]
          (when replaced?
            (let [prev-bucket (index/live-bucket (:target-internal prev))]
              (when prev-bucket (.remove prev-bucket prev)))
            (when-let [pmk (:method-key prev)]
              (let [pmb (.get by-method pmk)]
                (when pmb (.remove pmb prev)))))
          (.add (index/bucket (:target-internal norm-spec)) norm-spec)
          (when-let [mk (:method-key norm-spec)]
            (.add (method-bucket mk) norm-spec))
          (when-not replaced?
            (stats/ensure-stats spec-id))
          ;; A replaced spec changes the registry contents, so the armed
          ;; AgentBuilder must re-visit already-loaded matching classes —
          ;; otherwise the new bridge/position never reaches the JVM.
          (let [woven (int (retransform-loaded-matching! spec-target))]
            (if replaced?
              (do (log/info "hook replaced:" (:id norm-spec)
                            "target=" (:target-internal norm-spec)
                            "method=" (:method-name norm-spec))
                  (mark-installed! (:id norm-spec) spec-target woven)
                  false)
              (do (log/info "hook registered:" (:id norm-spec)
                            "target=" (:target-internal norm-spec)
                            "method=" (:method-name norm-spec)
                            "@" (:position norm-spec)
                            "action=" (:action norm-spec)
                            (when-let [t (:tag norm-spec)] (str " tag=" t))
                            (when-let [n (:note norm-spec)] (str "// " n)))
                  (mark-installed! (:id norm-spec) spec-target woven)
                  true))))))))

(defn uninstall!
  [id]
  (let [by-id     (get-by-id)
        by-method (get-by-method)]
    (locking registry-lock
      (bump-revision!)
       (when-let [removed (.remove by-id (str id))]
         (let [target (:target-internal removed)
               b (index/live-bucket target)]
           (when b (.remove b removed))
           (index/forget-target! target b)
           (when-let [mk (:method-key removed)]
             (let [mb (.get by-method mk)]
               (when mb (.remove mb removed))
               (when (and mb (.isEmpty mb))
                 (.remove by-method mk mb))))
           (stats/remove-stats (:id removed))
           (let [installer (resolve 'nihilite.kernel.installer/uninstall-spec-with-target!)
                 ;; installer is resolved, not required: registry cannot
                 ;; require the kernel (installer requires registry back),
                 ;; and in a JVM that never mounted the agent the namespace
                 ;; is not loaded at all, so `resolve` answers nil. Calling
                 ;; that nil is an NPE out of a path documented to WARN --
                 ;; which is the shape the uninstall_warn_test asserts, and
                 ;; the only reason it passed is that the full contract
                 ;; runner shares one JVM with every other namespace, so
                 ;; something else had already loaded installer. No
                 ;; Instrumentation and no installer are the same situation
                 ;; as far as the caller is concerned: nothing to
                 ;; retransform, so the count is 0 and the existing zero
                 ;; branch below says so.
                 count (try
                         (if installer
                           (installer (str id) target)
                           0)
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
    (index/clear!)
    (.clear by-method)
    (bump-revision!)
    (stats/clear!)))

(defn matching
  "Specs currently registered for `target-internal`. The query the weaving
   machinery uses; prefer nilitite.registry.index/matching when you only need
   to read, since that layer exists precisely to keep callers off the
   registry's internals."
  ^java.util.List [target-internal]
  (index/matching target-internal))

(defn list-ids
  []
  (sort (vec (.keySet by-id))))

(defn lookup
  [id]
  (.get by-id (str id)))

(defn replace-bridge!
  "Swaps a registered spec's bridge for `new-bridge`, in place.

   The spec value itself is immutable, so a new map is built and every place
   that holds the old one has to be rewritten. There are three, and all
   three matter:

   - `by-id` is what a fire-time lookup reads for the spec itself.
   - `index/by-target` and `by-method` are what `spec-bucket` walks, and the
     walk reads `:bridge` off the spec it finds there. A dispatch therefore
     looks the spec up in one map and calls the bridge out of another, so
     rewriting only `by-id` swapped the map the caller can see while every
     real invocation kept running the previous bridge.

   It runs under the same lock and in the same remove-then-add shape as the
   replace branch of `install!` (see above), which is what keeps one spec
   value per id in all three maps.

   Deliberately does NOT bump the revision or retransform: the shape a
   transform matches on -- class, method, arity, position -- is unchanged, so
   the already-woven call sites are still correct and the next fire picks the
   new bridge up through the lookup. That is what makes this a hot rewrite."
  [id new-bridge]
  (let [by-id ^ConcurrentHashMap by-id
        k (str id)]
    (locking registry-lock
      (let [cur (.get by-id k)]
        (if (nil? cur)
          false
          (let [updated (assoc cur :bridge new-bridge)]
            (when-let [tb (index/live-bucket (:target-internal cur))]
              (.remove tb cur))
            (when-let [pmk (:method-key cur)]
              (when-let [pmb (.get by-method pmk)]
                (.remove pmb cur)))
            (.put by-id k updated)
            (.add (index/bucket (:target-internal updated)) updated)
            (when-let [mk (:method-key updated)]
              (.add (method-bucket mk) updated))
            (log/info "hook bridge swapped:" k)
            true))))))
