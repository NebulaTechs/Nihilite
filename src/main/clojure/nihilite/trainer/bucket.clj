(ns nihilite.trainer.bucket
  "Registry-to-position bucketing for the advice transformer. Pulls the
   specs registered for a target class and partitions them into the four
   ByteBuddy positions (:entry, :return, :throw, :redefine). Also builds
   the per-position ElementMatcher.Junction the transformer applies to a
   method description."
  (:require [clojure.string :as str])
  (:import [net.bytebuddy.matcher ElementMatchers]))

(defn- lookup-matching
  "The IFn behind `nihilite.builder.registry.index/matching` if it is
   resolvable, else nil. Looked up by symbol rather than required: the
   ByteBuddy-generated transformer stubs call this on threads where the
   index namespace may not be loaded yet, and a compile-time dependency
   would drag the registry into the class loading path. Falls back through
   (resolve) -> (Var/getRawRoot) so both AOT and driver/JAR deployments
   yield the IFn."
  []
  (try
    (let [v (resolve 'nihilite.builder.registry.index/matching)]
      (cond
        (instance? clojure.lang.IFn v) v
        (instance? clojure.lang.Var v)
        (let [val (.getRawRoot ^clojure.lang.Var v)]
          (when (instance? clojure.lang.IFn val) val))
        :else nil))
    (catch ClassNotFoundException _ nil)
    ;; A namespace that is still evaluating answers with an unlinked var;
    ;; its static initialiser throws this rather than ClassNotFoundException.
    (catch ExceptionInInitializerError _ nil)))

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
                               (let [desc (spec-field spec :method-descriptor)
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
