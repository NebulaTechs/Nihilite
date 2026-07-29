(ns nihilite.reload.init
  "Init-file execution and per-module reload with structured
   failure capture. Returns a closed-contract map:
     :re-init-done bool   — every module reloaded with no throw.
     :partial      bool   — at least one module reloaded but ≥1
                             failed; reloadable is still >0.
     :failed       vector — `[{:ns sym :throwable-class class-name
                                :message text} ...]`
     :reloadable   int    — total modules attempted."
  (:require [clojure.java.io :as jio]
            [clojure.tools.logging :as log]))

(defn- run-init!
  "Run the init file. Failures logged and swallowed."
  [init-file]
  (cond
    (nil? init-file)
    (do (binding [*out* *err*]
          (log/info "no init file configured; skipping")
          (flush))
        nil)
    (.exists (jio/file init-file))
    (try
      (log/info "running init:" init-file)
      (load-file init-file)
      (catch Throwable t
        (binding [*out* *err*]
          (log/error t "init load failed")
          (flush))
        nil))
    :else
    (do (binding [*out* *err*]
          (log/warn "init file not found:" init-file)
          (flush))
        nil)))

(defn do-reload
  "Reload all modules in topo order, then run init. Failures → :failed. Returns contract map."
  [ordered init-file]
  (log/info "discovered" (count ordered) "module(s):"
            (pr-str (mapv str ordered)))
  (let [results (mapv (fn [n]
                         (try
                           (require (symbol n) :reload)
                           [n :ok]
                           (catch Throwable t
                             [n [(.getName (class t)) (.getMessage t)]])))
                       ordered)
        failed (->> results
                    (filter (fn [[_ r]] (vector? r)))
                    (mapv (fn [[n v]]
                            {:ns (symbol n)
                             :throwable-class (nth v 0)
                             :message (nth v 1)})))]
    (log/info "reload attempts:" (count ordered)
              "failures:" (count failed))
    (when (seq failed)
      (doseq [f failed]
        (log/error "reload-failed:" (:ns f)
                    (:throwable-class f) (:message f))))
    (run-init! init-file)
    {:re-init-done (zero? (count failed))
     :partial      (boolean (seq failed))
     :failed       failed
     :reloadable   (count ordered)}))
