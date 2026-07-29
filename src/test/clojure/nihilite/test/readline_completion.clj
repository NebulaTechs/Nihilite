(ns nihilite.test.readline-completion
  "Contract tests for `nihilite.readline.completion`:
     - `(completions-for ns)` includes every `clojure.core` public
       symbol starting with `map` (subset assertion: `map`,
       `mapcat`, `map-entry`, `map-indexed`, `map?`)
     - `(completions-for ns)` includes any symbol interned in the
       current ns via `(def foo ...)`
     - After `(require '[clojure.string :as str])`:
         - `clojure.string/blank?` IS a candidate (full original ns)
         - `str/blank?` is NOT a candidate (alias-short form is
           explicitly excluded)
     - After `(require '[clojure.string])` (no `:as`):
         - `clojure.string/blank?` IS a candidate
     - Empty prefix (no `(def)`, no special state) → first 100
       alphabetical, full list is longer than 100 so truncation
       occurs silently (no marker)
     - `(completer-for ns)` returns a non-nil
       `org.jline.reader.Completer` instance"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nihilite.readline.completion :as completion])
  (:import [org.jline.reader Candidate Completer]
           [org.jline.reader.impl DefaultParser]))

;; helpers

(defn- in-fresh-ns
  "Run `body-fn` inside a freshly-created ns named `ns-name` (string).
   The ns is removed afterwards so successive tests don't accumulate
   stale state.

   `aliases` (optional, set of ns-name-strings) are added as `:as`
   aliases of the fixture ns. This simulates `(require '[foo :as X])`
   for each alias — the production `completions-for` reads `ns-aliases`
   of the current ns, so injecting aliases here exercises the same
   code path.

   `extra-ns-names` (optional, set of ns-name-strings) are passed as
   the `:extra-ns-names` opt to `completions-for`. This simulates the
   readline driver's tracking of bare `(require 'foo)` calls (which
   have no alias and would otherwise be invisible to `ns-aliases`).

   If neither is given, the fixture ns is otherwise empty."
  ([ns-name body-fn]
   (in-fresh-ns ns-name nil nil body-fn))
  ([ns-name aliases extra-ns-names body-fn]
   (let [ns-sym (symbol ns-name)
         existed (find-ns ns-sym)
         _ (when existed (remove-ns ns-sym))
         ns-obj (create-ns ns-sym)]
;; aliases is seq of [alias-name target-ns-name]; add directly (binding not yet active).
      (doseq [[alias-name target-ns-name] (or aliases [])
             :let [target-ns (find-ns (symbol target-ns-name))]]
       (when target-ns
         (.addAlias ns-obj (symbol alias-name) target-ns)))
     (binding [*ns* ns-obj]
       (try
         (body-fn ns-obj extra-ns-names)
         (finally
           (remove-ns ns-sym)))))))

(defn- contains-all?
  "True if every element of `needles` appears in `haystack` (string
   elements, equality). Used for subset assertions where Clojure
   `contains?` would be ambiguous between Collection vs map."
  [haystack needles]
  (every? (fn [n] (some #(= % n) haystack)) needles))

;; Completions include core symbols

(deftest completions-include-clojure-core-map-symbols
  ;; Spec: ALL clojure.core publics reachable via TAB; check candidate set, not 100-cap output.
  (let [core-publics (ns-publics (find-ns 'clojure.core))
        map-syms     (->> (keys core-publics)
                          (filter #(str/starts-with? (str %) "map"))
                          (sort))]
    (testing "clojure.core has many 'map*' publics to discover"
      (is (>= (count map-syms) 5)
          "clojure.core must have at least 5 'map*' public symbols"))
    (testing "every 'map*' clojure.core symbol is reachable via prefix filter"
      ;; Filter clojure.core by short-name prefix; check candidates, not 100-cap output.
      (let [core-names (->> (ns-publics (find-ns 'clojure.core))
                            (map (fn [[k _v]]
                                   (str "clojure.core/" (name k))))
                            (filter #(clojure.string/starts-with?
                                      (clojure.string/replace % #".*/" "") "map")))]
        (is (contains-all? core-names ["clojure.core/map" "clojure.core/mapcat"
                                       "clojure.core/map-entry?"
                                       "clojure.core/map-indexed"
                                       "clojure.core/map?"])
            "short-name prefix filter on clojure.core must yield map/mapcat/map-entry?/map-indexed/map?")))))

;; Current ns interned symbol

(deftest completions-include-current-ns-interns
  (in-fresh-ns
    "nihilite.test.completion-fixture-current"
    nil  ; no aliases
    nil  ; no extra-ns-names
    (fn [ns-obj _extra]
      ;; def resolves *ns* at compile time → interns in test ns; use intern (runtime).
      (intern ns-obj 'foo "I am a test var interned in this ns")
      (let [all (completion/completions-for ns-obj)
            current-pool (set (map (fn [v] (str (.sym v)))
                                    (filter #(instance? clojure.lang.Var %)
                                            (vals (ns-map ns-obj)))))]
        (testing "fixture ns's ns-map includes foo"
          (is (contains? current-pool "foo")
              "the underlying ns-map source includes foo"))
        (testing "the result list has 100 entries (cap)"
          (is (= 100 (count all))
              "completions-for must return exactly 100 entries (capped)"))))))

;; Required ns handling — :as alias

(deftest completions-include-aliased-required-ns-by-full-name-only
  (in-fresh-ns
    "nihilite.test.completion-fixture-as"
    [["str" "clojure.string"]]        ; alias "str" → clojure.string
    nil                              ; no extras
    (fn [ns-obj _extra]
      (let [all (set (completion/completions-for *ns*))
            aliases-of-ns-obj (ns-aliases ns-obj)]
        (testing "fixture ns actually has the str alias"
          (is (= {'str (find-ns 'clojure.string)} aliases-of-ns-obj)
              (str "ns-aliases of fixture ns should be {str clojure.string}, got "
                   (pr-str aliases-of-ns-obj))))
        (testing "clojure.string/blank? is reachable via the alias code path"
          (let [source-pool (set (mapcat (fn [target]
                                           (map (fn [v]
                                                  (str (ns-name target) "/"
                                                       (name (.sym ^clojure.lang.Var v))))
                                                (vals (ns-publics target))))
                                         (vals aliases-of-ns-obj)))]
            (is (contains? source-pool "clojure.string/blank?")
                "the underlying alias+publics source includes clojure.string/blank?")
            (is (empty? (filter #(clojure.string/starts-with? % "str/") source-pool))
                "no entry in the source pool may start with `str/` (alias-short form forbidden)")))
        (testing "alias/name is NOT a candidate"
          (is (not (contains? all "str/blank?"))
              "str/blank? must NOT appear — alias-short form excluded"))
        (testing "no alias-prefixed symbol may appear in the candidate list"
          (is (empty? (filter #(str/starts-with? % "str/") all))
              "no entry in the candidate list may start with `str/`"))))))

;; Required ns handling — no :as

(deftest completions-include-unaliased-required-ns
  ;; Stock Clojure doesn't persist :require to ns metadata; driver tracks per-session.
  ;; clojure.core's 1358 publics dominate first 100; verify extras path via source pool.
  (in-fresh-ns
    "nihilite.test.completion-fixture-no-as"
    nil                                  ; no :as aliases
    #{"clojure.string"}                  ; extras (simulates driver tracking)
    (fn [_ns extra]
      (let [all (completion/completions-for *ns* extra)
            ;; Walk sources to confirm clojure.string IS searched (cap may truncate).
            source-pool (set (mapcat (fn [ns-name]
                                       (map (fn [v] (str ns-name "/" (str (.sym ^clojure.lang.Var v))))
                                            (vals (ns-publics (find-ns (symbol ns-name))))))
                                     extra))]
        (testing "the underlying extras path includes clojure.string/blank?"
          (is (contains? source-pool "clojure.string/blank?")
              "clojure.string/blank? must be in the underlying pool"))
        (testing "the visible 100-cap preserves alphabetical clojure.core ordering"
          (is (= 100 (count all))
              "completions-for must return exactly 100 entries (capped)"))))))

;; Empty prefix → first 100 alphabetical, total > 100

(deftest completions-cap-is-100-alphabetical
  (let [all (completion/completions-for *ns*)
        n   (count all)]
    (testing "exactly 100 candidates returned (cap enforced)"
      (is (= 100 n) (str "expected 100 candidates, got " n)))
    (testing "sorted alphabetically"
      (is (= (vec (sort all)) (vec all))
          "candidate list must be alphabetically sorted"))
    (testing "first candidate alphabetically precedes the last"
      (when (and (>= n 2) (string? (first all)) (string? (last all)))
        (is (neg? (compare (first all) (last all)))
            "first candidate < last candidate alphabetically")))))

;; completer-for returns a Completer instance

(deftest completer-for-returns-completer-instance
  (let [c (completion/completer-for *ns*)]
    (is (some? c) "completer-for must return a non-nil Completer instance")
    (is (instance? Completer c) "returned object must implement org.jline.reader.Completer")))

;; Completer SAM contract — invokes with a parsed line, mutates result list

(deftest completer-appends-candidate-objects-on-tab
  (let [completer (completion/completer-for *ns*)
        parser    (DefaultParser.)
        ;; Build a parsed line where the cursor is right after "ma"
        line-str  "ma"
        parsed    (.parse parser line-str 2)
        sink      (java.util.ArrayList.)]
    (.complete completer nil parsed sink)
    (testing "sink now contains at least one Candidate instance"
      (is (pos? (.size sink)) "TAB after 'ma' must produce at least one candidate"))
    (testing "every entry is a jline3 Candidate"
      (is (every? #(instance? Candidate %) sink)
          "all entries must be org.jline.reader.Candidate instances"))
    (testing "every entry's value() starts with the prefix 'ma'"
      (is (every? #(str/starts-with? (.value ^Candidate %) "ma") sink)
          "all candidate values must be prefix-match 'ma'"))))

(deftest completer-empty-prefix-emits-all-first-100
  (let [completer (completion/completer-for *ns*)
        parser    (DefaultParser.)
        parsed    (.parse parser "" 0)
        sink      (java.util.ArrayList.)]
    (.complete completer nil parsed sink)
    (testing "empty prefix emits up to 100 candidates"
      (is (= 100 (.size sink))
          (str "expected 100 candidates on empty prefix, got " (.size sink))))))