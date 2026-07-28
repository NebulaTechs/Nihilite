(ns nihilite.observers.selector
  "Selector DSL. A selector map is a flat set of constraints:
   absent keys mean 'no constraint on that field'; an empty
   selector `{}` matches every spec. `:class`, `:method`,
   `:loader-id` accept glob strings (`*` / `?`) or explicit
   `java.util.regex.Pattern` values; other keys are
   exact-match. `:action` accepts a single keyword or a set."
  (:require [nihilite.registry :as reg])
  (:import [java.util.regex Pattern]))

(defn- glob->pattern
  "Translate a glob string into an anchored `Pattern`. A bare
   literal (no `*` or `?`) compiles to an anchored exact match
   — preserves the old exact-match contract for callers that
   pass plain class/method names."
  ^Pattern [^String glob]
  (let [sb (StringBuilder. "^")
        n (.length glob)]
    (dotimes [i n]
      (let [c (.charAt glob i)]
        (case c
          \\ (do (.append sb "\\\\") (.append sb (.charAt glob (inc i))) (inc i))
          \. (.append sb "\\.")
          \* (.append sb ".*")
          \? (.append sb ".")
          (.append sb c))))
    (.append sb "$")
    (Pattern/compile (.toString sb))))

(defn- match-string
  "True if `field-value` matches a glob string or Pattern predicate."
  [field-value predicate-value]
  (cond
    (nil? predicate-value) true
    (instance? Pattern predicate-value)
    (.matches (.matcher ^Pattern predicate-value (str field-value)))
    (string? predicate-value)
    (.matches (.matcher ^Pattern (glob->pattern predicate-value)
                        (str field-value)))
    :else
    (= field-value predicate-value)))

(defn- match-numeric [field-value predicate-value]
  (if (nil? predicate-value)
    true
    (= field-value predicate-value)))

(defn- match-keyword-set [spec-action predicate-value]
  (cond
    (nil? predicate-value) true
    (set? predicate-value)   (contains? predicate-value spec-action)
    (keyword? predicate-value) (= predicate-value spec-action)
    :else false))

(defn- match-keyword [field-value predicate-value]
  (if (nil? predicate-value)
    true
    (= field-value predicate-value)))

(defn matches?
  "True if `spec` (a HookSpec map or anything with the standard
   field keys) satisfies every constraint in `selector`."
  [selector spec]
  (and
    (match-string      (or (:target-internal spec) (:class spec))   (:class selector))
    (match-string      (or (:method-name spec) (:method spec))      (:method selector))
    (match-string      (:loader-id spec)                            (:loader-id selector))
    (match-string      (or (:source-descriptor spec) (:descriptor spec)) (:descriptor selector))
    (match-numeric     (:arity spec)                                (:arity selector))
    (match-keyword-set (:action spec)                               (:action selector))
    (match-keyword     (:position spec)                             (:position selector))
    (match-string      (:tag spec)                                  (:tag selector))
    (match-string      (:id spec)                                   (:id selector))))

(defn select-targets
  "Return a defensive vector of specs from `reg/snapshot` that
   match `selector`."
  ([] (select-targets {}))
  ([selector]
   (vec (filter (partial matches? selector)
                 (.values ^java.util.Map (reg/snapshot))))))
