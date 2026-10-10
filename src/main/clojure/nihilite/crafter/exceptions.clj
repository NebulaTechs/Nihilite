(ns nihilite.crafter.exceptions
  "Exception types emitted into the agent bytecode.

   NihiliteAdviceException carries the cause of a failed advice dispatch;
   HookCancelledException signals an intentional short-circuit, swallowed
   by the installer before reaching user code. Both inherit RuntimeException
   and are generated via the private generate-class function so their class
   names and inheritance stay stable for instanceof checks and catch sites
   on the JVM side."
  (:require [nihilite.crafter.classgen :as cg]))

(defn gen-all!
  "Generates NihiliteAdviceException and HookCancelledException into
   *compile-path*. Intended to run during AOT compilation of this
   namespace; it is a no-op outside of a compile because writeClassFile
   only writes when *compile-files* is set."
  []
  (cg/generate-class-bytes!
   {:name "nihilite.crafter.NihiliteAdviceException"
    :extends java.lang.RuntimeException
    :constructors {[String Throwable] [String Throwable]}
    :impl-ns "nihilite.crafter.exceptions"})
  (cg/generate-class-bytes!
   {:name "nihilite.crafter.HookCancelledException"
    :extends java.lang.RuntimeException
    :constructors {[] []}
    :impl-ns "nihilite.crafter.exceptions"})
  nil)

(defn advice-ex!
  "Returns an instance of the generated NihiliteAdviceException. The
   spec-id is embedded in the exception message; cause is the underlying
   Throwable."
  [spec-id ^Throwable cause]
  (let [cls    (Class/forName "nihilite.crafter.NihiliteAdviceException")
        ctor   (.getConstructor ^Class cls
                                 (into-array Class [java.lang.String java.lang.Throwable]))]
    (.newInstance ctor (object-array
                        [(if (nil? spec-id)
                           "nihilite advice failed"
                           (str "nihilite advice failed for spec=" spec-id))
                         cause]))))

(defn cancelled!
  "Returns an instance of the generated HookCancelledException."
  []
  (let [cls (Class/forName "nihilite.crafter.HookCancelledException")
        ctor (.getDeclaredConstructor ^Class cls (into-array Class []))]
    (.setAccessible ctor true)
    (.newInstance ctor (object-array []))))

(when *compile-files*
  (gen-all!))
