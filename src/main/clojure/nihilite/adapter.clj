(ns nihilite.adapter
  "Runtime-binding protocols for nihilite internals.

   This ns is closed-dispatched (defprotocol) because the methods
   here are on the runtime-internal hot path: boot sentinel
   polling, scheduler routing, event-registry strategy. Adding a
   new runtime is still easy — implement the protocol via
   `install-default!` from the runtime's init file.

    The `:minecraft/vanilla` implementation lives in
    `examples/minecraft/init.clj`. It uses
    `(defrecord MinecraftVanillaAdapter [] nihilite.adapter/BootSentinel ...)`
    and calls `(nihilite.adapter/install-default! :minecraft/vanilla recordInstance)`.

   Workers in `nihilite.agent.Agent` query `default-adapter` at
   boot and call its `wait-until-runtime-ready!` instead of doing
   runtime-specific polling inline. This keeps Agent.js agnostic
   to whether we're attached to vanilla MC, Spigot, Folia, Spring,
   JOOQ, Kafka, generic JRE, etc."

  (:require [clojure.tools.logging :as log]))

;; protocols

(defprotocol BootSentinel
  (wait-until-runtime-ready!
    [this]
    "Block until the runtime's main class / kernel is reachable.

    Returns when the runtime is ready (e.g. the MC server's
    MinecraftServer class is loaded and its lifecycle is past
    the initialization barrier). May throw on hard-fail (e.g.
    class never appears after timeout). May return silently
    on soft-fail — implementations are free to log + return
    rather than throw.

    Used by `nihilite.agent.Agent`'s worker thread to block
    before binding `Compiler/LOADER` and before calling
    `nihilite.boot/start!`."))

(defprotocol TargetThreadDispatcher
  (schedule-on-target-thread!
    [this runnable-fn]
    "Run the no-arg runnable-fn on the runtime's main thread.

    For vanilla MC this is `MinecraftServer.execute`. For
    Spigot single-threaded, `Bukkit.getScheduler().runTask`.
    Modern Paper/Folia use region/entity schedulers per-call.

    Non-blocking from the caller's perspective: the runnable
    queues; the caller returns immediately.

    Returns nil."))

(defprotocol EventRegistryStrategy
  (strategy
    [this]
    "Return one of:
      :direct-transformer  — vanilla MC + future runtimes with no
                              plug-in / listener-bus abstraction;
                              the runtime uses HookSpec install!
                              via the agent's transformer directly.
      :plugin-manager      — Spigot/Paper; event listeners register
                              via `PluginManager.registerEvents`.
      :event-factory       — Fabric; events built via
                              `EventFactory.createArrayBacked`.
    Returns a keyword."))

;; default-adapter storage

(def ^{:doc "Single-cell holder for the runtime-default adapter.
       Replaces the previous volatile!+check-then-set path so two
       concurrent installers race deterministically: compare-and-set
       ensures exactly one non-forced winner; the loser sees nil or
       the existing adapter in the return value."}
  default-state
  (atom {:adapter nil :pending nil}))

(defn- cas-install!
  "compare-and-set installer. Returns the previous adapter on
   success (may be nil); returns :already-installed when the
   default is already set and force? is false."
  [adapter force?]
  (let [prev @default-state]
    (cond
      force?
      (if (compare-and-set! default-state prev
                             (assoc prev :adapter adapter))
        (:adapter prev)
        (recur adapter force?))

      (nil? (:adapter prev))
      (if (compare-and-set! default-state prev
                             (assoc prev :adapter adapter))
        nil
        (recur adapter force?))

      :else :already-installed)))

(defn default-adapter
  "Returns the currently-installed default runtime adapter, or nil
   if none has been registered via `install-default!`."
  []
  (-> @default-state :adapter))

(defn set-default-adapter!
  "Replace the default adapter. Used by `install-default!` after
   the CAS gate. Direct callers should prefer `install-default!`."
  [adapter]
  (swap! default-state assoc :adapter adapter)
  adapter)

(defn install-default!
  "Idempotent: install `adapter` as the default if no default
   exists, OR replace the existing one if `force?` is truthy.
   Returns a structured result map
     `{:previous <prev> :adapter <new> :forced? bool}`.
   Concurrent installers race deterministically — exactly one
   non-forced win; the loser observes the existing adapter via
   the `:previous` field."
  ([runtime-kw adapter] (install-default! runtime-kw adapter false))
  ([runtime-kw adapter force?]
   (let [result (cas-install! adapter force?)
         prev   (if (= result :already-installed)
                  (:adapter @default-state)
                  result)
         new    (:adapter @default-state)
         label  (or (some-> new .getClass .getSimpleName) "<nil>")]
(log/info "install" (if force? "(forced)" "")
            "for" (name runtime-kw) "→" label)
     {:previous prev :adapter new :forced? force?})))
