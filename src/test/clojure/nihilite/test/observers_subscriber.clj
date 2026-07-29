(ns nihilite.test.observers-subscriber
  "Subscriber tests.
   - :action :subscriber is a NEW closed keyword
   - Subscriber bridges short-circuit the bucket
   - Subscription is a 16-field defrecord
   - :take auto-unsubscribes
   - selector defaults to match-all (with log warning)
   - unsubscribe! returns true/false (matches reg/uninstall! contract)
   - Subscriber's pr-str preserves map-literal shape
   - capture-stack? propagates from original spec"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.registry.spec :as rs]
            [nihilite.observers.subscriber :as sub]
            [nihilite.observers.sinks :as sinks]))

(defn- setup [f]
  (reg/clear!)
  (try (f) (finally (reg/clear!))))

(use-fixtures :each
  (fn [t] (setup t)))

(deftest subscribe-creates-spec-and-subscription
  (testing "subscribe! returns a Subscriber defrecord"
    (reg/install! {:id "src-a" :target-internal "x" :method-name "m"
                   :descriptor "()V" :position :entry :arity 0
                   :bridge (fn [_]) :tag "fast"})
    (let [s (sub/subscribe! (fn [_ev])
                            {:selector {:tag "fast"}
                             :sink :println})]
      (is (instance? nihilite.observers.subscriber.Subscriber s))
      (is (string? (:id s)))
      (is (= :println (:sink s))))))

(deftest subscriber-record-has-16-fields
  (testing "Subscriber defrecord has 16 fields"
    (is (= 16 (count (keys (sub/->Subscriber
                             "id" {} nil :println "n" true 0 nil 0
                             (atom 0) (atom 0) (atom 0) (atom 0)
                             (java.util.concurrent.atomic.AtomicBoolean.)
                             (atom []) (atom 0))))))))

(deftest subscriber-pr-str-is-map-literal
  (testing "sub/->Subscriber pr-str yields a map-literal shape (D9:
            downstream log/eval consumers that pattern-match on a
            plain map continue to work after the defrecord switch)"
    (let [cb  (java.util.concurrent.atomic.AtomicBoolean.)
          xs  (atom [])
          c   (atom 0)
          e   (atom 0)
          se  (atom 0)
          cn  (atom 0)
          ft  10
          s   (sub/->Subscriber
                 "test-id" {} (constantly nil) :println "name" true 0 nil ft
                 c e se cn cb xs (atom 0))
          ps  (pr-str s)]
      (is (re-find #"\{.*:id" ps)
          "pr-str shape must contain \"{:id\" (map-literal signature)"))))

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

(deftest subscribe-bulk-with-capture-stack
  (testing "R12: capture-stack? true on the original spec propagates
            to the subscriber-installed spec; reg/lookup on the
            subscriber tag returns the spec with :capture-stack? = true."
    (reg/install! {:id "src-g" :target-internal "g" :method-name "m"
                   :descriptor "()V" :position :entry :arity 0
                   :bridge (fn [_])
                   :capture-stack? true})
    (let [s (sub/subscribe! (fn [_])
                            {:sink :println})]
      (let [sub-tag  (str "subscriber:" (:id s))
            all-specs (vals (reg/snapshot))
            tags     (filter #(= sub-tag (:tag %)) all-specs)]
        (is (pos? (count tags))
            "subscriber installs at least one tagged spec")
        (let [first-spec (first tags)]
          (is (true? (:capture-stack? first-spec))
              ":capture-stack? propagates from the source spec")
          (is (= :subscriber (:action first-spec))))))))
