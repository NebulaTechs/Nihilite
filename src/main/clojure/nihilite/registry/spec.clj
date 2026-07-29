(ns nihilite.registry.spec
  "P0 hook-system data shapes: HookSpec, HookContext, HookEvent
   defrecords, position / action keywords, and the canonical
   `method-key` builder (mirrors `nihilite.hooks.HookKeys.build`
   byte-for-byte)."
  (:require [clojure.string :as str]))

(def ^:const ENTRY   :entry)
(def ^:const EXCATCH :excatch)   ; reserved, not dispatched
(def ^:const RETURN  :return)
(def ^:const THROW   :throw)

(def ^:const ACTIONS #{:observe :modify :cancel :subscriber})

(defn method-key
  "Canonical method-key: <internal>/<method-name>#<descriptor>. # is unambiguous separator."
  [class-internal method-name descriptor]
  (str class-internal "/" method-name "#" descriptor))

(defrecord HookSpec
  [id target-internal method-name position arity bridge note
   action method-key source-class source-descriptor tag
   capture-stack? max-depth sample-rate])

(defrecord HookContext
  [hookId self args phase returnValue cancelled])

;; Cancellation closes over per-event AtomicBoolean; see event.clj
(defrecord HookEvent
  [spec-id source phase self args return-value throwable
   cancelled? cancel! thread-name timestamp-ns sequence note stack])

(defn normalize-position
  "Coerce a position (kw/string/nil) to a canonical phase kw; default :entry."
  [p]
  (cond
    (keyword? p) p
    (string? p)  (case (.toUpperCase ^String p)
                   "ENTRY"          :entry
                   "EXCATCH"        :excatch
                   "RETURN"         :return
                   "THROW"          :throw
                   "INVOKE-BEFORE"  :invoke-before
                   "INVOKE-RETURN"  :invoke-return
                   "INVOKE-THROW"   :invoke-throw
                   "REDEFINE"       :redefine
                   :entry)
    :else        :entry))

(defn normalize-action
  "Coerce action to #{:observe :modify :cancel :subscriber}; nil→:observe."
  [a]
  (cond
    (nil? a)         :observe
    (keyword? a)     a
    (string? a)      (keyword (.toLowerCase ^String a))
    :else            a))
