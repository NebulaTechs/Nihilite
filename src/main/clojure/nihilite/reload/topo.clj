(ns nihilite.reload.topo
  "Topological sort over discovered modules. Iterative
   three-color DFS (white/gray/black); deterministic across runs
   because we iterate module names in sorted order. Missing deps
   are warned but skipped. Cycles fail BEFORE any reload with a
   structured `:cycle-path` carrying the participating modules.

   Key invariant: deps strings are converted with `module-ns`
   (underscore → hyphen) before comparing with sorted-names keys,
   else no edges are followed. Cycle signal propagates via the
   `cycled?` atom — returning :cycle through doseq gets swallowed
   (doseq returns nil)."
  (:require [nihilite.reload.discover :as discover]))

(defn topo-sort
  "Topological sort over `modules` — a vector of [ns-symbol header-map file]
   triples from `nihilite.reload.discover/discover-modules`."
  [modules]
  (let [by-name (into {} (map (fn [[n h _]] [n h]) modules))
        sorted-names (vec (sort (keys by-name)))
        color (atom (zipmap sorted-names (repeat :white)))
        cycle-path (atom [])
        cycled? (atom false)
        order (atom [])
        visit
        (fn visit [n]
          (let [c (@color n)]
            (cond
              (= c :black) nil
              (= c :gray)
              (do (swap! cycle-path conj n)
                  (reset! cycled? true))
              :else
              (do (swap! color assoc n :gray)
                  (let [deps (mapv discover/module-ns (get-in by-name [n :requires]))]
                    (doseq [d sorted-names
                            :when (contains? (set deps) d)]
                      (visit d)))
                  (swap! color assoc n :black)
                  (swap! order conj n)))))]
    (doseq [n sorted-names]
      (when (= (@color n) :white)
        (visit n)))
    (when @cycled?
      (throw (ex-info "module cycle detected"
                      {:nihilite/kind :nihilite/cycle
                       :cycle-path @cycle-path})))
    @order))
