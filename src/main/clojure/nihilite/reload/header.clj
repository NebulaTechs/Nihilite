(ns nihilite.reload.header
  "Read the `;; nihilite-module:` / `;; nihilite-requires:` header
   pair from a .clj file. A module value of `-` (or empty /
   whitespace) is treated as 'not a module' and returns nil —
   those files are excluded from the reload walker (single-shot
   init files, opt-in examples, test fixtures)."
  (:require [clojure.java.io :as jio]
            [clojure.string :as str]))

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

(defn parse-module-header
  "Read first N lines of .clj file. Returns {:module m :requires [r...]} or nil."
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
