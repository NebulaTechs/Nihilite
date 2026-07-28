(ns nihilite.readline.completion
  "Facade for the jline3 TAB completer."
  (:require [nihilite.readline.completion.source :as src]
            [nihilite.readline.completion.completer :as cpl]))

(def completions-for src/completions-for)
(def completer-for   cpl/completer-for)
