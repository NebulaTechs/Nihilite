(ns examples.hotrewrite.init
  "Demonstrate hot rewrite: swap-bridge! replaces a hook's bridge at
   runtime without restarting the JVM.

   TEACHING EXAMPLE — the target will NOT actually fire under Nihilite's
   app-loader-only boundary. `java.lang.String` is a bootstrap-classloader
   class; Nihilite injects its advice into the application classloader,
   which a bootstrap class cannot resolve. So the hook registers but the
   bridge never runs. The point of this example is the `swap-bridge!`
   pattern (rewire a live bridge at runtime), not a working hook. For a
   hook that fires, target an application-classloader class — see
   examples/minecraft or examples/fabric."
  (:require [nihilite.api :as api]))

(def ^:private current-label (atom "v1"))

(api/install!
  {:id              "greet"
   :target-internal "java/lang/String"
   :method-name     "length"
   :descriptor      "()I"
   :position        :entry
   :action          :observe
   :bridge          (fn [_] @current-label)
   :note            "bridge reads current-label; swap-bridge! rewires it"})

(defn rewrite-to-v2!
  []
  (reset! current-label "v2")
  (api/swap-bridge! "greet" (fn [_] @current-label)))

(println "[examples.hotrewrite.init] loaded. Call"
         "(examples.hotrewrite.init/rewrite-to-v2!) to hot-rewrite.")
(flush)