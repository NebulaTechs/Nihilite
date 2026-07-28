(ns nihilite.reload
  "Facade for the manual environment reset. No file-watch, no
   auto-reload. Call `re-init!` for a clean slate. Each .clj
   file under the modules directory declares its place in the
   dependency graph with header lines:

       ;; nihilite-module: <name>
       ;; nihilite-requires: <space-separated-module-names>

   `re-init!` walks the graph in topological order and
   `(require '[<ns>] :reload)` each namespace."
  (:require [nihilite.reload.header :as h]
            [nihilite.reload.topo :as t]))

(def parse-module-header h/parse-module-header)
(def topo-sort           t/topo-sort)
