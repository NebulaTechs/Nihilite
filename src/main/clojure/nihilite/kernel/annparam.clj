(ns nihilite.kernel.annparam
  "ByteBuddy-shared helpers used by advice.clj, dispatcher.clj, and
   transformer.clj. Currently limited to `host-internal`; annotation-
   parameter builders differ between Advice (`net.bytebuddy.asm.Advice$*`)
   and MethodDelegation (`net.bytebuddy.implementation.bind.annotation.*`)
   and are kept local to each caller.")

(defn host-internal
  "JVM internal name of the host class (`/` separators, no leading `L`).
   Returns `\"?\"` for a nil class so generated code never NPEs before
   the worker boots."
  [^java.lang.Class host-class]
  (if (nil? host-class)
    "?"
    (.replace (.getName host-class) "." "/")))
