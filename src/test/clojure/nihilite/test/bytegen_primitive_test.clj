(ns nihilite.test.bytegen-primitive-test
  "The primitive paths of the runtime class generator.

   Every class Nihilite generates today has a reference return type and
   reference parameters: the advice classes return Object, the indy bootstrap
   returns void or CallSite. So the generator's primitive handling -- boxing a
   primitive argument, passing it to the Clojure fn, unboxing the result,
   pushing a primitive local, and returning a primitive -- had no test at all.

   That is where the generator is most fragile. The boxing of an argument is
   emitted as an explicit `Integer.valueOf` call, the unboxing of a result as
   an explicit `intValue` call, and both are written by hand against a table
   of primitive facts. A wrong fact -- a wrong descriptor, a wrong box
   class, a wrong load opcode -- produces bytecode that fails verification at
   class-load time, a failure far from its cause.

   These tests generate a class that exercises every one of the eight
   primitives on both sides, and call it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nihilite.crafter.bytegen :as bg])
  (:import [net.bytebuddy.agent ByteBuddyAgent]))

(def ^:private inst
  "A local Instrumentation, used only to inject the probe class. It is never
   handed to the registry, so the agent is never armed by these tests -- the
   contract suite's own :woven-count 0 assertions depend on that."
  (delay (ByteBuddyAgent/install)))

;; The forwarder hands the target fn BOXED arguments and expects a BOXED
;; result: it boxes on the way in and unboxes on the way out. Each target
;; therefore takes one boxed argument and returns the box type its method
;; declares, which is what the CHECKCAST before the unboxing call requires.
;;
;; The arithmetic is done on primitives and boxed by the return, so the box
;; type the forwarder casts to is the one the method declares.

(defn int-target [x] (int (+ x 1)))
(defn long-target [x] (long (+ x 1)))
(defn double-target [x] (double (+ x 1)))
(defn float-target [x] (float (+ x 1)))
(defn short-target [x] (short (+ x 1)))
(defn byte-target [x] (byte (+ x 1)))
(defn char-target [x] (char (+ (int x) 1)))
(defn boolean-target [x] (not x))

(defn instance-target [_this x] (int (+ x 1)))

