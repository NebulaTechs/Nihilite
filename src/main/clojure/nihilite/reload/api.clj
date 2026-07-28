(ns nihilite.reload.api
  "Public surface for the reload primitive.
   `discover-ordered` walks modules-dir and returns the topo-sorted
   seq of ns symbols. `re-init!` reloads every module in topo order
   and then runs the init file. Both are used by smoke tests and
   by the operator at the REPL."
  (:require [clojure.tools.logging :as log]
            [nihilite.reload.header :as header]
            [nihilite.reload.discover :as discover]
            [nihilite.reload.topo :as topo]
            [nihilite.reload.init :as init]))

(defn discover-ordered
  "Walk modules-dir and return the ordered seq of ns symbols
   (topo-sorted). Exposed for diagnostics."
  ([] (discover-ordered "examples"))
  ([modules-dir]
   (->> (discover/discover-modules modules-dir)
        topo/topo-sort
        vec)))

(defn re-init!
  "Reload every module namespace in topological order, then run
   the init file. Vars defined in the modules get their file-defined
   root values back, overwriting any in-REPL alter-var-root overrides.

   Options:
     :modules-dir - directory to scan for .clj files (default
                    \"examples\"). Relative paths resolve against
                    the JVM's CWD.
     :init-file   - path to the init file to run after reload
                    (default: -Dnihilite.init if set, else nil —
                    pure REPL when no init file is configured).

   Returns a structured map. Empty-discoveries and absent init
   return `{:re-init-done false :partial false :failed [] :reloadable 0}`
   (no error — pure REPL short-circuit). Cycle-path failures
   return the same shape with an additional `:cycle-path [sym...]`
   key. The successful mapping is the same as `do-reload`'s
   return shape."
  ([] (re-init! nil))
  ([opts]
   (let [{:keys [modules-dir init-file]
          :or {modules-dir "examples"
               init-file (System/getProperty "nihilite.init")}}
         opts
         modules (discover/discover-modules modules-dir)]
      (if (empty? modules)
        (do (log/info "no modules discovered under" modules-dir
                      "(looking for .clj files with"
                      header/header-prefix-module "header)")
            {:re-init-done false :partial false :failed [] :reloadable 0})
       (try
         (init/do-reload (topo/topo-sort modules) init-file)
         (catch clojure.lang.ExceptionInfo e
           (when (= :nihilite/cycle (:nihilite/kind (ex-data e)))
             (let [cyc (:cycle-path (ex-data e))]
               (log/error "ABORTED — cycle detected:" cyc)
               {:re-init-done false :partial false :failed []
                :reloadable  0 :cycle-path cyc}))))))))
