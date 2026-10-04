(ns nihilite.test.dispatch-return-cancel-test
  "dispatch-return-for-spec's own decision branches, at :return.

   This walk is NOT walk-bucket: it carries a `decided?` flag and a `result`
   atom, because a :modify bridge's return value replaces the target's. So the
   branches here need their own coverage, and it has to be arranged so they
   can be reached at all.

   Two arrangement rules, both learned the hard way:

   Every spec below shares `:target-internal \"x\"`. A bucket is keyed by
   target+method+descriptor (registry/method-key), so specs on DIFFERENT
   targets sit in DIFFERENT buckets: a \"cancel\" spec on target \"y\" and a
   \"modify\" on target \"x\" never meet, and the walk only ever sees one spec.
   This namespace previously used x/y/z and every test silently degenerated
   into \"one :modify bridge's value is the result\" -- the cancel bridge was
   never called. Same-target is what makes a bucket walk mean anything.

   `:cancel` is only legal at `:position :entry` (registry/install! throws
   :nihilite/cancel-requires-entry), so there is no return-position cancel to
   short-circuit here. `:subscriber` IS legal at :return and is the branch
   that has to be pinned -- it decides, without contributing a value."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.registry.dispatch :as dispatch]
            [nihilite.test.fixtures :as fx]))

(use-fixtures :each fx/reg-cleanup)

(defn- ret
  "A :return spec on the SHARED target. :observe and :subscriber contribute no
   replacement value; :modify returns one."
  [id action]
  {:id id
   :target-internal "x"
   :method-name "m"
   :descriptor "()V"
   :position :return
   :arity 0
   :action action
   :bridge (case action
             :observe (fn [_] nil)
             :subscriber (fn [ev] ((:cancel! ev) true))
             (fn [_] (str "MUT-" (clojure.string/upper-case id))))})

(defn- return-value
  [id]
  (dispatch/dispatch-return-for-spec id nil (object-array 0) "ORIG"))

(deftest a-subscriber-decides-without-contributing-a-value
  ;; subscriber sits between an observer and a :modify. It sets the
  ;; cancellation, which sets decided?, so the :modify never runs and the
  ;; target's own return value stands.
  (reg/install! (ret "sub-first" :observe))
  (reg/install! (ret "sub-stop" :subscriber))
  (reg/install! (ret "sub-never" :modify))
  (is (= "ORIG" (return-value "sub-first"))
      "the :modify after the subscriber did not get its value in")
  (is (zero? (:fired (reg/install-status! "sub-never")))
      "and that spec's own counter stayed at zero, so this is observable
       rather than a coincidence of return values"))

(deftest the-first-modify-decides-and-later-ones-do-not-run
  (reg/install! (ret "mod-first" :observe))
  (reg/install! (ret "mod-a" :modify))
  (reg/install! (ret "mod-b" :modify))
  (is (= "MUT-MOD-A" (return-value "mod-first"))
      "the first :modify that returns a value wins")
  (is (zero? (:fired (reg/install-status! "mod-b")))
      "the later :modify never ran"))

(deftest an-observer-only-bucket-leaves-the-return-value-alone
  (reg/install! (ret "only-obs-a" :observe))
  (reg/install! (ret "only-obs-b" :observe))
  (is (= "ORIG" (return-value "only-obs-a"))
      "no :modify means no decision, so the target's value is returned")
  (is (= 1 (:fired (reg/install-status! "only-obs-b")))
      "and every spec in the bucket still ran"))

(deftest a-modify-before-a-subscriber-still-wins
  ;; The order matters and this is the asymmetry worth pinning: decided? is
  ;; set by the first spec that can set it, whichever kind that is.
  (reg/install! (ret "order-mod" :modify))
  (reg/install! (ret "order-sub" :subscriber))
  (is (= "MUT-ORDER-MOD" (return-value "order-mod"))
      "the :modify decided first, so the subscriber's cancellation came too
       late to matter for the return value"))

(deftest cancel-is-not-a-legal-return-action
  ;; Pins WHY there is no return-position cancel branch to test.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #":action :cancel requires :position :entry"
                        (reg/install! (assoc (ret "illegal" :observe)
                                             :action :cancel)))
      "so dispatch-return-for-spec's :cancel branch is unreachable, and
       :subscriber above is the branch that actually decides"))

(deftest missing-spec-returns-original
  (is (= "ORIG" (dispatch/dispatch-return-for-spec "no-such-spec"
                                                  nil (object-array 0)
                                                  "ORIG"))
      "an id that is not registered has no bucket to walk"))
