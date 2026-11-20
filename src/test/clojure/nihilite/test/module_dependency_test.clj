(ns nihilite.test.module-dependency-test
  "Machine-checkable dependency contracts for the builder/trainer/crafter boundary."
  (:require [clojure.test :refer [deftest is]]
            [nihilite.builder.capability]
            [nihilite.builder.registry :as registry]
            [nihilite.builder.registry.runtime :as runtime]
            [nihilite.crafter.annparam]
            [nihilite.crafter.bytegen]
            [nihilite.crafter.classgen]
            [nihilite.crafter.descriptor]
            [nihilite.crafter.exceptions]
            [nihilite.crafter.jul]
            [nihilite.trainer.agent :as agent]
            [nihilite.trainer.installer]))

(def ^:private crafter-namespaces
  '[nihilite.crafter.annparam
    nihilite.crafter.bytegen
    nihilite.crafter.classgen
    nihilite.crafter.descriptor
    nihilite.crafter.exceptions
    nihilite.crafter.jul])

(defn- namespace-symbol
  [value]
  (cond
    (instance? clojure.lang.Namespace value)
    (.-name ^clojure.lang.Namespace value)

    (symbol? value)
    value))

(defn- ns-depends
  "The direct namespace dependencies visible in a loaded namespace's metadata."
  [ns-sym]
  (let [ns (find-ns ns-sym)]
    (->> (concat (vals (ns-aliases ns))
                 (keep #(get (meta %) :ns) (vals (ns-refers ns))))
         (keep namespace-symbol)
         set)))

(defn- dependency-graph
  "The transitive namespace dependency graph rooted at ns-sym."
  [ns-sym]
  (loop [pending [ns-sym]
         visited #{}]
    (if-let [current (first pending)]
      (if (contains? visited current)
        (recur (rest pending) visited)
        (recur (concat (rest pending) (ns-depends current))
               (conj visited current)))
      visited)))

(defn- namespace-in-prefix?
  [prefix ns-sym]
  (.startsWith (name ns-sym) prefix))

(deftest registry-compatibility-holders-share-runtime-atoms
  (is (identical? registry/instrumentation-ref runtime/instrumentation-ref)
      "registry/instrumentation-ref must hold the runtime instrumentation atom")
  (is (identical? registry/redefine-dispatcher-ref runtime/redefine-dispatcher-ref)
      "registry/redefine-dispatcher-ref must hold the runtime dispatcher atom")
  (is (identical? registry/uninstaller-ref runtime/uninstaller-ref)
      "registry/uninstaller-ref must hold the runtime uninstaller atom"))

(deftest trainer-entry-point-shares-runtime-holder
  (is (identical? agent/entry-point runtime/entry-point-ref)
      "trainer.agent/entry-point must hold the runtime entry-point atom"))

(deftest trainer-boundary-does-not-depend-on-builder-registry
  (doseq [ns-sym '[nihilite.trainer.agent
                   nihilite.trainer.installer]]
    (let [forbidden (filter #(= 'nihilite.builder.registry %)
                            (dependency-graph ns-sym))]
      (is (empty? forbidden)
          (str ns-sym " must not depend on nihilite.builder.registry; found "
               (pr-str (set forbidden)))))))

(deftest capability-does-not-depend-on-trainer
  (let [forbidden (filter #(namespace-in-prefix? "nihilite.trainer." %)
                          (dependency-graph 'nihilite.builder.capability))]
    (is (empty? forbidden)
        (str "nihilite.builder.capability must not depend on trainer namespaces; found "
             (pr-str (set forbidden))))))

(deftest crafter-boundary-does-not-depend-on-builder-or-trainer
  (doseq [ns-sym crafter-namespaces]
    (let [forbidden (filter #(or (namespace-in-prefix? "nihilite.builder." %)
                                  (namespace-in-prefix? "nihilite.trainer." %))
                            (dependency-graph ns-sym))]
      (is (empty? forbidden)
          (str ns-sym " must not depend on builder or trainer namespaces; found "
               (pr-str (set forbidden)))))))
