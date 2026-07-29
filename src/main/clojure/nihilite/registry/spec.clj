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
  "Canonical method-key: `<internal>` + `/` + `<method-name>` +
   `#` + `<descriptor>`. The separator `#` is not legal in any
   JVM-internal name, method name, or JLS descriptor, so the
   concatenation is unambiguous."
  [class-internal method-name descriptor]
  (str class-internal "/" method-name "#" descriptor))

(defrecord HookSpec
  [id target-internal method-name position arity bridge note
   action method-key source-class source-descriptor tag
   capture-stack? max-depth sample-rate])

(defrecord HookContext
  [hookId self args phase returnValue ^:volatile-mutable cancelled])

(defrecord HookEvent
  ;; Cancellation: `:cancelled?` and `:cancel!` are closures over a per-event
  ;; `AtomicBoolean`; `(:cancelled? ev)` reads it and `((:cancel! ev) true)`
  ;; flips it. #_see event.clj:5
  [spec-id source phase self args return-value throwable
   cancelled? cancel! thread-name timestamp-ns sequence note stack])

(defn normalize-position
  "Coerce a position value (keyword, string, or nil) to one of
   the canonical phase keywords. Unknown values default to :entry."
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
  "Coerce an action value to a keyword in #{:observe :modify :cancel :subscriber}.
   nil / missing → :observe. Unknown → returned as-is; install!
   then validates against `ACTIONS`."
  [a]
  (cond
    (nil? a)         :observe
    (keyword? a)     a
    (string? a)      (keyword (.toLowerCase ^String a))
    :else            a))
