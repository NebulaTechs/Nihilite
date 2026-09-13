(ns nihilite.kernel.classgen
  (:require [clojure.java.io :as io]))

(defn generate-class-bytes!
  "Invokes clojure.core$generate_class and writes the resulting .class
   to *compile-path*. Returns the generated class name. No-op outside a
   compile because writeClassFile only writes when *compile-files* is
   set."
  [options]
  (let [generate-class (Class/forName "clojure.core$generate_class")
        invoke-static (.getDeclaredMethod generate-class "invokeStatic"
                                         (into-array Class [Object]))]
    (.setAccessible invoke-static true)
    (let [[cname bytecode] (.invoke invoke-static nil (object-array [options]))]
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