(ns nihilite.hooks
  "User-facing install surface for hook IFns. Clojure 1.12
   compatibility: atoms are accessed via `deref` + `get` rather
   than as IFn (the IFn-as-atom shortcut for map lookup was
   removed from clojure 1.12's Atom impl).

   The cell + delegating-bridge pattern keeps a single stable
   bridge IFn across install!/hot-swap!/uninstall!:
     - install! registers the delegating IFn as the registry's
       bridge, then resets the per-keyword cell to the user IFn.
     - hot-swap! only resets the cell.
     - uninstall! clears the cell, removes the spec, and drops
       the cached bridge so a re-install! creates a fresh one."
  (:require [clojure.tools.logging :as log]
            [nihilite.registry :as reg]))

(def ^:private targets (atom {}))

(defn register-target! [kw partial-spec]
  (swap! targets assoc kw partial-spec)
  (kw @targets))

(defonce ^:private cells   (atom {}))
(defonce ^:private bridges (atom {}))

(defn- cell-for
  "Return the cell-atom for kw, creating a fresh empty one if
   no entry exists yet."
  ^clojure.lang.IAtom [kw]
  (or (clojure.core/get @cells kw)
      (let [fresh (atom nil)]
        (swap! cells assoc kw fresh)
        (clojure.core/get @cells kw))))

(defn- delegating-ifn*
  "Build a single fresh delegating IFn that, on every invoke,
   reads the user IFn from `cell` and delegates. The bridge
   object identity is preserved across re-install!/hot-swap!."
  ^clojure.lang.IFn [cell]
  (fn [ctx]
    (let [f @cell]
      (when f (f ctx)))))

(defn install!
  "Install IFn as hook for known kw. Throws :nihilite/unknown-hook if unknown."
  [kw ifn]
  (let [m (clojure.core/get @targets kw)]
    (when-not m
      (throw (ex-info (str "nihilite.hooks: unknown hook keyword "
                           (pr-str kw) "; known: " (pr-str (keys @targets)))
                      {:nihilite/kind :nihilite/unknown-hook
                       :nihilite/kw kw
                       :nihilite/known (vec (keys @targets))})))
    (let [cell   (cell-for kw)
          bridge (or (clojure.core/get @bridges kw)
                     (delegating-ifn* cell))]
      (swap! bridges assoc kw bridge)
      (reset! cell ifn)
      (reg/install!
        {:id               (name kw)
         :target-internal  (:target-internal m)
         :method-name      (:method-name m)
         :position         (:position m)
         :arity            (when-some [a (:arity m)]
                             (if (number? a) (int a)
                                 (Integer/parseInt (str a))))
         :descriptor       (:descriptor m)
         :bridge           bridge
         :note             (or (:note m) "")})
      (log/info "installed:" (name kw)
                "→" (:method-name m) "target=" (:target-internal m))
      nil)))

(defn hot-swap!
  "Atomically replace IFn for kw. Returns new-ifn; spec stays registered."
  [kw new-ifn]
  (reset! (cell-for kw) new-ifn)
  new-ifn)

(defn uninstall!
  "Remove hook by kw. Returns true if removed. Clears cell, drops bridge."
  [kw]
  (let [id           (name kw)
        cell         (clojure.core/get @cells kw)
        reg-removed? (reg/uninstall! id)]
    (when cell (reset! cell nil))
    (swap! bridges dissoc kw)
    (swap! cells   dissoc kw)
    (boolean reg-removed?)))

(defn installed?
  "True if the keyword currently has a registered spec."
  [kw]
  (some? (reg/lookup (name kw))))

(defn current-ifn
  "The user IFn currently associated with kw (or nil)."
  [kw]
  (when-let [cell (clojure.core/get @cells kw)]
    @cell))

(defn bridge-of
  "The stable delegating IFn currently registered for kw, or
   nil. Diagnostic."
  [kw]
  (clojure.core/get @bridges kw))

(defn ctx-self        [ctx]          (reg/ctx-self ctx))
(defn ctx-arg         [ctx n]        (reg/ctx-arg ctx n))
(defn ctx-argc        [ctx]          (reg/ctx-argc ctx))
(defn ctx-return      [ctx]          (reg/ctx-return ctx))
(defn ctx-phase       [ctx]          (reg/ctx-phase ctx))
(defn ctx-cancel!     [ctx v]        (reg/ctx-cancel! ctx v))
(defn ctx-cancelled?  [ctx]          (reg/ctx-cancelled? ctx))
