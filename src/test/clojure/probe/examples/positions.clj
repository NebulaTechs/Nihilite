(ns probe.examples.positions
  "Deterministic verification probe for the positions example.

   The example is loaded by absolute path before this probe, so its namespace
   is resolved from the runtime namespace registry instead of the classpath.")

(def ^:private example-namespace-symbol 'examples.positions.init)

(defn- check! [condition message data]
  (when-not condition
    (throw (ex-info message
                    (assoc data :nihilite/kind :nihilite/example-probe-failed)))))

(defn- example-var [example-ns symbol-name]
  (let [resolved (ns-resolve example-ns symbol-name)]
    (when-not resolved
      (throw (ex-info "the positions example is missing a public function"
                      {:nihilite/kind :nihilite/example-probe-failed
                       :namespace example-namespace-symbol
                       :symbol symbol-name})))
    resolved))

(let [example-ns (or (find-ns example-namespace-symbol)
                      (throw (ex-info "the positions example namespace is not loaded"
                                      {:nihilite/kind :nihilite/example-probe-failed
                                       :namespace example-namespace-symbol})))
      exercise! (example-var example-ns 'exercise!)
      results (example-var example-ns 'results)
      statuses (example-var example-ns 'statuses)
      exercised-results (exercise!)
      result-map (results)
      status-map (statuses)]
  (check! (= exercised-results result-map)
          "the positions example did not expose the exercised results"
          {:expected exercised-results
           :actual result-map})
  (check! (= #{:entry :return :throw :redefine} (set (keys status-map)))
          "the positions example did not expose all four public hook statuses"
          {:expected #{:entry :return :throw :redefine}
           :actual (set (keys status-map))})
  (let [entry-event (get-in result-map [:entry :event])
        entry-result (get-in result-map [:entry :result])]
    (check! (= :entry (:phase entry-event))
            "the entry bridge did not observe an entry event"
            {:event entry-event})
    (check! (true? entry-result)
            "the entry hook changed the original isEmpty result"
            {:expected true
             :actual entry-result}))
  (let [return-event (get-in result-map [:return :event])
        original-return-result (get-in result-map [:return :original-result])
        return-result (get-in result-map [:return :result])]
    (check! (= :return (:phase return-event))
            "the return bridge did not observe a return event"
            {:event return-event})
    (check! (= original-return-result (:return-value return-event))
            "the return bridge did not observe the original size value"
            {:expected original-return-result
             :actual (:return-value return-event)})
    (check! (= original-return-result return-result)
            "the return hook changed the original size result"
            {:expected original-return-result
             :actual return-result}))
  (let [throw-event (get-in result-map [:throw :event])
        observed-throwable (:throwable throw-event)
        caught-throwable (get-in result-map [:throw :result])]
    (check! (= IndexOutOfBoundsException (class observed-throwable))
            "the throw bridge did not observe exactly IndexOutOfBoundsException"
            {:expected IndexOutOfBoundsException
             :actual (class observed-throwable)})
    (check! (= IndexOutOfBoundsException (class caught-throwable))
            "BitSet.get(-1) did not throw exactly IndexOutOfBoundsException"
            {:expected IndexOutOfBoundsException
             :actual (class caught-throwable)}))
  (let [redefine-event (get-in result-map [:redefine :event])
        original-result (get-in result-map [:redefine :original-result])
        redefine-result (get-in result-map [:redefine :result])]
    (check! (= "cardinality" (:method-name redefine-event))
            "the redefine bridge did not receive the target method name"
            {:event redefine-event})
    (check! (= 1 original-result)
            "the original cardinality baseline is not the expected value"
            {:expected 1
             :actual original-result})
    (check! (= 99 redefine-result)
            "the redefine bridge result did not replace the original body"
            {:expected 99
             :actual redefine-result}))
  (doseq [[position status] status-map]
    (check! (true? (:registered? status))
            "a positions example hook is not registered"
            {:position position
             :status status})
    (check! (and (number? (:woven-count status))
                 (pos? (long (:woven-count status))))
            "a positions example hook was not woven"
            {:position position
             :status status})
    (check! (false? (:pending? status))
            "a positions example hook is still pending"
            {:position position
             :status status})
    (check! (and (number? (:fired status))
                 (pos? (long (:fired status))))
            "a positions example hook did not fire"
            {:position position
             :status status})
    (check! (and (number? (:exceptions status))
                 (zero? (long (:exceptions status))))
            "a positions example hook recorded an exception"
            {:position position
             :status status})
    (check! (nil? (:last-error status))
            "a positions example hook has an installation error"
            {:position position
             :status status}))
  ;; The bridge SHAPES are proven by behaviour, not by introspection: the
  ;; entry, return and throw bridges each observed a context with the key their
  ;; position supplies, and the redefine bridge observed the method name and
  ;; returned 99, which could only happen if it was called with
  ;; (self, args, method-name). A Clojure fn carries no :arglists metadata, so
  ;; there is no public way to read an arity off it anyway.
  (println "nihilite:example-pass positions")
  (flush))
