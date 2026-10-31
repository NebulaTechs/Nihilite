(ns nihilite.builder.target
  "Answers one question about a hook spec: does the target method exist?

   A spec names a class, a method and a descriptor, and the registry
   validates their SHAPE -- the descriptor is a well-formed JVM method
   descriptor, the arity agrees with it, the action and the position are
   legal. Shape is not existence. A spec naming a method that does not
   exist registers successfully, reports a woven count, and never fires,
   which is the failure mode docs/hook-limits.md calls \"registers but
   never fires\" -- reached here by a route the reader would not expect,
   because everything about the spec looked right.

   This namespace closes that route at install time, when the caller can
   still see the error. It answers the question the JVM can answer
   WITHOUT loading anything.

   THE CONSTRAINT THAT SHAPES EVERYTHING HERE. Resolving a class name
   means loading the class, and loading the class is not free of
   consequences for a hook: a hook installed to observe a class-loading
   path re-enters its own advice when the target class is loaded by the
   verification, and the static initialiser of the target runs at a moment
   the caller did not choose. So this never resolves a name.

   It reads the class list the Instrumentation already holds
   (getAllLoadedClasses), which contains only classes that are already
   loaded, and looks there. A class that is not loaded is NOT an error: the
   target may be loaded later, and a hook for a class that has not arrived
   yet is a legitimate thing to install. The answer in that case is
   :not-loaded, and the caller says so rather than guessing.

   WHAT IS CHECKED, WHEN THE CLASS IS LOADED.

     - the method does not exist under that name, anywhere in the
       inheritance chain -> :nihilite/target-method-not-found
     - the method exists but its descriptor is a different one -> the
       spec would match nothing, because both ByteBuddy and this
       registry's method key decide a match on the descriptor ->
       :nihilite/target-descriptor-mismatch
     - the method exists with that descriptor -> :ok

   The inheritance chain matters. ByteBuddy's ElementMatchers/named
   matches an inherited method as readily as a declared one, so a spec
   naming an inherited method is a working spec and must not be rejected.
   The chain is walked the same way a virtual call resolves it.

   Constructors are excluded on purpose. The transformer matches methods
   only -- nihilite.trainer.bucket/matcher-for excludes constructors and
   type initialisers -- so a spec naming one could never fire."
  (:require [clojure.string :as str])
  (:import [java.lang.instrument Instrumentation]
           [java.lang.reflect Modifier Method]))

(def ^:private primitive-codes
  "The JVM's primitive type codes, by the primitive's own name. A
   reflective method spells a primitive parameter as \"int\"; a descriptor
   spells it as \"I\". Comparing the two means translating the reflective
   form into the descriptor's vocabulary, and the descriptor is the
   authority because that is what the registry keys on and what ByteBuddy
   matches on."
  {"boolean" "Z" "byte" "B" "char" "C" "short" "S"
   "int" "I" "long" "J" "float" "F" "double" "D"
   "void" "V"})

(defn- spell
  "The descriptor spelling of a type: a primitive code, an object type as
   `Lbinary/name;`, or an array as the element type's spelling prefixed
   with one `[` per dimension.

   An array is not an object type. `byte[]` is `[B` in a descriptor, not
   `L[B;` -- the latter is the reflective name of the array CLASS, which is
   a different thing and would make every array parameter look like a
   mismatch."
  [^Class c]
  (if (.isArray c)
    (let [component (.getComponentType c)]
      (str "[" (spell component)))
    (if (.isPrimitive c)
      (primitive-codes (.getName c))
      (str "L" (.replace (.getName c) "." "/") ";"))))

(defn- method-descriptor
  "The JVM descriptor of `m`, as a string."
  [^Method m]
  (str "("
       (str/join (map spell (.getParameterTypes m)))
       ")"
       (spell (.getReturnType m))))

(defn- descriptor-matches?
  "Whether `m` has the descriptor `expected`."
  [^Method m ^String expected]
  (= expected (method-descriptor m)))

