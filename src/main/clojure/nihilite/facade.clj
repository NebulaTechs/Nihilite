(ns nihilite.facade
  "User-facing runtime API surface (multimethod-dispatched by
   runtime keyword).

   Per-runtime installations (e.g. `examples/minecraft/init.clj`)
   add `defmethod` implementations under their runtime keyword.
   User code in the REPL calls
   `(nihilite.facule/<m> :<runtime> arg1 arg2)` and gets the
   runtime-specific dispatch.

   Multimethod (not protocol) because runtime identity is the
   dispatch value, and runtimes are open-ended: operators can add
   a new `:my-runtime` keyword at any time without touching this
   ns or the Java shell.

    v0 ships the `:minecraft` defmethods only — see
    `examples/minecraft/init.clj`. Other runtimes land as future
    `examples/<runtime>/init.clj` files.

   The forward-path for Minecraft is:
     (nihilite.facade/send-system-message!
       (nihilite.facade/get-server-instance :minecraft)
       \"hi\")

   No bytecode hook is involved. Hooks remain available as opt-in
   observability (e.g. for hot-swappable handlers via
   alter-var-root); they are NOT the default forward path."
  (:require [clojure.core :as core]))

(defmulti get-server-instance
  "Return the runtime's main server instance.

   Args:
     runtime   keyword identifying the runtime (e.g. :minecraft)
     opts      optional map of runtime-specific options (forward-compat)

   Returns the runtime's root server/instance object. Each runtime
   implements this for its specific framework (vanilla MC: the
   MinecraftServer singleton; Spigot/Paper: the CraftServer
   delegate; Fabric: the MinecraftServer accessor; etc.)."
  (fn [runtime & _] runtime))

(defmulti send-system-message!
  "Send a system message via the runtime's MC-equivalent.

   Args:
     runtime   keyword identifying the runtime
     inst      the runtime's server instance from get-server-instance
     msg       string message body

   Routes to the runtime's main thread (so it's safe to call from
   any REPL thread). Returns nil. Each runtime implements this
   for its specific framework."
  (fn [runtime & _] runtime))

(defmulti list-players
  "Return a vector of player objects in the runtime.

   Args:
     runtime   keyword identifying the runtime
     inst      the runtime's server instance

   Each runtime returns a normalized vector (always a vector, not
   a list — REPL ergonomics)."
  (fn [runtime & _] runtime))

(defmulti schedule-on-target-thread!
  "Schedule a no-arg fn on the runtime's main thread.

   Args:
     runtime   keyword identifying the runtime
     inst      the runtime's server instance
     fn        no-arg thunk to run on the runtime's main thread

   Each runtime implements its dispatcher:
     - vanilla MC:        (.execute ^MinecraftServer server ^Runnable r)
     - Spigot single:     (.runTask ^BukkitTask shceduler ^Runnable r)
     - Paper regionised:   (.execute ^RegionScheduler schd ^Runnable r)
     - Folia:              region/entity scheduler (per region/thread)
     - Generic:            any ExecutorService.submit

   Returns nil. Caller blocks zero time."
  (fn [runtime & _] runtime))
