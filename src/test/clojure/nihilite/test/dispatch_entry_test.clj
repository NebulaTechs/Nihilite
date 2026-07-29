(ns nihilite.test.dispatch-entry-test
  "Entry-phase audit regression.

   The plan invented a `:modify-with-rv` case branch in
   `nihilite.registry.dispatch.entry/dispatch-for-spec` and proposed
   removing it. After audit: the branch NEVER existed in the source.
   `entry.clj` does not consume `:return-value ev` and does not contain
   a `(case action ...)` form at all — it routes via `if`/loop directly.

   These two assertions lock that property in. If a future change
   introduces `(case action ...)` dispatch OR reads `:return-value ev`
   from the event, this test fails — surfacing the architectural
   decision (entry phase ignores observer return values; return-value
   consumption belongs in the :modify/:cancel phase, see
   `dispatch-return-for-spec` in the :return namespace)."
  (:require [clojure.test :refer [deftest is]]))

(def ^:private entry-clj-path
  "src/main/clojure/nihilite/registry/dispatch/entry.clj")

(def ^:private entry-clj-source
  (delay (slurp entry-clj-path)))

(deftest entry-does-not-consume-rv
  (let [src @entry-clj-source]
    ;; (1) No (case action ...) dispatch form in entry.clj — entry phase
    ;;     routes via direct if/loop, not a case-on-action table.
    (is (nil? (re-find #"\(case\s+action" src))
        "entry.clj must not contain a `(case action ...)` dispatch form")
    ;; (2) No `:return-value ev` consumption — entry phase does not read
    ;;     return-value off the event. Return-value belongs to the
    ;;     :modify/:cancel phase (dispatch-return-for-spec).
    (is (nil? (re-find #":return-value" src))
        "entry.clj must not read `:return-value ev` from the event")))