(defn- loaded-classes
  "The classes the Instrumentation knows about, or an empty list when there
   is no Instrumentation to ask. An empty list means the answer is
   :not-loaded for every target, which is the honest answer for a JVM
   that was never armed."
  [^Instrumentation inst]
  (if (nil? inst)
    []
    (try
      (vec (.getAllLoadedClasses inst))
      (catch Throwable _ []))))

(defn- find-loaded-class
  "The loaded class whose binary name is `binary-name`, or nil. The class
   list holds binary names (\"java.util.BitSet\"), and a spec carries the
   internal form (\"java/util/BitSet\"), so the comparison is on the
   dotted form both sides agree on."
  [^Instrumentation inst ^String binary-name]
  (first (filter (fn [^Class c]
                   (and c (= binary-name (.getName c))))
                 (loaded-classes inst))))

(defn- inherited?
  "Whether a method declared on `owner` is inherited by `klass`. A public
   or protected method is. A package-private one is inherited only within
   the same package. A private one never is, and a private method of a
   superclass is not something the transformer can weave into a subclass
   either, so leaving it out matches what ByteBuddy would find."
  [^Class klass ^Class owner ^Method m]
  (let [mods (.getModifiers m)]
    (or (Modifier/isPublic mods)
        (Modifier/isProtected mods)
        (and (= (.getPackageName klass) (.getPackageName owner))
             (not (Modifier/isPrivate mods))
             (not (Modifier/isStatic mods))))))

(defn- candidates
  "Every method `name` that `klass` inherits or declares, in the order a
   virtual call would consider them: the class first, then its
   superclass, and so on.

   Overloads are all returned. A spec names a method by name AND
   descriptor -- ByteBuddy matches on both, and the registry's method key
   is built from both -- so a class with two `loadClass` overloads has
   two candidates and the descriptor is what tells them apart."
  [^Class klass ^String name]
  (loop [c klass
         acc []]
    (if (nil? c)
      acc
      (let [found (filter (fn [^Method m]
                            (= name (.getName m)))
                          (.getDeclaredMethods c))]
        (recur (.getSuperclass c)
               (into acc (filter #(inherited? klass c %)) found))))))

(defn check-target-method
  "Check that the method a spec names really exists, and return what was
   found.

   `inst` is the live Instrumentation, or nil when the agent was never
   armed. `target-internal` is the internal class name the spec carries
   (\"java/util/BitSet\"), `method-name` the method name, and `descriptor`
   the descriptor the spec carries.

   Returns one of:

   ```clojure
   {:outcome :ok
    :class   <the loaded Class>
    :method  <the reflective Method>}

   {:outcome :not-loaded
    :class-name <the binary name that was looked for>}

   {:outcome :method-not-found
    :class-name <the binary name>
    :method-name <the method name>}

   {:outcome :descriptor-mismatch
    :class-name <the binary name>
    :method-name <the method name>
    :expected <the descriptor the spec carries>
    :actual   <the descriptors the same-named methods really have>}
   ```

   :not-loaded is not a failure. The class may be loaded later, and a hook
   for a class that has not arrived yet is a legitimate thing to install.
   The caller decides what to do with the answer; this only reports it."
  [^Instrumentation inst ^String target-internal ^String method-name ^String descriptor]
  (let [binary-name (.replace target-internal "/" ".")]
    (if-let [klass (find-loaded-class inst binary-name)]
      (let [found (candidates klass method-name)]
        (if (empty? found)
          {:outcome :method-not-found
           :class-name binary-name
           :method-name method-name}
          (if-let [m (first (filter (fn [^Method m]
                                       (descriptor-matches? m descriptor))
                                     found))]
            {:outcome :ok :class klass :method m}
            {:outcome :descriptor-mismatch
             :class-name binary-name
             :method-name method-name
             :expected descriptor
             :actual (mapv method-descriptor found)})))
      {:outcome :not-loaded :class-name binary-name})))
