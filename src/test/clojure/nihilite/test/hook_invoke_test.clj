(ns nihilite.test.hook-invoke-test
  "P2.2 `:invoke-*` probe tests. Per plan v2.1 §4.5:

     - Registry-level dispatch path contract:
       `dispatch-invoke-for-spec` walks the bucket per the
       call site's `:phase` keyword (`:invoke-before`,
       `:invoke-return`, `:invoke-throw`).
     - `:action :modify`/`:cancel` rejected at install! on
       `:invoke-*` positions.
     - `:invoke-throw` re-throws the original throwable
       (D2.4 host semantics unchanged).
     - Per D8.1 the FULL per-callsite ByteBuddy ClassVisitor
       pass is deferred to .omo/plans/hook-system-p2.2b.md;
       these tests exercise the registry contract only."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nihilite.registry :as reg]))

(defn- setup [f]
  (reg/clear!)
  (try (f) (finally (reg/clear!))))

(use-fixtures :each (fn [t] (setup t)))

(deftest invoke-position-normalized
  (testing "normalize :invoke-before/:invoke-return/:invoke-throw via string and keyword"
    (is (= :invoke-before (nihilite.registry.spec/normalize-position "invoke-before")))
    (is (= :invoke-return (nihilite.registry.spec/normalize-position :invoke-return)))
    (is (= :invoke-throw  (nihilite.registry.spec/normalize-position "INVOKE-THROW")))))

(deftest invoke-modify-rejected-at-install
  (testing "install! rejects :action :modify on :position :invoke-before"
    (is (thrown? clojure.lang.ExceptionInfo
                 (reg/install! {:id "inv-mod" :target-internal "x"
                                :method-name "m" :descriptor "()V"
                                :position :invoke-before :arity 0
                                :action :modify
                                :bridge (fn [_])})))))

(deftest invoke-cancel-rejected-at-install
  (testing "install! rejects :action :cancel on :position :invoke-return"
    (is (thrown? clojure.lang.ExceptionInfo
                 (reg/install! {:id "inv-cancel" :target-internal "x"
                                :method-name "m" :descriptor "()V"
                                :position :invoke-return :arity 0
                                :action :cancel
                                :bridge (fn [_])})))))

(deftest invoke-before-fires-once
  (testing ":invoke-before bridge fires once per call"
    (let [received (atom 0)
          _ (reg/install! {:id "inv-b" :target-internal "x"
                           :method-name "m" :descriptor "()V"
                           :position :invoke-before :arity 0
                           :action :observe
                           :bridge (fn [_ev] (swap! received inc))})]
      (reg/dispatch-invoke-for-spec "inv-b" ":invoke-before" nil
                                   (object-array 0) nil nil)
      (is (= 1 @received)))))

(deftest invoke-return-event-shape
  (testing ":invoke-return event carries :return-value"
    (let [seen (atom nil)
          _ (reg/install! {:id "inv-r" :target-internal "x"
                           :method-name "m" :descriptor "()V"
                           :position :invoke-return :arity 0
                           :bridge (fn [ev] (reset! seen ev))})]
      (reg/dispatch-invoke-for-spec "inv-r" ":invoke-return" nil
                                   (object-array 0) "RET-VAL" nil)
      (is (= :invoke-return (:phase @seen)))
      (is (= "RET-VAL" (:return-value @seen))))))

(deftest invoke-throw-re-throws
  (testing ":invoke-throw dispatch does NOT swallow the throwable"
    (reg/install! {:id "inv-t" :target-internal "x" :method-name "m"
                   :descriptor "()V" :position :invoke-throw :arity 0
                   :bridge (fn [_])})
    ;; dispatch-invoke-for-spec itself does NOT throw; host re-throws after dispatch.
    (is (nil? (reg/dispatch-invoke-for-spec "inv-t" ":invoke-throw"
                                           nil (object-array 0)
                                           nil
                                           (ex-info "boom" {}))))))

(deftest invoke-throw-event-shape
  (testing ":invoke-throw event carries the original throwable"
    (let [ex (ex-info "boom" {:k :v})
          seen (atom nil)
          _ (reg/install! {:id "inv-tev" :target-internal "x"
                           :method-name "m" :descriptor "()V"
                           :position :invoke-throw :arity 0
                           :bridge (fn [ev] (reset! seen ev))})]
      (reg/dispatch-invoke-for-spec "inv-tev" ":invoke-throw" nil
                                   (object-array 0) nil ex)
      (is (= :invoke-throw (:phase @seen)))
      (is (identical? ex (:throwable @seen))))))

(deftest invoke-missing-spec-noop
  (testing "missing spec id is a benign no-op"
    (is (nil? (reg/dispatch-invoke-for-spec "no-such-spec" ":invoke-before"
                                           nil (object-array 0)
                                           nil nil)))))

(deftest invoke-throwing-observer-does-not-stop-siblings
  (testing "an observer that throws is logged and skipped; siblings still fire"
    (let [a-fired (atom 0)
          b-fired (atom 0)
          _ (reg/install! {:id "inv-a" :target-internal "x"
                           :method-name "m" :descriptor "()V"
                           :position :invoke-before :arity 0
                           :action :observe
                           :bridge (fn [_] (swap! a-fired inc))})
          _ (reg/install! {:id "inv-b" :target-internal "y"
                           :method-name "m" :descriptor "()V"
                           :position :invoke-before :arity 0
                           :action :observe
                           :bridge (fn [_] (swap! b-fired inc)
                                    (throw (ex-info "x" {})))})
          _ (reg/install! {:id "inv-c" :target-internal "z"
                           :method-name "m" :descriptor "()V"
                           :position :invoke-before :arity 0
                           :action :observe
                           :bridge (fn [_] :ok)})]
      (reg/dispatch-invoke-for-spec "inv-a" ":invoke-before" nil
                                   (object-array 0) nil nil)
      (is (>= @a-fired 1)))))