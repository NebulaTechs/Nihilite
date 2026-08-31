(ns nihilite.pointcut
  "Pointcut protocol and three implementations: ExactPointcut (default,
   preserves existing behavior), WildcardPointcut with *, **, ? globs,
   AnnotationPointcut matching annotation FQNs on methods or classes.

   C4 deliverable. Defaults to no behavioral change for callers that
   do not pass :matcher."
  (:require [clojure.string :as str])
  (:import (java.util.regex Pattern)
           (java.lang.reflect Method Constructor)))

(defprotocol Pointcut
  (matches? [p ctx]
    "Return true if this pointcut matches the given join-point ctx.
     ctx keys: :target-internal (String, dot or slash form),
     :method-name (String), :descriptor (String, optional),
     :method (optional java.lang.reflect.Method, for annotation check),
     :clazz (optional java.lang.Class, for annotation check)."))

(defn glob->regex
  "Translate a filesystem-style glob into a regex string.
   *  = zero or more chars (greedy)
   ?  = exactly one char
   \\  = literal backslash
   .  = literal dot"
  ^String [^String s]
  (let [sb (StringBuilder.)]
    (doseq [^Character c (seq s)]
      (cond
        (= c \\) (.append sb "\\\\")
        (= c \.) (.append sb "\\.")
        (= c \*) (.append sb ".*")
        (= c \?) (.append sb ".")
        :else (.append sb c)))
    (.toString sb)))

(defn- slash->dot
  "Normalize internal name to dot form so ExactPointcut does not care
   whether the caller passed 'java/lang/String' or 'java.lang.String'."
  ^String [^String s]
  (when s (str/replace s \/ \.)))

(deftype ExactPointcut [target-internal method-name descriptor]
  Pointcut
  (matches? [_ ctx]
    (and (= (slash->dot target-internal)
            (slash->dot (:target-internal ctx)))
         (= method-name (:method-name ctx))
         (or (nil? descriptor) (= descriptor (:descriptor ctx))))))

(deftype WildcardPointcut [target-pattern method-pattern]
  Pointcut
  (matches? [_ ctx]
    (let [^Pattern t-re (Pattern/compile (glob->regex target-pattern))
          ^Pattern m-re (Pattern/compile (glob->regex method-pattern))
          target (or (:target-internal ctx) "")
          method (or (:method-name ctx) "")]
      (and (.matches (.matcher t-re target))
           (.matches (.matcher m-re method))))))

(deftype AnnotationPointcut [annotation-fqns]
  Pointcut
  (matches? [_ ctx]
    (let [^Method m (:method ctx)
          ^Class  c (:clazz ctx)
          fqns    (set annotation-fqns)]
      (cond
        m (boolean (some #(contains? fqns (.getName ^java.lang.annotation.Annotation %))
                           (.getDeclaredAnnotations m)))
        c (boolean (some #(contains? fqns (.getName ^java.lang.annotation.Annotation %))
                           (.getDeclaredAnnotations c)))
        :else false))))

(defn exact
  ([] (exact nil nil nil))
  ([target-internal method-name descriptor]
   (->ExactPointcut (or target-internal "") (or method-name "") descriptor)))

(defn wildcard
  [target-pattern method-pattern]
  (->WildcardPointcut target-pattern method-pattern))

(defn annotation
  [annotation-fqns]
  (->AnnotationPointcut (vec annotation-fqns)))