(ns nihilite.test.spec-validation-test
  "Which specs the registry refuses, and what it says when it refuses them.

   install! validates a spec before it registers anything, and each
   rejection carries a :nihilite/kind so a caller can tell WHY a spec was
   refused without matching on the message text. The kinds are a contract:
   api/install! names them, and a caller that branches on one has to be
   able to rely on it.

   Two rules the tests hold the registry to:

   - A rejected spec leaves NO trace. The registry must not carry the
     spec, and must not carry an index entry pointing at it: a spec that
     is half-registered is a hook that reports :registered? false while
     lookup still finds it.

   - Every rejection is an ex-info with a :nihilite/kind. A bare
     NullPointerException or ClassCastException from inside install!
     means the spec was refused by an accident of how the registry
     happens to normalise it, not by the check that was written to
     refuse it -- the caller gets a stack trace instead of a kind, and
     the check the author wrote for that case never ran.

   The last rule is not hypothetical. The registry used to compute
   :internal-class and :arity in the same let that read the spec, so a
   spec with no :target-internal or a non-numeric :arity died on a
   NullPointerException or a ClassCastException before the validation
   branch that was written for it could run. Those two branches were
   dead code: :nihilite/missing-target and :nihilite/bad-arity could
   never be produced from a spec that reached them."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            [nihilite.builder.registry :as reg]
            [nihilite.test.fixtures :as fx]))

(use-fixtures :each fx/reg-cleanup)

(def ^:private base-spec
  {:id "sv"
   :target-internal "java/util/BitSet"
   :method-name "size"
   :descriptor "()I"
   :position :entry
   :action :observe
   :bridge (fn [_] nil)})

(defn- spec
  "A valid spec, with `overrides` applied. The base is a spec the registry
   accepts as-is, so a test that changes one key changes exactly one
   thing."
  [overrides]
  (merge base-spec overrides))

(defn- reject!
  "Install `s` and return the ex-info it was rejected with, or nil when it
   was not rejected at all.

   Returns the throwable rather than rethrowing so that a test can make a
   claim about what kind of failure it was. A throwable that is NOT an
   ExceptionInfo is a failure of the registry's own contract -- the
   caller cannot branch on a kind it never received."
  [s]
  (try
    (reg/install! s)
    nil
    (catch Throwable t t)))

(defn- kind-of
  "The :nihilite/kind of a rejection, or ::not-an-ex-info when the
   rejection was not an ex-info at all."
  [t]
  (if (instance? clojure.lang.ExceptionInfo t)
    (:nihilite/kind (ex-data t))
    ::not-an-ex-info))

(deftest a-spec-missing-a-required-key-is-rejected-with-its-own-kind
  ;; Each required key has its own kind, and the registry says WHICH one
  ;; is missing. A single "invalid spec" kind would force every caller to
  ;; re-parse the message to find out what to fix.
  (testing "each required key has its own kind"
    (are [overrides kind] (= kind (kind-of (reject! (spec overrides))))
      {:id nil}                :nihilite/missing-id
      {:id ""}                 :nihilite/missing-id
      {:target-internal nil}  :nihilite/missing-target
      {:target-internal ""}   :nihilite/missing-target
      {:method-name nil}      :nihilite/missing-method
      {:method-name ""}       :nihilite/missing-method
      {:descriptor nil}       :nihilite/missing-descriptor
      {:descriptor ""}        :nihilite/missing-descriptor)))
(deftest a-rejected-spec-leaves-no-trace
  ;; The registry validates before it indexes, so a refused spec must not
  ;; be findable afterwards. A spec that is in by-id but not in a bucket
  ;; would be a hook that reports :registered? false while lookup still
  ;; returns it -- the half-registered state the atomicity tests are
  ;; about.
  (doseq [[label overrides] [["no id" {:id nil}]
                             ["no target" {:target-internal nil}]
                             ["no method" {:method-name nil}]
                             ["no descriptor" {:descriptor nil}]
                             ["bad arity" {:arity -1}]
                             ["bad tag" {:tag ""}]
                             ["bad action" {:action :bogus}]
                             ["bad position" {:position :bogus}]]]
    (testing label
      (reject! (spec overrides))
      (is (nil? (reg/lookup (:id base-spec)))
          "a refused spec must not be registered")
      (is (empty? (reg/list-ids))
          "a refused spec must not leave an index entry behind"))))

(deftest a-non-numeric-arity-is-rejected-as-a-bad-arity
  ;; :arity is checked with (not (integer? arity)), so a string or a
  ;; keyword is meant to be refused with :nihilite/bad-arity. Computing
  ;; the arity with (int arity) before that check turned the refusal into
  ;; a ClassCastException, and the branch the author wrote for it could
  ;; not run.
  (doseq [bad ["2" "abc" :two]]
    (testing (pr-str bad)
      (let [t (reject! (spec {:arity bad}))]
        (is (instance? clojure.lang.ExceptionInfo t)
            "a non-numeric :arity must be refused with an ex-info")
        (is (= :nihilite/bad-arity (kind-of t)))))))

(deftest a-non-numeric-arity-is-not-silently-coerced
  ;; The other half of the same question: a numeric string is not a
  ;; number here. "2" reads like an arity, and accepting it would make
  ;; the registry's answer depend on whether the caller happened to
  ;; write 2 or "2".
  (let [t (reject! (spec {:arity "2"}))]
    (is (= :nihilite/bad-arity (kind-of t))
        "a numeric string is still not an integer")))

