(ns examples.jdkstdlib.init
  "Teaching example: install a public-API hook on the JDK's
   `java.io.FileOutputStream.write([BII)V` method.

   The target is a bootstrap-classloader method, so the advice is reached
   through an invokedynamic call site. The example counts calls and exposes
   the public installation status so it can be verified independently."
  (:require [nihilite.builder.api :as api]))

(def ^:private writes (atom 0))

(Class/forName "java.io.FileOutputStream")

(let [installed? (api/install!
                   {:id              "fos-write"
                    :target-internal "java/io/FileOutputStream"
                    :method-name     "write"
                    :descriptor      "([BII)V"
                    :position        :entry
                    :action          :observe
                    :bridge          (fn [_ctx]
                                       (swap! writes inc))
                    :note            "count write calls on any FileOutputStream"})]
  (when-not (true? installed?)
    (throw (ex-info "the jdkstdlib example requires a new hook installation"
                    {:installed? installed?}))))

(defn total-writes
  "Return the number of observed FileOutputStream.write calls."
  []
  @writes)

(defn install-status
  "Return the public install-side and runtime-side status for the hook."
  []
  (api/install-status! "fos-write"))

(def ^{:doc "Alias for install-status."} status install-status)

(println "[examples.jdkstdlib.init] loaded. Check"
         "(examples.jdkstdlib.init/total-writes) and"
         "(examples.jdkstdlib.init/install-status).")
(flush)
