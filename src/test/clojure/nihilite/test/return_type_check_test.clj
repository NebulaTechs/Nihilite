(ns nihilite.test.return-type-check-test
  "The return value a :modify or :redefine bridge returns becomes the
   target method's return value: the advice is woven with
   @Advice.Return(typing = DYNAMIC), so the JVM casts whatever came back
   to the target's declared return type. A wrong-shaped value used to
   surface as a bare ClassCastException from inside the woven method,
   naming neither the hook nor the spec.

   Both checks are driven from the target method's DESCRIPTOR, not from
   the previous call's value. That distinction is the point of this
   namespace: a method may return null on one call and a real value on
   the next, so a check that reads the previous value passes or fails
   depending on data rather than on the bridge.

   Targets are real signatures so the descriptor is not the only thing
   under test: java/util/BitSet size()I for the primitive path, and
   valueOf([J)Ljava/util/BitSet; for the reference path."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [nihilite.registry :as reg]
            [nihilite.registry.dispatch :as dispatch]
            [nihilite.test.fixtures :as fx]))

(use-fixtures :each fx/reg-cleanup)

(def ^:private SIZE-DESC "()I")
(def ^:private VALUE-OF-DESC "([J)Ljava/util/BitSet;")

(defn- bitset [] (java.util.BitSet.))
;; valueOf takes a long[], so the descriptor's one parameter is the array
;; itself, not its length.
(defn- long-array [] (into-array Long [1]))

(defn- install [id bridge-fn & {:keys [method descriptor position action]}]
  (reg/install! {:id              id
                 :target-internal "java/util/BitSet"
                 :method-name     (or method "size")
                 :descriptor      (or descriptor SIZE-DESC)
                 :position        (or position :return)
                 :action          (or action :modify)
                 :bridge          bridge-fn}))

(defn- invalid?
  "True when calling f throws with one of the return-value rejections."
  [f]
  (try (f) false
       (catch clojure.lang.ExceptionInfo e
         (contains? #{:nihilite/invalid-modify-value
                      :nihilite/invalid-redefine-value}
                    (:nihilite/kind (ex-data e))))))

