(ns nihilite.kernel.bucket
  "Maps registry hook specs into the four ByteBuddy position buckets
   (:entry / :return / :throw / :redefine) and builds the method
   matchers each bucket needs.

   Pure Clojure — no generated classes, no ByteBuddy AgentBuilder. The
   installer's combined transformer and the registry retransform path
   both call into here."
  (:require [clojure.string :as str])
  (:import [net.bytebuddy.matcher ElementMatchers]))

(defn- lookup-matching []
  (let [v (resolve 'nihilite.registry/matching)]
    (when v ^clojure.lang.IFn v)))

(defn- spec-field [spec key]
  (let [entry (first (filter (fn [^java.util.Map$Entry e]
                               (= key (.getKey e)))
                             (seq (.entrySet ^java.util.Map spec))))]
    (when entry
      (let [v (.getValue entry)]
        (when v (str v))))))

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
                                     bucket-key (keyword (str p))
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