(deftest a-missing-target-is-rejected-as-a-missing-target
  ;; The same shape of bug as the arity one: :internal-class is derived
  ;; from the target by a String.replace, and deriving it before the
  ;; target was known to be there meant a spec with no :target-internal
  ;; died on a NullPointerException instead of reaching the
  ;; :nihilite/missing-target branch written for it.
  (let [t (reject! (spec {:target-internal nil}))]
    (is (instance? clojure.lang.ExceptionInfo t)
        "a missing :target-internal must be refused with an ex-info")
    (is (= :nihilite/missing-target (kind-of t)))))

(deftest a-blank-target-is-rejected-as-a-missing-target
  ;; An empty target is the same absence spelled differently, and it was
  ;; already refused -- but with the same kind as an absent one, so the
  ;; two are told apart the same way.
  (is (= :nihilite/missing-target
         (kind-of (reject! (spec {:target-internal ""}))))))

(deftest a-numeric-target-is-rejected-as-a-bad-target
  ;; A number is not a class name. The registry reads the target as a
  ;; string, so 42 used to become the target "42" and was accepted --
  ;; and then matched nothing, ever, which is the "registers but never
  ;; fires" mode the whole validation layer exists to prevent.
  (is (= :nihilite/bad-target
         (kind-of (reject! (spec {:target-internal 42}))))))

(deftest a-target-that-is-not-a-well-formed-class-name-is-rejected
  ;; The same grammar the descriptor is checked against: a name with a
  ;; dot, or one starting with a digit, can never name a class.
  (doseq [bad ["java.util.String" "42" "java//util/String"]]
    (testing (pr-str bad)
      (is (= :nihilite/bad-target
             (kind-of (reject! (spec {:target-internal bad}))))))))

(deftest a-well-formed-target-is-accepted
  ;; The point of the check above is that a REAL target is not refused.
  ;; Without this, a registry that refused every target would pass the
  ;; rest of this namespace.
  (doseq [target ["java/util/BitSet" "java/lang/String" "x"
                   "nihilite/test/retransform_driver/DummyTarget"
                   "java/util/BitSet$Inner"]]
    (testing target
      (is (true? (reg/install! (spec {:id (str "sv-t-" target) :target-internal target})))
          (str target " must be accepted")))))

(deftest an-unknown-action-is-rejected
  (is (= :nihilite/invalid-action
         (kind-of (reject! (spec {:action :bogus}))))))

(deftest a-blank-tag-is-rejected
  ;; An empty tag groups nothing and reads like a tag, so it is refused
  ;; rather than stored.
  (is (= :nihilite/bad-tag
         (kind-of (reject! (spec {:tag ""}))))))

(deftest modify-and-cancel-are-refused-on-a-throw-position
  ;; :modify and :cancel rewrite the control flow of the target method.
  ;; On :throw there is no return value to rewrite and no way to cancel
  ;; a throw that has already happened.
  (is (= :nihilite/invalid-action-on-throw
         (kind-of (reject! (spec {:position :throw :action :modify})))))

  ;; :cancel is refused on :throw too, but by the more fundamental rule:
  ;; :cancel is only ever legal at :entry, whatever the other position
  ;; is. The registry checks that first, so the kind a caller gets is the
  ;; one that says what to do about the ACTION rather than the one that
  ;; says what to do about the position.
  (is (= :nihilite/cancel-requires-entry
         (kind-of (reject! (spec {:position :throw :action :cancel}))))))

(deftest a-subscriber-is-refused-on-a-redefine-position
  ;; :redefine replaces the method body; a subscriber subscribes to an
  ;; event the advice raises. There is no event on a replaced body, so
  ;; the combination cannot mean anything.
  (is (= :nihilite/subscriber-requires-entry
         (kind-of (reject! (spec {:position :redefine :action :subscriber}))))))

(deftest a-cancel-is-refused-on-every-position-but-entry
  (is (= :nihilite/cancel-requires-entry
         (kind-of (reject! (spec {:position :return :action :cancel})))))

  ;; On :redefine the position-specific rule wins: ":modify|:cancel
  ;; invalid on :position :redefine" names the combination that is
  ;; impossible, where "cancel requires :entry" would name a position
  ;; the caller could still move to and keep the rest of the spec.
  (is (= :nihilite/invalid-action-on-redefine
         (kind-of (reject! (spec {:position :redefine :action :cancel}))))))

(deftest the-four-legal-positions-are-still-accepted
  ;; The point of the rejections above is that a legal spec is NOT
  ;; refused. Without this, a registry that refused everything would
  ;; pass every other test in this namespace.
  (doseq [position [:entry :return :throw]]
    (testing (name position)
      (let [id (str "sv-" (name position))]
        (is (true? (reg/install! (spec {:id id :position position})))
            (str position " must be accepted"))
        (is (= position (:position (reg/lookup id))))))))

(deftest an-absent-arity-is-derived-from-the-descriptor
  ;; The descriptor states the parameter count, so an omitted :arity is
  ;; redundant rather than unknown, and the two agree by construction.
  (let [t (reg/install! (spec {:id "sv-derived" :arity nil}))]
    (is (true? t))
    (is (zero? (:arity (reg/lookup "sv-derived")))
        "()I declares no parameters, so the derived arity is 0")))
