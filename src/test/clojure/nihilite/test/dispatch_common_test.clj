(ns nihilite.test.dispatch-common-test
  "One method-key, two buckets.

   spec-bucket reads the method bucket, which holds every position for that
   target/method/descriptor, then filters it to the caller's own position. So
   an :entry and a :throw hook on the same method share a method-key but not a
   bucket -- and that filter is load-bearing, not tidiness: :redefine bridges
   take (self args method-name) while every other position's bridge takes a
   single ctx, so a bridge crossing positions would be an ArityException.

   The test this file used to be called position-from-bucket-walks-entry-and-
   throw, which said the opposite. It asserted only that each record carried
   the right :position and that two specs matched the target, so nothing caught
   the wrong claim in its own name."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [nihilite.builder.registry :as reg]
            [nihilite.test.fixtures :as fx]))

(use-fixtures :each fx/reg-cleanup)

(defn- at [id position]
  {:id id
   :target-internal "java/lang/String"
   :method-name "length"
   :descriptor "()I"
   :position position
   :bridge (fn [_])})

(deftest positions-share-a-method-key-but-not-a-bucket
  (reg/install! (at "common-entry" :entry))
  (reg/install! (at "common-throw" :throw))
  (let [e (reg/lookup "common-entry")
        t (reg/lookup "common-throw")]
    (is (= :entry (:position e)))
    (is (= :throw (:position t)))
    (is (= (:method-key e) (:method-key t))
        "same target, method and descriptor, so one method-key")
    (is (= 2 (count (reg/matching "java/lang/String")))
        "and both are reachable through the target index")
    (is (= ["common-entry"] (mapv :id (reg/spec-bucket e))))
    (is (= ["common-throw"] (mapv :id (reg/spec-bucket t)))
        "each bucket holds only its own position, which is what keeps a
         :redefine bridge's 3-arg signature away from the 1-arg callers")))

(deftest a-second-hook-at-the-same-position-joins-the-bucket
  (reg/install! (at "same-a" :entry))
  (reg/install! (at "same-b" :entry))
  (is (= ["same-a" "same-b"] (mapv :id (reg/spec-bucket (reg/lookup "same-a"))))
      "so the fan-out is within one position, not across positions"))

(deftest the-bucket-does-not-leak-between-tests
  ;; This namespace used to call (reg/clear!) at the top of its only test and
  ;; install two specs it never removed, so they stayed in the global registry
  ;; for the rest of the shared-JVM run. The manual clear! also meant the
  ;; isolation came from this file rather than from a fixture, so any namespace
  ;; inserted before it would have inherited whatever this one happened to
  ;; wipe. fx/reg-cleanup does both halves.
  (is (= [] (reg/matching "java/lang/String"))
      "reg-cleanup ran, so nothing survived from the previous test"))
