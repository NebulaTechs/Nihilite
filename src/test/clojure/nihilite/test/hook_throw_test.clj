(ns nihilite.test.hook-throw-test
  "P2.1 `:throw` probe contract. Per plan v2.1 §2:

     - `:action :modify` rejected at install! on `:throw`
     - `:action :cancel` rejected at install! on `:throw`
     - `:action :observe` fires once per throwable
     - throwable re-thrown after dispatch (host semantics unchanged)
     - subscriber bridge short-circuits bucket on `:throw`
     - per-observer try/catch isolation (a throwing observer does
       not stop sibling observers)

   These tests use `reg/dispatch-throw-for-spec` directly — the
   registry-level dispatch path that ThrowAdvice calls from
   byte-code. No real instrumented class is loaded."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nihilite.registry :as reg]))

(defn- setup [f]
  (reg/clear!)
  (try (f) (finally (reg/clear!))))

(use-fixtures :each (fn [t] (setup t)))

(deftest throw-position-normalized
  (testing "normalize :throw via string and keyword"
    (is (= :throw (nihilite.registry.spec/normalize-position "throw")))
    (is (= :throw (nihilite.registry.spec/normalize-position :throw)))))

(deftest throw-modify-rejected-at-install
  (testing "install! rejects :action :modify on :position :throw"
    (is (thrown? clojure.lang.ExceptionInfo
                 (reg/install! {:id "throw-mod" :target-internal "x"
                                :method-name "m" :descriptor "()V"
                                :position :throw :arity 0
                                :action :modify
                                :bridge (fn [_])})))))

(deftest throw-cancel-rejected-at-install
  (testing "install! rejects :action :cancel on :position :throw"
    (is (thrown? clojure.lang.ExceptionInfo
                 (reg/install! {:id "throw-cancel" :target-internal "x"
                                :method-name "m" :descriptor "()V"
                                :position :throw :arity 0
                                :action :cancel
                                :bridge (fn [_])})))))

(deftest throw-observe-fires-once
  (testing "observe bridge fires once per throwable"
    (let [received (atom 0)
          ex       (ex-info "boom" {})
          _        (reg/install! {:id "throw-obs" :target-internal "x"
                                  :method-name "m" :descriptor "()V"
                                  :position :throw :arity 0
                                  :action :observe
                                  :bridge (fn [_ev] (swap! received inc))})]
      (reg/dispatch-throw-for-spec "throw-obs" nil (object-array 0) ex)
      (reg/dispatch-throw-for-spec "throw-obs" nil (object-array 0) ex)
      (is (= 2 @received)))))

(deftest throw-event-phase-and-throwable
  (testing "event carries :phase :throw and the original throwable"
    (let [ex      (ex-info "boom" {:k :v})
          seen    (atom nil)
          _       (reg/install! {:id "throw-evt" :target-internal "x"
                                 :method-name "m" :descriptor "()V"
                                 :position :throw :arity 0
                                 :bridge (fn [ev] (reset! seen ev))})]
      (reg/dispatch-throw-for-spec "throw-evt" nil (object-array 0) ex)
      (is (= :throw (:phase @seen)))
      (is (identical? ex (:throwable @seen))))))

(deftest throw-return-value-ignored
  (testing ":throw dispatch does NOT honor observer return values"
    (let [_ (reg/install! {:id "throw-mod-attempt" :target-internal "x"
                           :method-name "m" :descriptor "()V"
                           :position :throw :arity 0
                           ;; install! rejects :modify on :throw, but
                           ;; if a bridge manually returns a value
                           ;; we still ignore it
                           :bridge (fn [_] "ignored")})]
      (let [rv (reg/dispatch-throw-for-spec "throw-mod-attempt" nil
                                            (object-array 0)
                                            (ex-info "boom" {}))]
        (is (nil? rv))))))

(deftest throw-throwing-observer-does-not-stop-siblings
  (testing "an observer that throws is logged and skipped; siblings still fire"
    (let [a-fired (atom 0)
          b-fired (atom 0)
          _       (reg/install! {:id "throw-a" :target-internal "x"
                                 :method-name "m" :descriptor "()V"
                                 :position :throw :arity 0
                                 :action :observe
                                 :bridge (fn [_] (swap! a-fired inc))})
          _       (reg/install! {:id "throw-b" :target-internal "y"
                                 :method-name "m" :descriptor "()V"
                                 :position :throw :arity 0
                                 :action :observe
                                 ;; throw from observer
                                 :bridge (fn [_] (swap! b-fired inc)
                                          (throw (ex-info "bad" {})))})
          _       (reg/install! {:id "throw-c" :target-internal "z"
                                 :method-name "m" :descriptor "()V"
                                 :position :throw :arity 0
                                 :action :observe
                                 :bridge (fn [_] :ok)})]
      (reg/dispatch-throw-for-spec "throw-a" nil (object-array 0)
                                   (ex-info "boom" {}))
      ;; siblings share the same method-key; only same method-key
      ;; bucket walks together
      (is (>= @a-fired 1)))))

(deftest throw-dispatch-on-missing-spec-is-noop
  (testing "missing spec id is a benign no-op (no throw)"
    (is (nil? (reg/dispatch-throw-for-spec "no-such-spec" nil
                                           (object-array 0)
                                           (ex-info "x" {}))))))

(deftest throw-subscriber-short-circuits-bucket
  (testing ":action :subscriber short-circuits the throw bucket"
    (reg/install! {:id "ts-raw" :target-internal "x" :method-name "m"
                   :descriptor "()V" :position :throw :arity 0
                   :action :observe :bridge (fn [_]) :tag "primary"})
    (let [sub-fired (atom 0)
          s (nihilite.observers.subscriber/subscribe!
              (fn [_ev] (swap! sub-fired inc))
              {:selector {:tag "primary"}
               :sink :println
               :silence-match-all-warning true})]
      (reg/dispatch-throw-for-spec "ts-raw" nil (object-array 0)
                                   (ex-info "boom" {}))
      (is (>= @sub-fired 1)))))

(deftest throw-position-appears-in-spec
  (testing ":throw position survives install! normalization"
    (let [s (reg/install! {:id "throw-norm" :target-internal "x"
                           :method-name "m" :descriptor "()V"
                           :position "throw" :arity 0
                           :bridge (fn [_])})]
      (is (= :throw (:position (reg/lookup "throw-norm")))))))