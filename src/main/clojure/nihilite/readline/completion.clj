(ns nihilite.readline.completion
  "TAB completer for jline3. Sources candidates from the current ns
   plus every `:as`-aliased ns in `(:require *ns*)` plus `clojure.core`.
   No fuzzy matching; prefix-only, case-sensitive, alphabetical, 100-cap.
   The cap is applied AFTER the prefix filter, so prefix matches past
   position 100 in the raw pool are still surfaced on TAB."
  (:import [java.util ArrayList]
           [org.jline.reader Candidate Completer LineReader ParsedLine]))

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
   symbol-name strings for `ns`, sourced from:
     1. `(.ns-map ns)` keys  (current ns internals)
     2. ns-aliases of `ns` (`:as` alias targets, full ns/name form)
        plus any extras passed via `extra-ns-names` (tracked by the
        readline driver)
     3. `clojure.core` ns-publics  (full ns/name form)

   Sorted alphabetically, NOT capped. This is the pool the
   `completer-for` filters by prefix BEFORE capping — so a user
   typing `ma<TAB>` sees `map` / `mapcat` even though those sit
   past position 100 in the full alphabetical order.

   `completions-for` wraps this with the 100-cap for callers that
   want the raw pool preview (spec §3.2)."
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
   `completions-source`.

   NOTE: this is the raw-pool PREVIEW (first-100 alphabetical). The
   interactive TAB path in `completer-for` does NOT use this — it
   filters the UNCAPPED `completions-source` by prefix first, then
   caps the filtered result, so prefix matches past position 100
   are still surfaced (spec §3.2).

   Accepts an optional `extra-ns-names` set for nses tracked by
   the readline driver (e.g. bare `(require 'foo)` with no `:as`)."
  ([^clojure.lang.Namespace ns]
   (completions-for ns nil))
  ([^clojure.lang.Namespace ns extra-ns-names]
   (vec (take candidate-cap (completions-source ns extra-ns-names)))))

(defn- short-name-of
  "For a candidate string of the form `ns/name` (or `name` if no
   slash), return the var-name portion after the last slash. Used
   to match a user-typed prefix against the short name rather
   than the fully-qualified form — see `prefix-matches`."
  ^String [^String candidate]
  (let [slash-idx (.lastIndexOf candidate (int \/))]
    (if (neg? slash-idx)
      candidate
      (.substring candidate (unchecked-inc slash-idx)))))

(defn- insertion-value
  "The text jline3 inserts when the user picks candidate `c` after
   a NO-SLASH prefix (short-name match). Directly-usable symbols
   insert their short name; qualified required-ns symbols keep the
   full `ns/name` (their short name is not referred into the ns):

     - no slash              → current-ns symbol, already short → `c`
     - `clojure.core/<name>` → auto-referred everywhere          → `<name>`
     - other `ns/<name>`     → not referred; must qualify        → `c`

   The full `c` is still shown as the Candidate's *display* so the
   user sees provenance; only the *value* (insertion text) differs."
  ^String [^String c]
  (cond
    (neg? (.indexOf c (int \/)))       c
    (.startsWith c "clojure.core/")    (short-name-of c)
    :else                              c))

(defn- prefix-matches
  "Filtered prefix-match. Case-sensitive per spec §3.2. `prefix`
   may be empty (returns all). Returns a java.util.List of
   `org.jline.reader.Candidate` instances — NO cap here, the
   caller controls capping.

   The prefix matches against the candidate's *short name* (the
   part after the last `/`) when the prefix has no `/`. This
   matches the user expectation that `map<TAB>` completes to
   the var `map` (auto-referred from `clojure.core`), inserting
   the SHORT name `map` — see `insertion-value` for the value vs
   display split. If the prefix itself contains a `/`, the match
   is against the full candidate string (ns-qualified completion)
   and the value is the full string. If the prefix is empty, all
   candidates match (full string inserted)."
  ^java.util.List [candidates ^String prefix]
  (let [out (ArrayList.)
        cs  (or candidates [])
        prefix-len (.length prefix)
        has-slash  (>= (.indexOf prefix (int \/)) 0)]
    (doseq [^String c cs]
      (cond
        (zero? prefix-len)
        (.add out (Candidate. c c "completions" nil nil nil true))

        has-slash
        (when (.startsWith c prefix)
          (.add out (Candidate. c c "completions" nil nil nil true)))

        :else
        (when (.startsWith (short-name-of c) prefix)
          (.add out (Candidate. (insertion-value c) c "completions" nil nil nil true)))))
    out))

(defn- capped-list
  "Cap a List at `candidate-cap` elements. Used by the completer
   to enforce the spec §3.2 100-cap on the result handed to
   jline3. Returns the original list reference if under cap,
   otherwise a new ArrayList with the first `candidate-cap` entries."
  ^java.util.List [^java.util.List lst]
  (let [n (.size lst)]
    (if (<= n candidate-cap)
      lst
      (let [out (ArrayList. candidate-cap)]
        (dotimes [i candidate-cap]
          (.add out (.get lst i)))
        out))))

(defn completer-for
  "Return a jline3 `org.jline.reader.Completer` instance that, on
   TAB, sources candidates from `ns`. The completer mutates the
   passed-in `java.util.List` in-place (jline3's SAM contract);
   it is NOT pure, but it is stateless across calls.

   We compute the full underlying candidate set WITHOUT the 100
   cap, filter by the prefix, then apply the cap on the
   *filtered* set. This is the spec §3.2 UX intent: a user
   typing `ma<TAB>` must see `map` / `mapcat` / `map-entry` etc.,
   even if those symbols sit at position ~150 alphabetically
   in the full set.

   The `extra-ns-names` opt is passed through to `completions-for`
   so the readline driver can track bare `(require 'foo)` calls
   (no `:as`) and surface their publics."
  (^Completer [^clojure.lang.Namespace ns]
   (completer-for ns nil))
  (^Completer [^clojure.lang.Namespace ns extra-ns-names]
   (reify Completer
     (complete [_this _reader line candidates]
       (let [prefix  (.word ^org.jline.reader.ParsedLine line)
             ;; first look wide, then cap the TAB bundle
             matched (prefix-matches (completions-source ns extra-ns-names) prefix)
             capped  (capped-list matched)]
         (.addAll ^java.util.List candidates capped))))))