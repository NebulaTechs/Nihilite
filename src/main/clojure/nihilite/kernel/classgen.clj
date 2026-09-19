(ns nihilite.kernel.classgen
  (:require [clojure.java.io :as io]))

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

(defn ensure-class-dir!
  "Pre-creates the package directory under *compile-path* so
   writeClassFile's File.mkdir() does not hit an IOException. Must run
   before the first generate-class-bytes! call that targets a
   sub-package."
  [class-name]
  (let [idx (.indexOf ^String class-name (int \.))
        pkg (if (neg? idx) "" (.substring class-name 0 idx))
        dir (io/file *compile-path* pkg)]
    (.mkdirs dir)
    dir))