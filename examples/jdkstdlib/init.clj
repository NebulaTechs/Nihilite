(ns examples.jdkstdlib.init
  "Hook a JDK stdlib class to show nihilite works on any JVM class.
   Loaded via -Dnihilite.init=examples/jdkstdlib/init.clj."
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