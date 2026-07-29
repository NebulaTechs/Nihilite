(ns nihilite.registry
  "Generic, loader-agnostic registry of hook specs + dispatch
   helpers. Thin facade over the sub-namespaces — callers should
   use this ns, not the sub-namespaces directly."
  (:refer-clojure :exclude [snapshot])
  (:require [nihilite.registry.spec :as rs]
            [nihilite.registry.install :as ri]
            [nihilite.registry.dispatch :as rd]
            [nihilite.registry.dispatch.entry :as rd-entry]
            [nihilite.registry.dispatch.return :as rd-return]
            [nihilite.registry.dispatch.throw :as rd-throw]
            [nihilite.registry.dispatch.invoke :as rd-invoke]
            [nihilite.registry.accessors :as ra]
            [nihilite.registry.stats :as rstats]
            [nihilite.registry.spec :refer [map->HookSpec]])
  (:import [nihilite.registry.spec
            HookContext HookEvent HookSpec]))

;; Position / action keywords re-exported from spec
(def ENTRY   rs/ENTRY)
(def EXCATCH rs/EXCATCH)
(def RETURN  rs/RETURN)
(def ACTIONS rs/ACTIONS)

(def method-key           rs/method-key)
(def install!             ri/install!)
(def install-fresh!       ri/install-fresh!)
(def install-new!        ri/install-new!)
(def uninstall!           ri/uninstall!)
(def clear!               ri/clear!)
(def matching             ri/matching)
(def snapshot             ri/snapshot)
(def list-ids             ri/list-ids)
(def lookup               rd/lookup)
(def lookup-spec-for-call rd/lookup-spec-for-call)
(def dispatch             rd-entry/dispatch-for-spec)
(def dispatch-for-spec    rd-entry/dispatch-for-spec)
(def dispatch-return-for-spec rd-return/dispatch-return-for-spec)
(def dispatch-throw-for-spec rd-throw/dispatch-throw-for-spec)
(def dispatch-invoke-for-spec rd-invoke/dispatch-invoke-for-spec)
(def dispatch-redefine    rd/dispatch-redefine)
(def install-redefine-dispatcher! rd/install-redefine-dispatcher!)
(def ctx-self             ra/ctx-self)
(def ctx-arg              ra/ctx-arg)
(def ctx-argc             ra/ctx-argc)
(def ctx-return           ra/ctx-return)
(def ctx-phase            ra/ctx-phase)
(def ctx-cancel!          ra/ctx-cancel!)
(def ctx-cancelled?       ra/ctx-cancelled?)
(def position             ra/position)

;; Stats (parallel StatsIndex, not on HookSpec — B3 fix)
(def ensure-stats         rstats/ensure-stats)
(def get-stats            rstats/get-stats)
(def remove-stats         rstats/remove-stats)

(defn spec
  "Convenience constructor mirroring HookSpec record. Trailing fields computed from first 7 + opts."
  ([id target-internal method-name position arity bridge note]
   (spec id target-internal method-name position arity bridge note nil :observe nil))
  ([id target-internal method-name position arity bridge note descriptor]
   (spec id target-internal method-name position arity bridge note descriptor :observe nil))
  ([id target-internal method-name position arity bridge note descriptor action]
   (spec id target-internal method-name position arity bridge note descriptor action nil))
  ([id target-internal method-name position arity bridge note descriptor action tag]
   (let [tid (str target-internal)
         mn  (str method-name)
         desc (when descriptor (str descriptor))
         mk (when (and desc (not (empty? desc)))
              (rs/method-key tid mn desc))
         sc (when mk (.replace ^String tid "/" "."))
         pos (rs/normalize-position position)
         act (rs/normalize-action action)]
     (map->HookSpec {:id                (str id)
                     :target-internal   tid
                     :method-name       mn
                     :position          pos
                     :arity             (when arity (int arity))
                     :bridge            bridge
                     :note              (str note)
                     :action            act
                     :method-key        mk
                     :source-class      sc
                     :source-descriptor desc
                     :tag               tag}))))
