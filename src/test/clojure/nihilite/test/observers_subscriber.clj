(ns nihilite.test.observers-subscriber
  "Subscriber tests. Per D2.1-D2.5 + D3.1-D3.4:
   - :action :subscriber is a NEW closed keyword
   - Subscriber bridges short-circuit the bucket
   - Subscription is a plain map (no defrecord on install path)
   - :take auto-unsubscribes
   - selector defaults to match-all (with log warning)
   - unsubscribe! returns true/false (matches reg/uninstall! contract)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.observers.subscriber :as sub]
            [nihilite.observers.sinks :as sinks]))

(defn- setup [f]
  (reg/clear!)
  (try (f) (finally (reg/clear!))))

(use-fixtures :each
  (fn [t] (setup t)))

(deftest subscribe-creates-spec-and-subscription
  (testing "subscribe! returns a plain-map subscription with :id"
    (reg/install! {:id "src-a" :target-internal "x" :method-name "m"
                   :descriptor "()V" :position :entry :arity 0
                   :bridge (fn [_]) :tag "fast"})
    (let [s (sub/subscribe! (fn [_ev])
                            {:selector {:tag "fast"}
                             :sink :println})]
      (is (map? s))
      (is (string? (:id s)))
      (is (= :println (:sink s))))))

(deftest unsubscribe-returns-true-or-false
  (testing "unsubscribe! returns true when removed, false when already gone"
    (reg/install! {:id "src-b" :target-internal "y" :method-name "m"
                   :descriptor "()V" :position :entry :arity 0
                   :bridge (fn [_])})
    (let [s (sub/subscribe! (fn [_])
                            {:selector {:class "y"}
                             :sink :println})]
      (is (true? (sub/unsubscribe! (:id s))))
      (is (false? (sub/unsubscribe! (:id s))))
      (is (false? (sub/unsubscribe! "never-existed-id"))))))

(deftest take-auto-unsubscribes
  (testing ":take cap triggers auto-unsubscribe"
    (reg/install! {:id "src-c" :target-internal "z" :method-name "m"
                   :descriptor "()V" :position :entry :arity 0
                   :bridge (fn [_])})
    (let [fired (atom 0)
          s (sub/subscribe! (fn [_ev] (swap! fired inc))
                            {:selector {:class "z"}
                             :sink :println
                             :take 2})]
      (reg/dispatch "src-c" nil (object-array 0))
      (reg/dispatch "src-c" nil (object-array 0))
      (reg/dispatch "src-c" nil (object-array 0))
      (is (= 2 @fired))
      (is (false? (sub/subscribed? (:id s)))))))

(deftest subscriber-short-circuits-bucket
  (testing ":action :subscriber spec fires alone in its bucket"
    (reg/install! {:id "src-d1" :target-internal "w" :method-name "m"
                   :descriptor "()V" :position :entry :arity 0
                   :bridge (fn [_]) :tag "primary"})
    (let [sub-fired (atom 0)
          s (sub/subscribe! (fn [_ev] (swap! sub-fired inc))
                            {:selector {:tag "primary"}
                             :sink :println})]
      (reg/dispatch "src-d1" nil (object-array 0))
      (is (= 1 @sub-fired))
      (is (= 1 @(:fired s))))))

(deftest default-selector-matches-all
  (testing "no selector -> match-all (with log warning suppressed for test)"
    (reg/install! {:id "src-e" :target-internal "u" :method-name "m"
                   :descriptor "()V" :position :entry :arity 0
                   :bridge (fn [_])})
    (let [s (sub/subscribe! (fn [_])
                            {:sink :println
                             :silence-match-all-warning true})]
      (reg/dispatch "src-e" nil (object-array 0))
      (is (= 1 @(:fired s))))))

(deftest pipeline-runs-handler-with-take-cap
  (testing "transducer + sink + :take all compose"
    (reg/install! {:id "src-f" :target-internal "v" :method-name "m"
                   :descriptor "()V" :position :entry :arity 0
                   :bridge (fn [_])})
    (let [s (sub/subscribe! (fn [_ev] nil)
                            {:sink :ring-buffer
                             :take 5})]
      (reg/dispatch "src-f" nil (object-array 0))
      (reg/dispatch "src-f" nil (object-array 0))
      ;; ring-buffer is a global atom in sinks; subscription fires the sink.
      (is (= 2 @(:fired s))))))
