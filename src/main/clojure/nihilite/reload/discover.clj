(ns nihilite.reload.discover
  "Filesystem walker: enumerate every .clj file under a modules
   directory whose header marks it as a reloadable module. Returns
   a vector of `[ns-symbol header-map file]` triples ready for
   topological sort."
  (:require [clojure.java.io :as jio]
            [clojure.string :as str]
            [nihilite.reload.header :as header]))

(defn module-ns
  "Module header value to a Clojure ns symbol. Underscores become hyphens."
  [m]
  (symbol (str/replace m "_" "-")))

(defn discover-modules
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
                   (when-let [h (header/parse-module-header f)]
                     [(module-ns (:module h)) h f])))
           vec))))