(def ^:private primitive-cases
  "One case per primitive: the type's name, its Class, the target var, and
   the value to call it with.

   The value is boxed explicitly, and to the box type the method declares:
   reflection matches the argument against the parameter type, and Clojure's
   own integer literal is a Long, so a plain 41 would be rejected by an int
   parameter."
  [["int"     Integer/TYPE    #'int-target     (int 41)]
   ["long"    Long/TYPE       #'long-target    (long 41)]
   ["double"  Double/TYPE     #'double-target  (double 41)]
   ["float"   Float/TYPE      #'float-target   (float 41)]
   ["short"   Short/TYPE      #'short-target   (short 41)]
   ["byte"    Byte/TYPE       #'byte-target    (byte 41)]
   ["char"    Character/TYPE  #'char-target    \a]
   ["boolean" Boolean/TYPE    #'boolean-target true]])

(defn- forward-var-symbol
  "The fully-qualified symbol the generated code interns at runtime to find
   the target fn.

   Derived from the var itself rather than written out by hand, so renaming
   a target cannot leave a stale symbol behind that only fails when the
   generated class is first called."
  [v]
  (symbol (str (ns-name (:ns (meta v)))) (str (:name (meta v)))))

(def ^:private probe-class-name "nihilite.test.PrimitiveProbe")

(def ^:private probe-class
  (delay
    (bg/define-class!
     {:name probe-class-name
      :instrumentation @inst
      :methods (concat
                (for [[type _ target _] primitive-cases]
                  {:name (str "echo" (str/capitalize type))
                   :static? true
                   :return type
                   :params [type]
                   :forward-var (forward-var-symbol target)})
                ;; An instance method keeps the receiver in local slot 0, so
                ;; the primitive argument lands in slot 1 and the generator has
                ;; to account for the extra slot.
                [{:name "echoIntOnInstance"
                  :return "int"
                  :params ["int"]
                  :forward-var (forward-var-symbol #'instance-target)}])})))

(defn- invoke-static
  "Calls a static method on the probe class by reflection and returns the
   boxed result."
  [^Class cls method-name ^Class param-type arg]
  (let [m (.getMethod cls method-name (into-array Class [param-type]))]
    (.invoke m nil (object-array [arg]))))

(defn- invoke-static-int
  "Calls a static method whose single parameter is an int."
  [^Class cls method-name]
  (invoke-static cls method-name Integer/TYPE (int 41)))

(deftest every-primitive-survives-the-round-trip
  (testing "a primitive argument is boxed in, and a primitive result is
            unboxed out, for every one of the eight primitives"
    (doseq [[type param-type _ arg] primitive-cases]
      (let [method-name (str "echo" (str/capitalize type))
            result (invoke-static @probe-class method-name param-type arg)]
        (is (some? result)
            (str "the " type " method must return a value, not nil"))))))

(deftest a-primitive-argument-arrives-boxed-at-the-target
  (testing "the target receives the boxed argument and returns a boxed
            result, which is what the forwarder's CHECKCAST requires"
    (is (= 42 (invoke-static-int @probe-class "echoInt")))))

(deftest a-primitive-argument-and-result-are-narrowed-to-the-declared-type
  (testing "the method's declared return type decides what comes back, so
            the value is narrowed rather than merely boxed"
    (is (= 42 (invoke-static-int @probe-class "echoInt")))
    (is (= 42 (invoke-static @probe-class "echoLong" Long/TYPE (long 41))))
    (is (= 42.0 (invoke-static @probe-class "echoDouble" Double/TYPE (double 41))))
    (is (= 42.0 (invoke-static @probe-class "echoFloat" Float/TYPE (float 41))))
    (is (= 42 (invoke-static @probe-class "echoShort" Short/TYPE (short 41))))
    (is (= 42 (invoke-static @probe-class "echoByte" Byte/TYPE (byte 41))))
    (is (= \b (invoke-static @probe-class "echoChar" Character/TYPE \a)))
    ;; Value equality, not false?. false? is (identical? x false), and
    ;; reflection does not hand back the Boolean singleton on every JDK: up
    ;; to JDK 17 a primitive boolean return is boxed into a NEW Boolean, so
    ;; identical? says false for a correct result. The VALUE is what the
    ;; generator owes the caller, so that is what is asserted.
    (is (= false (invoke-static @probe-class "echoBoolean" Boolean/TYPE true)))))

(deftest every-primitive-method-declares-a-primitive-return
  ;; The value assertions above cannot tell a narrowed primitive return from
  ;; a boxed one: reflection boxes a primitive boolean into a
  ;; java.lang.Boolean, which is exactly what a method declared to return
  ;; Boolean would have produced. The DESCRIPTOR is what says which one the
  ;; generator emitted, and it is the only version-independent witness.
  ;;
  ;; The probe declares the same type as its parameter and its return, so
  ;; the parameter's Class is the return's Class here.
  (doseq [[type param-type _ _] primitive-cases]
    (let [m (.getMethod @probe-class (str "echo" (str/capitalize type))
                        (into-array Class [param-type]))]
      (is (identical? param-type (.getReturnType m))
          (str "the " type " method must declare a primitive " type
               " return, not the box type")))))

(deftest a-primitive-argument-on-an-instance-method-is-boxed-too
  (testing "the receiver sits in local slot 0, so the primitive argument is
            the second thing loaded -- and it still has to be boxed"
    (let [cls @probe-class
          ^java.lang.reflect.Constructor ctor (.getDeclaredConstructor cls (into-array Class []))
          m (.getMethod cls "echoIntOnInstance" (into-array Class [Integer/TYPE]))
          result (.invoke m (.newInstance ctor (object-array [])) (object-array [(int 41)]))]
      (is (= 42 result)))))
