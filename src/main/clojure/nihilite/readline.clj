(ns nihilite.readline
  "Facade for the jline3-backed raw-branch REPL. Re-exports the
   public surface used by `nihilite.transport.raw`."
  (:require [nihilite.readline.terminal :as term]
            [nihilite.readline.eval :as ev]
            [nihilite.readline.loop :as lp]))

(def banner              lp/banner)

(def build-terminal      term/build-terminal)

(def eval-form           ev/eval-form)
(def eval-form-lf        ev/eval-form-lf)

(def run-loop            lp/run-loop)
