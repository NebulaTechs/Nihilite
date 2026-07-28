(ns nihilite.test.observers-stack-test
  "P2.4 stack sampling tests. Per plan v2.1 §5:

     - `:capture-stack?` opt-in on `:entry` populates `:stack`
     - `:max-depth` clamped to [1, 256]; default 32
     - `:sample-rate` gate: below sample-rate returns nil
     - `:stack` always nil on `:return` / `:throw` (L6 fix)
     - `:stack` contains no `nihilite.hooks.*` frames (frame skip)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nihilite.registry :as reg]))

(defn- setup [f]
  (reg/clear!)
  (try (f) (finally (reg/clear!))))

(use-fixtures :each (fn [t] (setup t)))

(deftest capture-stack-defaults
  (testing ":capture-stack? defaults to false"
    (reg/install! {:id "sd-1" :target-internal "x"
                   :method-name "m" :descriptor "()V"
                   :position :entry :arity 0
                   :bridge (fn [_])})
    (let [s (reg/lookup "sd-1")]
      (is (false? (:capture-stack? s)))
      (is (= 32 (:max-depth s)))
      (is (= 0.01 (:sample-rate s))))))

(deftest capture-stack-max-depth-clamped
  (testing ":max-depth > 256 rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (reg/install! {:id "sd-2" :target-internal "x"
                                :method-name "m" :descriptor "()V"
                                :position :entry :arity 0
                                :max-depth 1000
                                :bridge (fn [_])}))))
  (testing ":max-depth < 1 rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (reg/install! {:id "sd-3" :target-internal "x"
                                :method-name "m" :descriptor "()V"
                                :position :entry :arity 0
                                :max-depth 0
                                :bridge (fn [_])})))))

(deftest capture-stack-sample-rate-clamped
  (testing ":sample-rate > 1.0 rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (reg/install! {:id "sd-4" :target-internal "x"
                                :method-name "m" :descriptor "()V"
                                :position :entry :arity 0
                                :sample-rate 1.5
                                :bridge (fn [_])}))))
  (testing ":sample-rate < 0.0 rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (reg/install! {:id "sd-5" :target-internal "x"
                                :method-name "m" :descriptor "()V"
                                :position :entry :arity 0
                                :sample-rate -0.1
                                :bridge (fn [_])})))))

(deftest capture-stack-populated-on-entry
  (testing ":capture-stack? true on :entry → :stack populated"
    (let [seen (atom nil)
          _    (reg/install! {:id "sd-6" :target-internal "x"
                              :method-name "m" :descriptor "()V"
                              :position :entry :arity 0
                              :capture-stack? true
                              :sample-rate 1.0
                              :max-depth 16
                              :bridge (fn [ev] (reset! seen ev))})]
      (reg/dispatch "sd-6" nil (object-array 0))
      (is (vector? (:stack @seen)))
      (is (>= (count (:stack @seen)) 1)))))

(deftest capture-stack-shape-is-frame-triples
  (testing "each stack frame is [class-name method-name line-number]"
    (let [seen (atom nil)
          _    (reg/install! {:id "sd-6b" :target-internal "x"
                              :method-name "m" :descriptor "()V"
                              :position :entry :arity 0
                              :capture-stack? true
                              :sample-rate 1.0
                              :max-depth 8
                              :bridge (fn [ev] (reset! seen ev))})]
      (reg/dispatch "sd-6b" nil (object-array 0))
      (let [frames (:stack @seen)
            f0     (first frames)]
        (is (vector? f0))
        (is (= 3 (count f0)))
        (is (string? (nth f0 0)))     ; class-name
        (is (string? (nth f0 1)))     ; method-name
        (is (number? (nth f0 2))))))) ; line-number

(deftest capture-stack-nil-on-sample-rate-miss
  (testing "sample-rate 0 → :stack always nil"
    (let [seen (atom nil)
          _    (reg/install! {:id "sd-7" :target-internal "x"
                              :method-name "m" :descriptor "()V"
                              :position :entry :arity 0
                              :capture-stack? true
                              :sample-rate 0.0
                              :bridge (fn [ev] (reset! seen ev))})]
      (reg/dispatch "sd-7" nil (object-array 0))
      (is (nil? (:stack @seen))))))

(deftest capture-stack-always-nil-on-return
  (testing ":return events always have :stack = nil (L6 fix)"
    (let [seen (atom nil)
          _    (reg/install! {:id "sd-8" :target-internal "x"
                              :method-name "m" :descriptor "()V"
                              :position :return :arity 0
                              :capture-stack? true
                              :sample-rate 1.0
                              :max-depth 16
                              :bridge (fn [ev] (reset! seen ev))})]
      (reg/dispatch-return-for-spec "sd-8" nil (object-array 0) "RET")
      (is (nil? (:stack @seen))))))

(deftest capture-stack-always-nil-on-throw
  (testing ":throw events always have :stack = nil (L6 fix)"
    (let [seen (atom nil)
          _    (reg/install! {:id "sd-9" :target-internal "x"
                              :method-name "m" :descriptor "()V"
                              :position :throw :arity 0
                              :capture-stack? true
                              :sample-rate 1.0
                              :bridge (fn [ev] (reset! seen ev))})]
      (reg/dispatch-throw-for-spec "sd-9" nil (object-array 0)
                                   (ex-info "boom" {}))
      (is (nil? (:stack @seen))))))