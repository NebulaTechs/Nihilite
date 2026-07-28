(ns nihilite.readline.completion.prefix
  "Prefix matching, short-name extraction, and 100-cap enforcement
   for the TAB completer. Sits between `nihilite.readline.completion.source`
   (uncapped pool) and the jline3 Completer SAM (which mutates
   the caller's list in place).

   The prefix matches against the candidate's *short name* (the
   part after the last `/`) when the prefix has no `/`. This
   matches the user expectation that `map<TAB>` completes to
   the var `map` (auto-referred from `clojure.core`), inserting
   the SHORT name `map` — see `insertion-value` for the value vs
   display split. If the prefix itself contains a `/`, the match
   is against the full candidate string (ns-qualified completion)
   and the value is the full string. If the prefix is empty, all
   candidates match (full string inserted)."
  (:require [nihilite.readline.completion.source :as src])
  (:import [java.util ArrayList]
           [org.jline.reader Candidate]))

(def ^:const ^:long candidate-cap src/candidate-cap)

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
   caller controls capping."
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

(defn matched-and-capped
  "Pipeline-friendly: take a candidate pool + prefix, return a
   java.util.List of jline Candidates, capped at `candidate-cap`."
  ^java.util.List [candidates ^String prefix]
  (capped-list (prefix-matches candidates prefix)))
