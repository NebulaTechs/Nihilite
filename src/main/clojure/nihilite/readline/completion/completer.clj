(ns nihilite.readline.completion.completer
  "jline3 `Completer` SAM. Returns a Completer that, on TAB, sources
   candidates from `ns`, prefix-filters the uncapped pool, then caps
   the result. We compute the full underlying candidate set WITHOUT
   the 100 cap, filter by the prefix, then apply the cap on the
   *filtered* set — this is the spec §3.2 UX intent: a user typing
   `ma<TAB>` must see `map` / `mapcat` / `map-entry` etc., even if
   those symbols sit at position ~150 alphabetically in the full set."
  (:require [nihilite.readline.completion.source :as src]
            [nihilite.readline.completion.prefix :as pfx])
  (:import [org.jline.reader Completer]))

(defn completer-for
  "Return a jline3 `org.jline.reader.Completer` instance that, on
   TAB, sources candidates from `ns`. The completer mutates the
   passed-in `java.util.List` in-place (jline3's SAM contract);
   it is NOT pure, but it is stateless across calls.

   The `extra-ns-names` opt is passed through to
   `completions-source` so the readline driver can track bare
   `(require 'foo)` calls (no `:as`) and surface their publics."
  (^Completer [^clojure.lang.Namespace ns]
   (completer-for ns nil))
  (^Completer [^clojure.lang.Namespace ns extra-ns-names]
   (reify Completer
     (complete [_this _reader line candidates]
       (let [prefix  (.word ^org.jline.reader.ParsedLine line)
             matched (pfx/matched-and-capped
                       (src/completions-source ns extra-ns-names)
                       prefix)]
         (.addAll ^java.util.List candidates matched))))))
