(ns nihilite.kernel.bucket
  "Registry-to-position bucketing for the advice transformer. Pulls the
   specs registered for a target class and partitions them into the four
   ByteBuddy positions (:entry, :return, :throw, :redefine). Also builds
   the per-position ElementMatcher.Junction the transformer applies to a
   method description."
  (:require [clojure.string :as str]
            [nihilite.registry]
            [nihilite.registry.dispatch])
  (:import [net.bytebuddy.matcher ElementMatchers]))

(defn- lookup-matching
  "Returns the IFn behind `nihilite.registry/matching` if it is
   resolvable, else nil. Used by ByteBuddy-generated transformer stubs
   on threads where the registry namespace may not be loaded. Falls
   back through (resolve) -> (Var/getRawRoot) -> (RT/var + deref) so
   both AOT and driver/JAR deployments yield the IFn."
  []
  (try
    (let [v (resolve 'nihilite.registry/matching)]
      (cond
        (instance? clojure.lang.IFn v) v
        (instance? clojure.lang.Var v)
        (let [val (.getRawRoot ^clojure.lang.Var v)]
          (when (instance? clojure.lang.IFn val) val))
        :else nil))
    (catch Throwable _ nil)))

(defn- spec-field
  "Reads a keyword-keyed field from a spec (a Clojure map). Returns the
   raw value (keyword stays keyword)."
  [spec key]
  (get ^clojure.lang.IMap spec key))

(defn- empty-buckets []
  {:entry #{} :return #{} :throw #{} :redefine #{}})

(defn collect-buckets
  "Maps the specs registered for a target class to the four position
   buckets ByteBuddy needs. Returns nil when the target has no specs
   (nothing to do) so the transformer leaves the builder untouched."
  [^net.bytebuddy.description.type.TypeDescription type-description]
  (when-let [specs (some-> (lookup-matching)
                           (.invoke ^java.lang.String (.getInternalName type-description))
                           (some-> (vec)))]
    (when (seq specs)
      (let [by-position (reduce
                         (fn [acc spec]
                           (let [p    (spec-field spec :position)
                                 name (spec-field spec :method-name)]
                             (when (and p name)
                               (let [desc (spec-field spec :source-descriptor)
                                     bucket-key (if (keyword? p) p (keyword p))
                                     key [name desc]]
                                 (assoc-in acc [bucket-key]
                                           (conj (or (get-in acc [bucket-key]) #{}) key))))))
                         (empty-buckets)
                         specs)]
        (when (some identity (vals by-position))
          by-position)))))

(defn matcher-for
  "Builds the ElementMatcher.Junction over the methods in one position
   bucket: any of the (name, optional descriptor) pairs, excluding
   constructors and type initializers."
  [keys]
  (if (empty? keys)
    nil
    (let [matcher (reduce
                   (fn [acc [name desc]]
                     (let [named (ElementMatchers/named name)]
                       (if (and desc (not (str/blank? desc)))
                         (.or acc (.and named (ElementMatchers/hasDescriptor desc)))
                         (.or acc named))))
                   (ElementMatchers/none)
                   keys)]
      (.and (.and matcher (ElementMatchers/not (ElementMatchers/isConstructor)))
            (ElementMatchers/not (ElementMatchers/isTypeInitializer))))))
