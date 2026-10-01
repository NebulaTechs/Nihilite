(ns examples.jdkstdlib.init
  "Teaching example: how to fill out a hook spec against a JDK stdlib
   method.

   `java.io.FileInputStream` is loaded by the bootstrap classloader, which
   is the harder case: the advice is reached through an invokedynamic call
   site rather than a direct call, so the classloader boundary does not
   matter. See examples/minecraft and examples/fabric for app-classloader
   targets."
  (:require [nihilite.api :as api]
            [nihilite.registry :as reg]))

(def ^:private bytes-read (atom 0))

(api/install!
  {:id              "fis-read"
   :target-internal "java/io/FileInputStream"
   :method-name     "read"
   :descriptor      "([BII)I"
   :position        :return
   :action          :observe
   :bridge          (fn [ctx]
                      (when-let [n (reg/ctx-return ctx)]
                        (when (pos? (long n))
                          (swap! bytes-read + (long n)))))
   :note            "count bytes read from any FileInputStream"})

(defn total-bytes-read
  []
  @bytes-read)

(println "[examples.jdkstdlib.init] loaded. Read a file, then check"
         "(examples.jdkstdlib.init/total-bytes-read).")
(flush)