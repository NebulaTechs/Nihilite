(ns examples.hotrewrite.init
  "Demonstrate hot rewrite: swap-bridge! replaces a hook's bridge at
   runtime without restarting the JVM.
   Loaded via -Dnihilite.init=examples/hotrewrite/init.clj."
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