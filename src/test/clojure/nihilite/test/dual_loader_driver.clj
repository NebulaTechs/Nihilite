(ns nihilite.test.dual-loader-driver
  "Characterisation driver: ONE class name, TWO ClassLoaders, ONE hook.

   The registry has no ClassLoader dimension. `method-key` is
   \"<internal-class>/<method>#<descriptor>\"
   (registry/method-key) and the index is keyed by
   internal class name alone, while
   `nihilite.kernel.transformer/apply-advice-transformer` names its
   class-loader argument `_class-loader` and never reads it
   (transformer/apply-advice-transformer). So a name is
   the identity: two loaded Classes that share a name share one bucket, one
   HookSpec and one :fired counter.

   nihilite.test.retransform-batch-atomicity-test already loads a class
   twice through ClassLoadingStrategy/WRAPPER, but it drives a STUB
   Instrumentation and only counts retransformClasses calls: it never
   reaches registry/install! and never observes advice. This driver closes
   that gap through the production path -- ByteBuddyAgent/install, then a
   reflective Agent/premain, then api/install! -- and asserts on REAL
   FIRINGS (:fired, plus a per-loader counter), never on a woven count.

   MEASURED ON JDK 27 (the host's only JDK, so no single-JDK claim is
   being made):

     Class name              nihilite.collide.DupTarget (ByteBuddy-generated,
                             subclass of java.lang.Object, public String
                             ping() returning a fixed \"original\" body)
     Loader 1                <classpath> -> net.bytebuddy.dynamic.loading.
                             ClassFileLocator$ForClassLoader$1 of byte[1]
                             (WRAPPER)
     Loader 2                <classpath> -> ...ByteArrayClassLoader of byte[2]
                             (WRAPPER)
     identical?              false
     getClassLoader differs  true, and neither is the parent of the other
     getName equal           true
     loaded + modifiable     2 / 2
     install-status! :woven-count    2   (see FINDING 1)
     install-status! :target-loader :unloaded  (see FINDING 3)
     :fired after loader-1's call    1
     :fired after loader-2's call    2
     per-loader attribution   loader-1 -> 1, loader-2 -> 1
     body under the :entry hook      \"original\" from BOTH loaders
     uninstall!               true, :woven-count 2 (2 classes retransformed)
     install-status! after uninstall!   :registered? false, :fired 0
     per-loader fires after uninstall!  0 / 0
     body after uninstall!          \"original\" from BOTH loaders

     FINDING 1 -- :woven-count is 2. The single spec is woven into BOTH
       loaded Classes because `retransform-loaded-matching!` walks
       getAllLoadedClasses by NAME (registry/retransform-loaded-matching!) and
       retransforms
       each match on its own. The count therefore reports how many distinct
       loaded Classes share a name, not how many hooks exist.

     FINDING 2 -- the collision is total, and it is SILENT. One HookSpec
       serves both Classes, both fire, and nothing anywhere records which
       loader produced an event. `HookEvent` carries `:self`, so the loader
       is recoverable from the receiver, but no registry key, counter or
       index is loader-aware. A hook cannot be installed on one loader's
       copy of a class and not the other's; uninstall! and swap-bridge! hit
       both too, and uninstall! retransforms BOTH
       (installer/uninstall-spec-with-target!).

     FINDING 3 -- install-status! :target-loader reports :unloaded while
       both Classes are loaded. `target-loader` resolves the name through
       the SYSTEM classloader only (registry/target-loader), and a
       WRAPPER-loaded class is not visible there. That is a lookup-scope
       limitation, not a load-state measurement; this driver reports it and
       does not assert on it.

   The bridge records the receiver's own Class, so the per-loader counts
   below are attribution by identity, not inference from a shared total."
  (:require [nihilite.api :as api])
  (:import [net.bytebuddy ByteBuddy]
           [net.bytebuddy.agent ByteBuddyAgent]
           [net.bytebuddy.description.modifier ModifierContributor$ForMethod
                                                    Visibility]
           [net.bytebuddy.dynamic.loading ClassLoadingStrategy$Default]
           [net.bytebuddy.implementation FixedValue])
  (:gen-class
    :name nihilite.test.dualLoaderDriver
    :prefix "dld-"))

(def ^:private dup-target-dotted "nihilite.collide.DupTarget")

(def ^:private dup-target-internal "nihilite/collide/DupTarget")

(def ^:private ping-descriptor "()Ljava/lang/String;")

(def ^:private ping-body
  "What the unwoven ping() returns. Asserted before the hook, under it and
   after uninstall!, so an :entry that accidentally replaced the body cannot
   pass unnoticed."
  "original")

(def ^:private spec-id "dual-loader-ping")

;; Class object -> how many times the bridge ran for THAT loader's Class.
;; Keyed by the Class itself, so the attribution is by identity: Class does
;; not override equals, and two same-named Classes from two loaders are
;; distinct keys.
(def ^:private per-loader-fires (atom {}))

;; Bootstrapping helpers.

(defn- generate-dup-target
  "ByteBuddy-built target: public, extends Object, one method `ping()`
   returning a fixed String, so the original body stays observable from
   both sides of the hook. Two independent loads of these bytes give two
   distinct Classes that share a name."
  []
  (-> (ByteBuddy.)
      (.subclass Object)
      (.name dup-target-dotted)
      (.defineMethod "ping" String
                     (into-array ModifierContributor$ForMethod
                                 [Visibility/PUBLIC]))
      (.intercept (FixedValue/value ping-body))
      (.make)))
(defn- load-in-fresh-wrapper
  "One load through ClassLoadingStrategy/WRAPPER, which is what makes the
   collision reachable at all: the name is the same and the loader is not.
   WRAPPER builds a fresh child of the given loader per call, so two calls
   yield two unrelated loaders rather than a second copy in one loader."
  []
  (.getLoaded (.load (generate-dup-target)
                     (ClassLoader/getSystemClassLoader)
                     ClassLoadingStrategy$Default/WRAPPER)))

(defn- fail! [why code]
  (println "dualLoaderDriver FAIL:" why)
  (flush)
  (System/exit code))

(defn- say! [& args]
  (apply println args)
  (flush))

(defn- await-worker!
  "Blocks on the agent worker's latch. premain arms the AgentBuilder
   synchronously but brings the Clojure-side registry up on the worker
   thread, so a driver that installs a spec inside that window races the
   registry load. Waiting makes the measurement a function of the hook and
   not of thread scheduling.

   Resolved as a static Method rather than called on the Class object:
   Reflector reads `(.awaitWorkerReady (Class/forName ...))` as an instance
   call and throws `No matching field found`."
  []
  (let [m (.getDeclaredMethod (Class/forName "nihilite.kernel.Agent")
                              "awaitWorkerReady" (into-array Class []))]
    (.setAccessible m true)
    (.invoke m nil (object-array []))))

(defn- wrapper-parent
  "The loader ClassLoader/WRAPPER built this Class under.

   ClassLoader#parent is a private field, so `.-parent` degrades to
   Reflector, which then looks for the field on the RUNTIME class
   (ByteArrayClassLoader) and reports `No matching field found`. The public
   getParent() is the same value."
  ^ClassLoader [^Class c]
  (.getParent ^ClassLoader (.getClassLoader c)))

(defn- loaded-matching
  "The distinct loaded, modifiable Classes whose name matches `dot-name`,
   counted straight from the Instrumentation.

   This is the independent cross-check for install-status!'s :woven-count:
   it reads the JVM's own class table instead of the number Nihilite
   reported, so a count that disagreed with reality would be visible here."
  [^java.lang.instrument.Instrumentation inst ^String dot-name]
  (vec (filter (fn [^Class c]
                 (and c
                      (= dot-name (.getName c))
                      (.isModifiableClass inst c)))
               (.getAllLoadedClasses inst))))

(defn- fresh-instance ^Object [^Class c]
  (.newInstance (.getDeclaredConstructor c (into-array Class []))
                (object-array [])))

(defn- call-ping ^String [^Class c]
  (let [m (doto (.getDeclaredMethod c "ping" (into-array Class []))
            (.setAccessible true))]
    (.invoke m (fresh-instance c) (object-array []))))

(defn- fires-of [^Class c]
  (get @per-loader-fires c 0))
(defn- fired-delta ^long [id before]
  (long (- (or (:fired (api/install-status! id)) 0) before)))

(defn- want-body!
  "The original body must survive an :entry hook, and must come back after
   uninstall!. Checking the returned value is what makes that a claim: a
   firing count on its own would not notice a replaced body."
  [phase loader value]
  (when (not= ping-body value)
    (fail! (str phase ": " loader "'s ping() returned " (pr-str value)
                ", expected " (pr-str ping-body)
                " -- the original body was not the one running")
           5)))

;; Phase 1: the two Classes must be genuinely distinct.

(defn- check-distinct! [^Class c1 ^Class c2]
  (let [l1 (.getClassLoader c1)
        l2 (.getClassLoader c2)
        parent (wrapper-parent c1)]
    (say! "DUAL_CLASSES" (pr-str {:name (.getName c1)
                                  :identical (identical? c1 c2)
                                  :same-name (= (.getName c1) (.getName c2))
                                  :loader-1 (str l1)
                                  :loader-2 (str l2)
                                  :loader-1-parent (str parent)}))
    (when (identical? c1 c2)
      (fail! "the two loads returned the SAME Class object, so there is no collision to characterise" 2))
    (when-not (= (.getName c1) (.getName c2))
      (fail! (str "the two Classes have different names (" (.getName c1) " vs "
                  (.getName c2) "); this driver measures a same-name collision only")
             2))
    (when (identical? l1 l2)
      (fail! "both Classes were defined by the SAME ClassLoader; the WRAPPER strategy did not produce two loaders" 2))
    (when (identical? l1 (ClassLoader/getSystemClassLoader))
      (fail! "the wrapper strategy returned a SYSTEM-loaded Class, so the loader is not isolated" 2))
    (when-not (identical? parent (ClassLoader/getSystemClassLoader))
      (fail! (str "the wrapper's parent is " parent
                  ", not the system loader, so this run did not measure what the docstring claims")
             2))
    (say! "DUAL_CLASSES_OK identical?=false same-name=true loaders-differ=true")))

;; Phase 2: the production install!, and what it claims.

(defn- install-hook! []
  (api/install! {:id               spec-id
                 :target-internal dup-target-internal
                 :method-name     "ping"
                 :descriptor      ping-descriptor
                 :position        :entry
                 :bridge          (fn [ctx]
                                    (swap! per-loader-fires update
                                           (class (:self ctx)) (fnil inc 0))
                                    nil)
                 :note            "dual-loader characterisation: one name, two loaders"}))

(defn- check-install-status! [expected-classes]
  (let [st (api/install-status! spec-id)
        woven (:woven-count st)
        spec-count (count (api/list-specs))]
    (say! "DUAL_STATUS" (pr-str (select-keys st [:registered? :woven-count
                                                 :pending? :target-loader
                                                 :last-error :fired])))
    (say! "DUAL_WOVEN" (pr-str {:woven-count woven
                                 :loaded-modifiable expected-classes
                                 :registered-specs spec-count}))
    ;; :registered? / :pending? arrive as java.lang.Boolean boxes, so they
    ;; are compared by identity against Boolean/TRUE rather than with `=`,
    ;; which would report a boxed true as unequal to a Clojure true.
    (when-not (identical? Boolean/TRUE (:registered? st))
      (fail! (str "install! left :registered? " (pr-str (:registered? st)) ", expected true") 4))
    (when-not (identical? Boolean/FALSE (:pending? st))
      (fail! (str "install! left :pending? " (pr-str (:pending? st))
                  " with a woven count of " woven
                  "; the agent is not armed against the target loaders")
             4))
    (when (pos? (:fired st))
      (fail! (str ":fired was " (:fired st)
                  " BEFORE any call to ping(); a counter that moves without a call is not a firing measurement")
             4))
    (when-not (= 1 spec-count)
      (fail! (str "the registry holds " spec-count " specs, expected exactly 1 -- the collision is being described as a single hook")
             4))
    ;; The gate is that Nihilite's count matches the JVM's own count of
    ;; same-named loaded classes. That is the only loader-agnostic claim
    ;; the registry can make, and it is the observation the batch-atomicity
    ;; test only ever stubbed.
    (when-not (= expected-classes woven)
      (fail! (str "install-status! reported :woven-count " woven
                  " but the JVM holds " expected-classes
                  " distinct loaded+modifiable Classes named " dup-target-dotted
                  " -- the install-side count and the class table disagree")
             3))
    (say! "DUAL_WOVEN_OK woven-count=" woven " matches the JVM's own class count")))

;; Phase 3: one call per loader. The delta of the single shared :fired
;; counter is the proof the advice RAN -- a woven count cannot be that
;; proof, because a call does not change it -- and the per-loader counter is
;; the attribution, since one shared counter cannot say which of the two
;; loaders it was counting.

(defn- check-both-loaders-fire! [^Class c1 ^Class c2]
  (let [before (:fired (api/install-status! spec-id))
        r1 (call-ping c1)
        d1 (fired-delta spec-id before)
        f1 (fires-of c1)
        mid (:fired (api/install-status! spec-id))
        r2 (call-ping c2)
        d2 (fired-delta spec-id mid)
        f2 (fires-of c2)
        total (:fired (api/install-status! spec-id))]
    (say! "DUAL_FIRES" (pr-str {:loader-1 {:fired-delta d1 :per-loader f1 :returned r1}
                                :loader-2 {:fired-delta d2 :per-loader f2 :returned r2}
                                :shared-fired total}))
    (when-not (= 1 d1)
      (fail! (str "ping() on loader-1's Class moved :fired by " d1
                  ", expected 1 -- the advice did not run for loader-1's copy")
             5))
    (when-not (= 1 f1)
      (fail! (str "loader-1's Class was attributed " f1 " fire(s), expected 1") 5))
    (when-not (= 1 d2)
      (fail! (str "ping() on loader-2's Class moved :fired by " d2
                  ", expected 1 -- the advice did not run for loader-2's copy")
             6))
    (when-not (= 1 f2)
      (fail! (str "loader-2's Class was attributed " f2 " fire(s), expected 1") 6))
    (want-body! "under the :entry hook" "loader-1" r1)
    (want-body! "under the :entry hook" "loader-2" r2)
    (when-not (= 2 total)
      (fail! (str "the shared :fired counter reads " total
                  " after one call per loader, expected 2")
             6))
    (say! "DUAL_FIRES_OK loader-1 fired 1, loader-2 fired 1, shared :fired=2")))

;; Phase 4: uninstall, then prove the advice is gone from BOTH loaders.
;; Checking one only would miss a one-sided removal, which is the outcome a
;; loader-dimensioned registry would produce and the one worth excluding.

(defn- check-uninstalled! [^Class c1 ^Class c2]
  (let [removed (api/uninstall! spec-id)
        ust (api/install-status! spec-id)
        f1-before (fires-of c1)
        f2-before (fires-of c2)
        r1 (call-ping c1)
        r2 (call-ping c2)
        f1' (fires-of c1)
        f2' (fires-of c2)]
    (say! "DUAL_UNINSTALL" (pr-str {:removed removed
                                    :registered? (:registered? ust)
                                    :woven-count (:woven-count ust)
                                    :fired (:fired ust)
                                    :lookup-still-present (some? (api/lookup spec-id))
                                    :loader-1 {:fires f1-before "->" f1' :returned r1}
                                    :loader-2 {:fires f2-before "->" f2' :returned r2}}))
    (when-not removed
      (fail! "api/uninstall! did not remove the spec" 7))
    (when-not (identical? Boolean/FALSE (:registered? ust))
      (fail! (str "install-status! still reports :registered? "
                  (pr-str (:registered? ust)) " after uninstall!")
             7))
    (when (some? (api/lookup spec-id))
      (fail! "api/lookup still returns the spec after uninstall!" 7))
    (when (pos? (:fired ust))
      (fail! (str "install-status! reports :fired " (:fired ust)
                  " after uninstall!; the stats record should have been removed")
             7))
    (when-not (= f1-before f1')
      (fail! (str "the hook fired " (- f1' f1-before)
                  " more time(s) for loader-1's Class after uninstall!")
             8))
    (when-not (= f2-before f2')
      (fail! (str "the hook fired " (- f2' f2-before)
                  " more time(s) for loader-2's Class after uninstall!")
             8))
    (want-body! "after uninstall!" "loader-1" r1)
    (want-body! "after uninstall!" "loader-2" r2)
    (say! "DUAL_UNINSTALL_OK fires 0/0, original body restored in both loaders")))

;; Driver.

(defn dld-main [& _args]
  (let [inst (ByteBuddyAgent/install)
        Agent (Class/forName "nihilite.kernel.Agent")
        premain (.getDeclaredMethod Agent "premain"
                                    (into-array Class [String
                                                       java.lang.instrument.Instrumentation]))]
    (.setAccessible premain true)
    (.invoke premain nil (object-array [nil inst]))
    (await-worker!)

    ;; Both loads happen BEFORE the install, so install! has two same-named
    ;; classes already in the JVM to retransform. Loading them afterwards
    ;; would measure the AgentBuilder's load-time path instead, which is a
    ;; different question.
    (let [c1 (load-in-fresh-wrapper)
          c2 (load-in-fresh-wrapper)
          loaded (loaded-matching inst dup-target-dotted)]
      (check-distinct! c1 c2)
      (when-not (= 2 (count loaded))
        (fail! (str "expected 2 distinct loaded+modifiable Classes named "
                    dup-target-dotted ", found " (count loaded)
                    " -- the collision this driver measures is not present")
               3))
      (say! "DUAL_LOADED" (pr-str {:loaded-modifiable (count loaded)}))
      (install-hook!)
      (check-install-status! (count loaded))
      (check-both-loaders-fire! c1 c2)
      (check-uninstalled! c1 c2)
      (say! "DUAL_SUMMARY"
            (pr-str {:distinct-classes 2
                     :one-spec-serves-both true
                     :woven-count (:woven-count (api/install-status! spec-id))
                     :per-loader-fires (into {} (mapv (fn [[c n]]
                                                         [(str c) n])
                                                       @per-loader-fires))}))
      (say! "DRIVER_PASS dual-loader characterisation complete")
      (System/exit 0))))

(defn -main [& args]
  (dld-main args))
