(ns nihilite.readline.completion.source
  "Candidate-pool builders for the TAB completer. Sourced from:
     1. `(.ns-map ns)` keys  (current ns internals)
     2. ns-aliases of `ns` (`:as` alias targets, full ns/name form)
        plus any extras passed via `extra-ns-names` (tracked by the
        readline driver)
     3. `clojure.core` ns-publics  (full ns/name form)

   The source fns are uncapped; the cap is applied by the
   completer AFTER prefix-filter, so prefix matches past the
   first 100 in alphabetical order are still surfaced on TAB."
  (:import ))

(def ^:const ^:long candidate-cap 100)

(defn- ns-public-symbol-names
  "Sorted vec of full `ns/name` strings from the public vars of `ns`.

   `(ns-publics ns)` returns an `IPersistentMap` mapping symbol → Var;
   we iterate `.values()` to get the Var objects directly. We then
   render each as `<ns-name>/<var-sym>` and sort alphabetically."
  [^clojure.lang.Namespace ns]
  (when ns
    (let [ns-name (ns-name ns)
          ^java.util.Map m (ns-publics ns)]
      (->> (.values m)
           (map (fn [^clojure.lang.Var v]
                  (str ns-name "/" (.sym v))))
           (sort)
           (into [])))))

(defn- current-ns-symbol-names
  "Sorted vec of `name` strings (no ns prefix) from every Var in
   the current ns's `ns-map` — both public and private, since the
   user has interned them in their own REPL session. spec §3.1
   row 1: '.ns-map *ns*' keys (not 'ns-publics').

   `ns-map` returns an `IPersistentMap` mapping symbol → (Var | Class);
   we iterate `.values()` and filter to Var only — Java imports in
   the ns (e.g. `(import 'java.util.Map)`) live in the same map as
   Class instances and would blow up a Var cast otherwise."
  [^clojure.lang.Namespace ns]
  (when ns
    (let [^java.util.Map m (ns-map ns)]
      (->> (.values m)
           (filter #(instance? clojure.lang.Var %))
           (map (fn [^clojure.lang.Var v] (str (.sym v))))
           (into [])))))

(defn- ns-aliases-of
  "Names of nses that the current ns has aliased via `:as`. Spec §3.1
   row 2 calls for the ':as' alias ns to be searchable; the alias
   map is the runtime-visible record of those aliases.

   NOTE: `(:require *ns*)` returns nil in stock Clojure — the `ns`
   macro does not store libspecs in ns metadata; it expands them
   into `require` calls at compile time. The only post-compile
   record of an `:as` alias is the alias map. To make non-aliased
   `(require 'foo)` (no `:as`) candidates visible, the readline
   driver (Unit 4) tracks requires in a per-session atom and passes
   the accumulated set via the `:extra-ns-names` opt below."
  [^clojure.lang.Namespace ns]
  (when ns
    (->> (ns-aliases ns)
         vals
         (map (fn [^clojure.lang.Namespace t] (str (ns-name t))))
         (into #{}))))

(defn- required-ns-names
  "Set of fully-qualified ns-name strings to search in, derived from
   the runtime-visible alias map of `ns` (spec §3.1 row 2, ':as alias
   ns and original ns both').

   `(:require *ns*)` does NOT return libspecs in stock Clojure; we
   fall back to `(ns-aliases ns)` for `:as` aliases (which are
   runtime-visible) plus any `:extra-ns-names` passed by the
   readline driver to cover non-aliased `(require 'foo)` calls."
  ^java.util.Set ([^clojure.lang.Namespace ns]
                  (required-ns-names ns nil))
  ([^clojure.lang.Namespace ns extra-ns-names]
   (cond-> (ns-aliases-of ns)
     (seq extra-ns-names)
     (into (set extra-ns-names)))))

(defn- public-names-from-ns-names
  "Map ns-name-str → sorted vec of `<ns-name>/<var-name>` strings
   for every ns-name in `ns-names` that resolves to a real ns.
   Nses that fail to resolve are silently skipped (a stale or
   misspelled `:require` should not blow up completion)."
  [ns-names]
  (into {}
        (keep (fn [ns-name]
                (when-let [target (find-ns (symbol ns-name))]
                  [ns-name (ns-public-symbol-names target)])))
        ns-names))

(defn completions-source
  "Return the FULL (uncapped), sorted, deduped vec of candidate
   symbol-name strings for `ns`. The cap is applied later by
   the prefix-filter+cap path in `nihilite.readline.completion.prefix`."
  ([^clojure.lang.Namespace ns]
   (completions-source ns nil))
  ([^clojure.lang.Namespace ns extra-ns-names]
   (let [from-current  (or (current-ns-symbol-names ns) [])
         from-required (let [ns-names (required-ns-names ns extra-ns-names)]
                         (if (seq ns-names)
                           (->> (public-names-from-ns-names ns-names)
                                vals
                                (apply concat []))
                           []))
         from-core     (or (ns-public-symbol-names (find-ns 'clojure.core)) [])]
     (->> (concat from-current from-required from-core)
          distinct
          sort
          vec))))

(defn completions-for
  "Return a sorted, deduped vec of candidate symbol-name strings for
   `ns`, capped at `candidate-cap` (= 100). Thin cap-wrapper over
   `completions-source` for callers that want the raw pool preview."
  ([^clojure.lang.Namespace ns]
   (completions-for ns nil))
  ([^clojure.lang.Namespace ns extra-ns-names]
   (vec (take candidate-cap (completions-source ns extra-ns-names)))))