(defn- rejected-data
  "The ex-data of the rejection, or nil when f did not throw one."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

;; :modify, reference return type -- the case the old check got wrong
(deftest modify-rejects-a-wrong-shape-even-when-the-previous-value-was-null
  ;; The old check was (instance? (class original) rv). A method that
  ;; returns null on this call makes original nil, and (instance? nil rv)
  ;; is true for every rv -- so the check passed and the woven method
  ;; then threw a bare CCE naming neither hook nor spec.
  (let [id "wrong-shape"]
    (install id (fn [_] "not a bitset") :method "valueOf" :descriptor VALUE-OF-DESC)
    (is (invalid? #(dispatch/dispatch-return-for-spec id nil (long-array) nil))
        "a String is rejected against a BitSet-returning method even when original is nil")))

(deftest modify-rejects-a-wrong-shape-whatever-the-previous-value-was
  ;; These two calls differ only in data, so under the old check one
  ;; passed and one failed. With the declared type they must agree.
  (let [id "shape-vs-data"]
    (install id (fn [_] 42) :method "valueOf" :descriptor VALUE-OF-DESC)
    (is (invalid? #(dispatch/dispatch-return-for-spec id nil (long-array) (bitset)))
        "rejected when the previous value was a real BitSet")
    (is (invalid? #(dispatch/dispatch-return-for-spec id nil (long-array) nil))
        "rejected identically when the previous value was null")))

(deftest modify-accepts-the-declared-type
  (let [id "ok-shape"]
    (install id (fn [_] (bitset)) :method "valueOf" :descriptor VALUE-OF-DESC)
    (is (nil? (rejected-data #(dispatch/dispatch-return-for-spec id nil (long-array) nil)))
        "the declared type itself is accepted")))

(deftest modify-accepts-anything-under-a-wider-declared-type
  ;; String against an Object return is a widening the JVM allows, so the
  ;; check must not be stricter than the cast it stands in for.
  (let [id "object-declared"]
    (install id (fn [_] "a string") :method "toString"
             :descriptor "()Ljava/lang/Object;")
    (is (nil? (rejected-data #(dispatch/dispatch-return-for-spec id nil (object-array 0) "s")))
        "a wider declared type accepts any value")))

;; :modify, primitive return type -- narrowing itself is covered in
;; dispatch-modified-test; these pin the interaction
(deftest modify-narrows-a-primitive-and-there-is-nothing-left-to-check
  (let [id "prim-ok"]
    (install id (fn [_] 7))
    (is (nil? (rejected-data #(dispatch/dispatch-return-for-spec id nil (object-array 0) (int 0))))
        "a Long narrows to the declared int")
    (is (= 7 (dispatch/dispatch-return-for-spec id nil (object-array 0) (int 0))))))

(deftest modify-rejects-a-non-numeric-value-against-a-primitive-return
  (let [id "prim-bad"]
    (install id (fn [_] "nope"))
    (is (invalid? #(dispatch/dispatch-return-for-spec id nil (object-array 0) (int 0)))
        "a String against an int-returning method is rejected")))

(deftest modify-still-treats-a-nil-bridge-return-as-no-modification
  (let [id "nil-rv"]
    (install id (fn [_] nil) :method "valueOf" :descriptor VALUE-OF-DESC)
    (is (nil? (rejected-data #(dispatch/dispatch-return-for-spec id nil (long-array) (bitset))))
        "a nil bridge return means no modification, not a type error")))

;; :redefine -- had no check at all
(deftest redefine-rejects-a-wrong-shape
  ;; :redefine replaced the method body, so no value ever ran before it
  ;; and there was nothing to compare against. The descriptor is the only
  ;; available source, which is why it is the only check there can be.
  (let [id "redef-bad"]
    (install id (fn [_ _ _] "not a bitset") :method "valueOf"
             :descriptor VALUE-OF-DESC :position :redefine :action :observe)
    (is (invalid? #(dispatch/dispatch-redefine "java/util/BitSet" "valueOf"
                                              nil (long-array) VALUE-OF-DESC))
        "a String is rejected from a :redefine bridge")))

(deftest redefine-accepts-the-declared-type
  (let [id "redef-ok"]
    (install id (fn [_ _ _] (bitset)) :method "valueOf"
             :descriptor VALUE-OF-DESC :position :redefine :action :observe)
    (is (nil? (rejected-data #(dispatch/dispatch-redefine "java/util/BitSet" "valueOf"
                                                        nil (long-array) VALUE-OF-DESC)))
        "the declared type itself is accepted")))

(deftest redefine-narrows-a-primitive-the-same-way-modify-does
  (let [id "redef-prim"]
    (install id (fn [_ _ _] 7) :position :redefine :action :observe)
    (is (= 7 (dispatch/dispatch-redefine "java/util/BitSet" "size"
                                        nil (object-array 0) SIZE-DESC))
        "a Long from a bare literal narrows to the declared int")))

(deftest redefine-rejects-a-non-numeric-value-against-a-primitive-return
  (let [id "redef-prim-bad"]
    (install id (fn [_ _ _] "nope") :position :redefine :action :observe)
    (is (invalid? #(dispatch/dispatch-redefine "java/util/BitSet" "size"
                                              nil (object-array 0) SIZE-DESC))
        "a String against an int-returning target is rejected")))

;; the error has to name the hook
(deftest the-modify-rejection-names-the-spec-and-the-descriptor
  (let [id "named"
        _  (install id (fn [_] "nope") :method "valueOf" :descriptor VALUE-OF-DESC)
        d  (rejected-data #(dispatch/dispatch-return-for-spec id nil (long-array) nil))]
    (is (= :nihilite/invalid-modify-value (:nihilite/kind d)))
    (is (= "named" (:nihilite/id d)))
    (is (= VALUE-OF-DESC (:nihilite/descriptor d)))
    (is (class? (:nihilite/returned d)))))

(deftest the-redefine-rejection-names-the-spec-and-the-descriptor
  (let [id "redef-named"
        _  (install id (fn [_ _ _] "nope") :method "valueOf"
                      :descriptor VALUE-OF-DESC :position :redefine :action :observe)
        d  (rejected-data #(dispatch/dispatch-redefine "java/util/BitSet" "valueOf"
                                                      nil (long-array) VALUE-OF-DESC))]
    (is (= :nihilite/invalid-redefine-value (:nihilite/kind d)))
    (is (= "redef-named" (:nihilite/id d)))
    (is (= VALUE-OF-DESC (:nihilite/descriptor d)))))
