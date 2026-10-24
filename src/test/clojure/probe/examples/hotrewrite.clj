(ns probe.examples.hotrewrite
  "Deterministic probe for the hotrewrite example.

   The example namespace must already be loaded. It is resolved from the
   runtime namespace registry so examples/ never needs to be on the
   classpath.")

(def ^:private example-ns
  (find-ns 'examples.hotrewrite.init))

(when-not example-ns
  (throw (ex-info "hotrewrite example namespace is not loaded"
                  {:namespace 'examples.hotrewrite.init})))

(defn- example-var [symbol-name]
  (let [resolved (ns-resolve example-ns symbol-name)]
    (when-not resolved
      (throw (ex-info "hotrewrite example var is missing"
                      {:namespace 'examples.hotrewrite.init
                       :symbol symbol-name})))
    resolved))

(def ^:private call-target! (example-var 'call-target!))
(def ^:private rewrite-to-v2! (example-var 'rewrite-to-v2!))
(def ^:private call-counts (example-var 'call-counts))
(def ^:private status (example-var 'status))

(defn- check! [condition message data]
  (when-not condition
    (throw (ex-info message data))))

(defn- -main [& _]
  (let [baseline-counts (call-counts)
        baseline-status (status)]
    (call-target!)
    (let [after-v1-counts (call-counts)]
      (check! (= {:v1 1 :v2 0}
                 (merge-with - after-v1-counts baseline-counts))
              "the first BitSet.size call must reach only bridge v1"
              {:baseline baseline-counts
               :actual after-v1-counts}))
    (rewrite-to-v2!)
    (call-target!)
    (let [final-counts (call-counts)
          final-status (status)
          count-delta (merge-with - final-counts baseline-counts)
          fired-delta (- (long (:fired final-status))
                         (long (:fired baseline-status)))
          exception-delta (- (long (:exceptions final-status))
                             (long (:exceptions baseline-status)))]
      (check! (= {:v1 1 :v2 1} count-delta)
              "the probe must record one v1 call and one v2 call"
              {:expected {:v1 1 :v2 1}
               :actual count-delta})
      (check! (= 2 fired-delta)
              "the two BitSet.size calls must increase fired by exactly two"
              {:expected 2
               :actual fired-delta})
      (check! (pos? (long (:woven-count final-status)))
              "the hotrewrite hook must be woven"
              {:woven-count (:woven-count final-status)})
      (check! (false? (:pending? final-status))
              "the hotrewrite hook must not be pending"
              {:pending? (:pending? final-status)})
      (check! (= 0 exception-delta)
              "the hotrewrite hook must not increase exceptions"
              {:expected 0
               :actual exception-delta})
      (println "nihilite:example-pass hotrewrite")
      (flush))))

(-main)
