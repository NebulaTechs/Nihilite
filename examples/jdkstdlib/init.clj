(ns examples.jdkstdlib.init
  "Teaching example: how to fill out a hook spec against a JDK stdlib
   method.

   `java.io.FileOutputStream.write` is loaded by the bootstrap classloader,
   which is the harder case: the advice is reached through an invokedynamic
   call site rather than a direct call, so the classloader boundary does not
   matter. See examples/minecraft and examples/fabric for app-classloader
   targets.

   The target is deliberately NOT on the class loading path. This file used to
   hook `java.io.FileInputStream.read`, which is the more obvious demo and is
   the wrong one: the advice body needs classes, class loading reads bytes
   through the very method being hooked, and the cycle closes before the
   reentrancy guard is consulted. Measured behaviour for every target is in
   the README's Limits section -- read it before picking a method."
  (:require [nihilite.builder.api :as api]))

(def ^:private writes (atom 0))

(api/install!
  {:id              "fos-write"
   :target-internal "java/io/FileOutputStream"
   :method-name     "write"
   :descriptor      "([BII)V"
   :position        :entry
   :action          :observe
   :bridge          (fn [ctx]
                      ;; ctx-return is the documented way to read the target
                      ;; method's return value. It returns nil here because
                      ;; write([BII)V is void, so this example counts calls.
                      ;; A non-void target would read (reg/ctx-return ctx).
                      (swap! writes inc))
   :note            "count write calls on any FileOutputStream"})

(defn total-writes
  []
  @writes)

(println "[examples.jdkstdlib.init] loaded. Write to a file, then check"
         "(examples.jdkstdlib.init/total-writes).")
(flush)
