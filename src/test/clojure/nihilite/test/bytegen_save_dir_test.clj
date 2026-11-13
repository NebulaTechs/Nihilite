(ns nihilite.test.bytegen-save-dir-test
  "Where the generated classes are written, and where they are not.

   The agent generates six classes at mount time and injects them into the
   system classloader. Their bytes are kept in memory --
   nihilite.crafter.bytegen/generated-class-bytes -- and the transformer
   reads them from there, so nothing on disk was ever needed.

   define-class! used to save them to `target/classes`, a RELATIVE path,
   which resolved against the working directory of whatever JVM mounted the
   agent. That put six .class files into the host application's directory on
   every mount, and a JVM whose working directory was not writable could not
   mount the agent at all: ByteBuddy's saveIn threw `Could not create
   directory: target/classes/nihilite/trainer` out of premain and the JVM
   aborted with exit 134.

   The driver nihilite.test.javaagent-classpath-driver proves the same thing
   end to end, from a child JVM whose working directory is a fresh temp
   directory. This namespace pins the mechanism it is built on, so the two
   cannot drift."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nihilite.crafter.bytegen :as bg])
  (:import [net.bytebuddy.agent ByteBuddyAgent]))

(def ^:private inst
  "A local Instrumentation, used only to inject the probe classes. It is
   never handed to the registry, so the agent is never armed by these tests
   -- the contract suite's own :woven-count 0 assertions depend on that."
  (delay (ByteBuddyAgent/install)))

(defn- probe-file
  "Where ByteBuddy's saveIn puts the class file for `class-name`.

   The package path, not the dotted name: saveIn writes
   `<dir>/nihilite/test/SaveDirProbe.class`, so a probe that looked for
   `<dir>/nihilite.test.SaveDirProbe.class` would find nothing and pass
   whether or not the file was written -- a test that cannot fail."
  [dir class-name]
  (io/file dir (str (str/replace class-name "." "/") ".class")))

(deftest define-class-writes-nothing-by-default
  (let [class-name "nihilite.test.NoSaveDirProbe"
        file (probe-file "target/classes" class-name)]
    (.delete file)
    (bg/define-class! {:name class-name :methods [] :instrumentation @inst})
    (is (not (.exists file))
        (str "define-class! must not write into the working directory: the "
             "generated class is kept in memory and read from there, so "
             "nothing on disk was ever needed"))))

(deftest define-class-writes-where-it-is-told
  (testing "an explicit :save-dir is honoured"
    ;; The default being an opt-out is not the same as the feature being
    ;; removed: a caller that wants the file says where to put it.
    (let [class-name "nihilite.test.SaveDirProbe"
          dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "nihilite-save-dir-" (System/nanoTime)))
          file (probe-file dir class-name)]
      (try
        (bg/define-class! {:name class-name :methods [] :instrumentation @inst
                            :save-dir dir})
        (is (.exists file)
            "an explicit :save-dir must be used")
        (finally
          (doseq [^java.io.File f (reverse (file-seq dir))]
            (.delete f)))))))
