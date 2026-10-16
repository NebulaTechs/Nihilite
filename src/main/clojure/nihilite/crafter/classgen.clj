(ns nihilite.crafter.classgen
  "Drives clojure.core$generate_class and writes the resulting .class.

   Not a gen-class wrapper: it calls the compiler's own generate-class through
   reflection, because the annotation metadata this project needs on
   generated methods cannot be expressed through the ns macro -- :methods
   parameter vectors reject both array class symbols and the annotation
   values it wants. See generate-class-bytes! for the option that has to
   survive the bypass, without which the forwarder stubs throw at runtime.")

(defn generate-class-bytes!
  "Invokes clojure.core$generate_class with `:load-impl-ns true` and writes
   the resulting .class to *compile-path*. Returns the generated class
   name. No-op outside a compile because writeClassFile only writes when
   *compile-files* is set.

   `:load-impl-ns true` is the critical option that the Clojure compiler
   passes by default: without it, the generated class's static initializer
   never calls `clojure.lang.Util/loadWithClass(impl-cname, ctype)`, and
   the forwarder stubs throw `UnsupportedOperationException` at runtime
   because `prefix-method-name` is never defined. We must mirror the
   compiler's default because we bypass the ns macro that normally injects
   it."
  [options]
  (let [opts (merge {:load-impl-ns true} options)
        generate-class (Class/forName "clojure.core$generate_class")
        invoke-static (.getDeclaredMethod generate-class "invokeStatic"
                                         (into-array Class [Object]))]
    (.setAccessible invoke-static true)
    (let [[cname bytecode] (.invoke invoke-static nil (object-array [opts]))]
      (clojure.lang.Compiler/writeClassFile cname bytecode)
      cname)))
