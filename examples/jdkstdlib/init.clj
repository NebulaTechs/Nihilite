(ns examples.jdkstdlib.init
  "Teaching example: how to fill out a hook spec against a JDK stdlib
   method.

   IMPORTANT — this target will NOT actually fire under Nihilite's
   app-loader-only boundary. `java.io.FileInputStream` is loaded by the
   bootstrap classloader, while Nihilite injects its advice code into the
   application classloader; a bootstrap class cannot resolve an
   app-loader symbol, so the hook registers and reports a woven-count but
   never fires. This example exists to show the spec shape (descriptor,
   position, action, ctx-return), not to demonstrate a working hook. For
   a hook that fires, target an application-classloader class — see
   examples/minecraft or examples/fabric."
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