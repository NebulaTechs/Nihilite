(ns nihilite.reload.api
  "Public surface for the reload primitive. `discover-ordered` + `re-init!`."
  (:require [clojure.tools.logging :as log]
            [nihilite.reload.header :as header]
            [nihilite.reload.discover :as discover]
            [nihilite.reload.topo :as topo]
            [nihilite.reload.init :as init]))

(defn discover-ordered
  "Walk modules-dir and return ordered seq of ns symbols (topo-sorted). Diagnostic."
  ([] (discover-ordered "modules"))
  ([modules-dir]
   (->> (discover/discover-modules modules-dir)
        topo/topo-sort
        vec)))

(defn re-init!
  "Reload every module ns in topo order, then run init file. Options: :modules-dir, :init-file."
  ([] (re-init! nil))
  ([opts]
   (let [{:keys [modules-dir init-file]
          :or {modules-dir "modules"
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
