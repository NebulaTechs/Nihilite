(ns nihilite.test.driver-observe
  "Driver-observation atoms for the retransform and multi-hook fan-out drivers.

   These lived in `nihilite.builder.registry.stats` until 2026-10-10. Nothing in
   src/main read them, so they were test-only state shipping in the uberjar.

   Both drivers self-attach: `java-command!` starts the driver JVM, the
   driver installs an Instrumentation into itself, and the advice bridges
   that write these atoms run in that same JVM. src/test/clojure is on the
   driver classpath, so moving them here crosses no classloader boundary.")

(def driver-throw-observed (atom 0))
(def driver-body-executed-after-cancel? (atom false))
(def driver-redefine-body-executed?
  "Set by the retransform driver's :redefine target body. A :redefine hook
   wraps the method, so this must stay false — it is what proves the
   original body is genuinely replaced rather than merely having its
   return value overridden."
  (atom false))

(defn increment-throw-observed!
  "Bumps the throw observation counter used by nihilite.test.retransformDriver.
   Reset by clear-driver-state!."
  []
  (swap! driver-throw-observed inc))

(defn clear-driver-state!
  "Resets driver observation counters. Called between driver test phases."
  []
  (reset! driver-throw-observed 0)
  (reset! driver-body-executed-after-cancel? false)
  (reset! driver-redefine-body-executed? false))