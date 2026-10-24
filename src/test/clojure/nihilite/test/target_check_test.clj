(ns nihilite.test.target-check-test
  "Unit tests for the target-method check.

   The check answers one question: does the method a spec names exist? It
   runs before the spec is registered, so a spec naming a method that is
   not there is rejected instead of registering and never firing.

   These tests pass an Instrumentation to nihilite.builder.target/
   check-target-method directly rather than registering one globally. The
   registry's Instrumentation is process-wide, and registering one here
   would make every other test's :woven-count report a woven class it did
   not weave -- the contract suite's own :woven-count 0 assertions are
   what prove the no-agent path works."
  (:require [clojure.test :refer [deftest is]]
            [nihilite.builder.target :as target])
  (:import [net.bytebuddy.agent ByteBuddyAgent]))

(def ^:private inst
  "A local Instrumentation, used only to ask which classes are loaded. It is
   never handed to the registry, so the agent is never armed by these
   tests."
  (delay (ByteBuddyAgent/install)))

(defn- check
  ([target-internal method-name descriptor]
   (target/check-target-method @inst target-internal method-name descriptor))
  ([target-internal method-name descriptor instrumentation]
   (target/check-target-method instrumentation target-internal method-name descriptor)))

(deftest ok-for-a-method-that-exists
  (is (= :ok (:outcome (check "java/util/BitSet" "size" "()I")))))

(deftest ok-for-a-method-with-a-primitive-return
  (is (= :ok (:outcome (check "java/util/BitSet" "isEmpty" "()Z")))))

(deftest ok-for-a-method-with-a-primitive-parameter
  (is (= :ok (:outcome (check "java/util/BitSet" "get" "(I)Z")))))

(deftest ok-for-a-method-with-an-array-parameter
  ;; An array parameter is `[B` in a descriptor, not the reflective name of
  ;; the array class. Getting that wrong makes every array parameter look
  ;; like a mismatch, which would reject a working spec.
  (is (= :ok (:outcome (check "java/io/FileOutputStream" "write" "([BII)V")))))

(deftest ok-for-an-inherited-method
  ;; ByteBuddy's ElementMatchers/named matches an inherited method as
  ;; readily as a declared one, so a spec naming one is a working spec.
  ;; BitSet extends java.util.AbstractCollection, and Collection declares
  ;; isEmpty, which BitSet also declares -- so use a method BitSet does
  ;; NOT declare but its superclass does.
  (is (= :ok (:outcome (check "java/util/ArrayList" "size" "()I"))))
  (is (= :ok (:outcome (check "java/util/HashSet" "isEmpty" "()Z")))))

(deftest ok-for-one-overload-among-several
  ;; ClassLoader has two loadClass overloads in JDK 9 and later. The
  ;; descriptor is what tells them apart, so the single-argument one must
  ;; be found by its own descriptor and not confused with the two-argument
  ;; one.
  (is (= :ok (:outcome (check "java/lang/ClassLoader"
                              "loadClass"
                              "(Ljava/lang/String;)Ljava/lang/Class;")))))

(deftest method-not-found-when-the-name-is-wrong
  (let [r (check "java/util/BitSet" "noSuchMethod" "()I")]
    (is (= :method-not-found (:outcome r)))
    (is (= "java.util.BitSet" (:class-name r)))
    (is (= "noSuchMethod" (:method-name r)))))

(deftest descriptor-mismatch-when-the-descriptor-is-wrong
  (let [r (check "java/util/BitSet" "size" "()J")]
    (is (= :descriptor-mismatch (:outcome r)))
    (is (= "()J" (:expected r)) "the expected descriptor is the one the spec carried")
    (is (= ["()I"] (:actual r)) "the actual descriptors are the ones the method really has")))

(deftest descriptor-mismatch-not-method-not-found-for-an-overload
  ;; loadClass(String) exists; asking for a descriptor it does not have is
  ;; a descriptor problem, not a missing method. The two answers lead to
  ;; different fixes, so they must not be merged.
  (let [r (check "java/lang/ClassLoader" "loadClass" "()V")]
    (is (= :descriptor-mismatch (:outcome r)))))

(deftest not-loaded-when-the-class-is-absent
  ;; A class that is not loaded is not a failure: the target may load
  ;; later, and a hook for a class that has not arrived yet is a
  ;; legitimate thing to install.
  (let [r (check "no/such/Class/Anywhere" "size" "()I")]
    (is (= :not-loaded (:outcome r)))
    (is (= "no.such.Class.Anywhere" (:class-name r)))))

(deftest not-loaded-without-an-instrumentation
  ;; There is no class list to read without an Instrumentation, so there
  ;; is nothing to check against. The answer is the honest one.
  (let [r (check "java/util/BitSet" "size" "()I" nil)]
    (is (= :not-loaded (:outcome r)))))

(deftest a-constructor-is-not-a-target
  ;; The transformer matches methods only -- matcher-for excludes
  ;; constructors and type initialisers -- so a spec naming one could
  ;; never fire. A constructor is not an inherited method either, so it
  ;; reads as absent.
  (let [r (check "java/util/BitSet" "<init>" "()V")]
    (is (= :method-not-found (:outcome r)))))
