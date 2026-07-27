(ns nihilite.reload
  "Manual environment reset. No file-watch, no auto-reload.
   Call (nihilite.reload/re-init!) for a clean slate.

   Each .clj file under the modules directory declares its place
   in the dependency graph with two header lines near the top:

       ;; nihilite-module: <name>
       ;; nihilite-requires: <space-separated-module-names>

   re-init! walks the graph in topological order and
   (require '[<ns>] :reload) each namespace, clobbering any
   in-REPL alter-var-root overrides back to file-defined values."
  (:require [clojure.java.io :as jio]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def header-prefix-module ";; nihilite-module:")
(def header-prefix-requires ";; nihilite-requires:")
(def header-scan-lines 50)

(defn- line-after-prefix
  "If the line (trimmed of leading whitespace) starts with prefix,
   return the trimmed remainder. Otherwise nil."
  [line prefix]
  (let [trimmed (str/triml line)]
    (when (str/starts-with? trimmed prefix)
      (str/trim (subs trimmed (count prefix))))))

(defn- parse-module-header
  "Read first N lines of a .clj file. Returns
  {:module m :requires [r...]} if a ;; nihilite-module header is found,
  else nil. A module value of `-` (or empty/whitespace) is treated as
  'not a module' and returns nil — those files are excluded from the
  reload walker (single-shot init files, opt-in examples, test fixtures)."
  [f]
  (with-open [r (jio/reader f)]
    (let [lines (take header-scan-lines (line-seq r))
          module (some #(line-after-prefix % header-prefix-module) lines)
          requires-str (some #(line-after-prefix % header-prefix-requires) lines)]
      (when (and module (not (str/blank? module)) (not= (str/trim module) "-"))
        (let [reqs (if (or (nil? requires-str) (str/blank? requires-str) (= requires-str "-"))
                     []
                     (str/split requires-str #"\s+"))]
          {:module module :requires reqs})))))

(defn- module-ns
  "Module header value to a Clojure ns symbol. Underscores become hyphens."
  [m]
  (symbol (str/replace m "_" "-")))

(defn- discover-modules
  "Walk modules-dir and return a vector of
  [ns-symbol header-map file] for every .clj file with a
  ;; nihilite-module header."
  [modules-dir]
  (let [root (jio/file modules-dir)]
    (if-not (.isDirectory root)
      []
      (->> (file-seq root)
           (filter #(.isFile %))
           (filter #(str/ends-with? (.getName %) ".clj"))
           (keep (fn [f]
                   (when-let [h (parse-module-header f)]
                     [(module-ns (:module h)) h f])))
           vec))))

(defn- topo-sort
  "Topological sort over discovered modules. Iterative
   three-color DFS (white/gray/black); deterministic across runs
   because we iterate module names in sorted order. Missing deps
   are warned but skipped. Cycles fail BEFORE any reload with a
   structured `:cycle-path` carrying the participating modules."
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
                  (let [deps (mapv module-ns (get-in by-name [n :requires]))]
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

(defn discover-ordered
  "Walk modules-dir and return the ordered seq of ns symbols
  (topo-sorted). Exposed for diagnostics."
  ([] (discover-ordered "examples"))
  ([modules-dir]
   (->> (discover-modules modules-dir)
        topo-sort
        vec)))

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

(defn- do-reload
  "Reload all modules in topo order, then run init. Cycle and
   fatal-graph cases fail BEFORE any module is reloaded and
   surface via the return map. Per-module failures are accumulated
   into :failed with throwable class + message per entry. Returns
   a structured map whose keys form a closed contract:
     :re-init-done bool   — every module reloaded with no throw.
     :partial      bool   — at least one module reloaded but ≥1
                             failed; reloadable is still >0.
     :failed       vector — `[{:ns sym :throwable-class class-name
                                :message text} ...]`
     :reloadable   int    — total modules attempted."
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
modules (discover-modules modules-dir)]
      (if (empty? modules)
        (do (log/info "no modules discovered under" modules-dir
                      "(looking for .clj files with"
                      header-prefix-module "header)")
            {:re-init-done false :partial false :failed [] :reloadable 0})
       (try
         (do-reload (topo-sort modules) init-file)
(catch clojure.lang.ExceptionInfo e
            (when (= :nihilite/cycle (:nihilite/kind (ex-data e)))
              (let [cyc (:cycle-path (ex-data e))]
                (log/error "ABORTED — cycle detected:" cyc)
                {:re-init-done false :partial false :failed []
                 :reloadable  0 :cycle-path cyc}))))))))