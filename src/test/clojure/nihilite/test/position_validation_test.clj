(ns nihilite.test.position-validation-test
  "Which positions a spec may name, and what happens to one that names
   another.

   The registry validates the SHAPE of a spec: the descriptor is a
   well-formed JVM method descriptor, the arity agrees with it, the action
   is legal, the position is legal. Every one of those rejections exists
   because the alternative is a spec that registers successfully, reports a
   woven count, and never fires -- the 'registers but never fires' mode
   docs/hook-limits.md documents.

   The position was the one shape that was NOT checked. A spec naming
   :bogus, or a string that was not one of the four spellings, was
   registered, reported :registered? true, and could never fire: the
   transformer partitions the specs into exactly four position buckets
   (nihilite.trainer.bucket/collect-buckets), so a fifth position has no
   bucket to land in.

   And a string that did not match fell back to :entry, which is worse
   than rejecting it: a :return hook written as \"retrun\" became an
   :entry hook, fired at the wrong moment, and said nothing."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nihilite.builder.registry :as reg]
            [nihilite.builder.registry.dispatch :as dispatch]
            [nihilite.test.fixtures :as fx]))

(defn- spec [id position]
  {:id id
   :target-internal "java/lang/String"
   :method-name "length"
   :descriptor "()I"
   :position position
   :action :observe
   :bridge (fn [_] nil)})

(use-fixtures :each fx/reg-cleanup-with-dispatcher)

(deftest an-unknown-position-keyword-is-rejected
  (let [e (try (reg/install! (spec "pos-bogus" :bogus)) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e) "an unknown position must be rejected")
    (is (= :nihilite/invalid-position (:nihilite/kind (ex-data e))))
    (is (nil? (reg/lookup "pos-bogus"))
        "a rejected spec must not be registered")))

(deftest an-unknown-position-string-is-rejected
  (testing "a typo is not silently downgraded to :entry"
    (let [e (try (reg/install! (spec "pos-typo" "retrun")) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) "a position that names no position must be rejected")
      (is (= :nihilite/invalid-position (:nihilite/kind (ex-data e))))
      (is (nil? (reg/lookup "pos-typo"))))))

(deftest a-legacy-position-is-rejected
  (let [e (try (reg/install! (spec "pos-legacy" :invoke-before)) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (= :nihilite/invalid-position (:nihilite/kind (ex-data e))))
    (is (nil? (reg/lookup "pos-legacy")))))

(deftest the-four-legal-positions-are-accepted
  (doseq [p [:entry :return :throw :redefine]]
    (let [id (str "pos-" (name p))]
      (is (true? (reg/install! (spec id p))) (str p " must be accepted"))
      (is (= p (:position (reg/lookup id)))))))

(deftest a-position-string-is-accepted-and-normalised
  (doseq [[s p] [["ENTRY" :entry] ["return" :return] ["Throw" :throw]
                  ["redefine" :redefine]]]
    (let [id (str "pos-str-" (name p))]
      (is (true? (reg/install! (spec id s))) (str s " must be accepted"))
      (is (= p (:position (reg/lookup id)))
          "the stored position is the keyword, not the string"))))

(deftest an-absent-position-defaults-to-entry
  (let [id "pos-absent"]
    (is (true? (reg/install! (dissoc (spec id :entry) :position))))
    (is (= :entry (:position (reg/lookup id))))))

(deftest normalize-position-answers-nil-for-what-it-cannot-read
  (is (nil? (reg/normalize-position :bogus)))
  (is (nil? (reg/normalize-position "retrun")))
  (is (nil? (reg/normalize-position 42)))
  (is (= :entry (reg/normalize-position :entry)))
  (is (= :entry (reg/normalize-position "ENTRY"))))

(deftest a-lookup-with-an-unreadable-position-matches-nothing
  ;; The registry answers nil for a position it cannot read. The dispatcher
  ;; has to treat that as \"matches nothing\" rather than \"matches any\":
  ;; matching any would hand back a :redefine spec for an :entry call, and
  ;; the :redefine bridge takes (self args method-name) while every other
  ;; position's bridge takes a single ctx -- an ArityException from inside
  ;; the advice.
  (reg/install! (spec "pos-lookup-entry" :entry))
  (reg/install! (spec "pos-lookup-redefine" :redefine))
  (is (nil? (dispatch/lookup-spec-for-call "java/lang/String" "length" 0
                                           "()I" :bogus))
      "an unreadable position must not match a spec")
  (is (= "pos-lookup-entry"
         (dispatch/lookup-spec-for-call "java/lang/String" "length" 0
                                        "()I" :entry)))
  (is (= "pos-lookup-redefine"
         (dispatch/lookup-spec-for-call "java/lang/String" "length" 0
                                        "()I" :redefine)))
  (is (some? (dispatch/lookup-spec-for-call "java/lang/String" "length" 0 nil))
      "no position at all still matches the first spec, as it always has"))
