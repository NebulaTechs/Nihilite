(ns nihilite.builder.api
  "Public facade over nihilite.builder.registry — the verbs a user actually
   reaches for at the REPL or in a script.

   The public surface is ten verbs: `install!`, `uninstall!`, `lookup`,
   `install-status!`, `list-specs`, `swap-bridge!`, `register-action!`,
   `registered-actions`, `unregister-action!`, and `capability-report`.
   They are intentionally
   thin: every function delegates straight to `nihilite.builder.registry`, so
   contract guarantees (id-keyed uniqueness, target/method indexing, atomic
   install/uninstall) are inherited unchanged. `capability-report` is the one
   that does not: it reads the JVM's own state and has no registry
   counterpart, because it answers a different question -- what the JVM
   can do, rather than what a hook did.

   Spec map shape. `install!` normalises the map and returns `true` when the
   id was newly registered, `false` when it replaced an existing spec. The
   stored spec computes `:internal-class` and `:method-descriptor` rather
   than copying them.

   | key               | required | meaning                                       |
   | ----------------- | -------- | --------------------------------------------- |
   | :id               | yes      | unique hook identifier (any printable string)  |
   | :target-internal  | yes      | JVM internal name of the target class, e.g.   |
   |                   |          | `\"java/lang/String\"`                          |
   | :method-name      | yes      | method name (string)                          |
   | :descriptor       | yes      | raw method descriptor, e.g. `\"()I\"`          |
   | :position         | no       | `:entry` / `:return` / `:throw` / `:redefine` |
   |                   |          | (defaults to `:entry`)                        |
   | :arity            | no       | expected arg count; derived from the          |
   |                   |          | descriptor when omitted, and rejected when it |
   |                   |          | disagrees with it                             |
    | :bridge           | yes      | 1-arg fn called from the advice/delegator. Not |
    |                   |          | validated: a spec without one registers and    |
    |                   |          | never fires.                                   |
   | :note             | no       | free-form description                         |
   | :action           | no       | `:observe` (default) / `:modify` / `:cancel`  |
   |                   |          | / `:subscriber`                               |
   | :tag              | no       | free-form grouping label                      |"
  (:require [nihilite.builder.registry :as reg]
            [nihilite.builder.capability :as cap]))

(defn install!
  "Install a hook spec under `:id`. The registry mutation is atomic:
   indexing by target/method and (when an `Instrumentation` is registered)
   the ByteBuddy retransform happen under a single lock, and a failure
   during the retransform puts the registry back the way it was, so a spec
   is either fully indexed and woven or it was never there. Validation runs
   before the lock is taken: it reads the spec and the JVM's own class
   list, never the registry, so there is nothing in it to race with.

   Re-installing an id that is already registered REPLACES it: the previous
   spec leaves the target and method indexes, the class is retransformed so
   the new bridge reaches the JVM, and the return value is `false` (a
   `true` means the id was new). The counter record is kept across the
   replacement, so `:fired` does not restart.

   Returns `true` when the id was newly registered, `false` when it replaced
   an existing spec. Use `lookup` for the record.

   Throws `ex-info` when the spec is missing required keys
   (`:nihilite/missing-id` / `:nihilite/missing-target` /
   `:nihilite/missing-method` / `:nihilite/missing-descriptor`) or carries a
   value the registry rejects (`:nihilite/bad-descriptor`,
   `:nihilite/bad-arity`, `:nihilite/bad-tag`,
   `:nihilite/arity-descriptor-mismatch`, `:nihilite/invalid-action`,
   `:nihilite/invalid-position`, and the per-position action rules).
   `nihilite.builder.registry/install-fresh!` is the variant that refuses a
   duplicate id instead of replacing it, throwing
   `:nihilite/duplicate-spec-id`.

   THE TARGET METHOD IS CHECKED, WHEN THE JVM CAN ANSWER. The registry
   already checks the SHAPE of a spec -- the descriptor is a well-formed JVM
   method descriptor, the arity agrees with it, the action and the position
   are legal. Shape is not existence. A spec naming a method that does not
   exist, or that exists with a descriptor it does not have, is rejected with
   `:nihilite/target-method-not-found` or
   `:nihilite/target-descriptor-mismatch` before it is registered. Such a
   spec would otherwise register, report a woven count, and never fire --
   the \"registers but never fires\" mode docs/hook-limits.md documents,
   reached by a route the reader would not expect because everything about
   the spec looked right.

   The check only runs when the target class is ALREADY LOADED, and it
   never loads a class to find out -- resolving a name means loading the
   class, and a hook installed to observe a class-loading path re-enters
   its own advice when the verification does it. So a spec whose target
   class has not been loaded yet is accepted, and the target may load
   later. A JVM with no `Instrumentation` has no class list to read, so
   the check cannot run there either.

    Examples:
      (api/install! {:id \"trace-bitset-size\"
                     :target-internal \"java/util/BitSet\"
                     :method-name \"size\"
                     :descriptor \"()I\"
                     :position :entry
                     :bridge   (fn [ctx] (println :entry ctx))})"
  [spec]
  (reg/install! spec))

(defn uninstall!
  "Remove the hook spec with the given `:id` from the registry and
   ask the installer to retransform the target class so ByteBuddy
   drops its advice/delegator wiring.

   Returns `true` on successful removal, `nil` when no spec with that
   id was registered. May log a WARN through `nihilite.builder.registry` when
   retransform finds no loaded class (typical in tests where the
   agent is not armed).

   Throws `ex-info` with `:nihilite/kind :nihilite/uninstall-failed`
   if the retransform step itself throws (e.g. unsupported
   `ClassLoader`). The spec is still removed from the registry in
   that case — the throw is informational."
  [id]
  (reg/uninstall! id))

(defn lookup
  "Return the registered `HookSpec` record for `:id`, or `nil` when
   no such hook is registered.

   Note that the record is a snapshot: mutating it in-place has no
   effect on the registry. Use `swap-bridge!` to replace a bridge
   function safely."
  [id]
  (reg/lookup id))

(defn list-specs
  "Return a vector of all currently registered hook ids, sorted
   alphabetically. Empty when the registry is empty.

   Reads the registry, not the counter table. The two are not the same
   thing: `uninstall!` drops a counter record as part of removal, but the
   registry is what `lookup` answers from, so this has to agree with that
   rather than with a side table."
  []
  (reg/list-ids))

(defn install-status!
  "Report a spec's install-side and runtime-side status.

   Install side: :registered?, :woven-count, :pending?, :target-loader,
   :last-error -- what the agent did to the already-loaded classes.
   Runtime side: :fired, :modified, :cancelled, :exceptions -- what the
   advice has actually done since install.

   Neither side implies the other, so both are returned: a :woven-count of 1
   means the bytes were rewritten, not that the advice runs, and a :fired of 0
   means no bridge has run yet, which is also what a permanently dead hook
   looks like. Check :fired only after the target method has actually been
   called."
  [id]
  (reg/install-status! id))

(defn capability-report
  "What this JVM can do, and what Nihilite has done to it.

   install-status! answers \"what happened to the hook I installed\". This
   answers the questions around it: is the agent actually armed, and can
   this JVM do instrumentation at all. A hook reporting :woven-count 0 and
   :pending? true looks the same whether the agent is missing, the target
   class is not loaded, or the JVM does not support redefinition, and those
   three need three different fixes.

   The report is READ-ONLY. Reading it installs no agent, attaches to
   nothing, and retransforms no class. A diagnostic that changes the thing
   it diagnoses gives an answer about a different JVM than the one asked
   about.

   The keys, in the order they are usually read:

     :instrumentation-present?      whether an Instrumentation is registered
     :entry-point                   :premain, :agentmain, or nil
     :redefine-classes-supported?   what the Instrumentation reports
     :retransform-classes-supported? what the Instrumentation reports
     :java-version, :java-vendor, :vm-name, :os-name, :os-arch
     :java-instrument-module-present? whether java.instrument is in the image
     :jdk-attach-present?           whether com.sun.tools.attach is reachable
     :attach-provider-count         how many AttachProviders the JVM offers
     :relevant-jvm-arguments        the arguments that bear on instrumentation
     :detail                        a one-line summary
     :remediation                   what to do, or nil

   :remediation is the field to read when something does not work: it says
   which of the possible fixes applies, rather than leaving the reader to
   work out whether the agent was never started, cannot be attached to, or
   is running on a JVM that cannot redefine classes."
  []
  (cap/capability-report))

(defn swap-bridge!
  "Replace the bridge fn on the spec with `:id`, in place. Useful for
   hot-patching observed behaviour without uninstalling and reinstalling the
   whole hook.

   Only `:bridge` changes: `:action`, `:position` and the rest of the spec are
   untouched, and the class is not retransformed, because the shape a
   transform matches on has not changed. The already-woven call site is the
   point — the next call runs the new bridge.

   Returns `true` when the spec was found and rewritten, `false` when no
   spec has that id."
  [id new-impl]
  (reg/replace-bridge! id new-impl))

(defn register-action!
  "Register a custom action keyword so it can be used in the `:action` field
   of new specs. Returns the keyword.

   A registered keyword still has to mean something: `install!` validates
   what it does at the spec's `:position`, and an action the dispatch layer
   does not recognise simply runs as `:observe`. Throws
   `:nihilite/invalid-action-key` when `action-key` is not a keyword."
  [action-key]
  (reg/register-action! action-key))

(defn registered-actions
  "Every action keyword `install!` will accept: the four built-ins plus
   whatever `register-action!` has added, as a set."
  []
  (reg/registered-actions))

(defn unregister-action!
  "Removes an action previously added by `register-action!`, so specs naming
   it stop being accepted. Returns true when one was there to remove and false
   when it was not — so this is safe to call twice.

   Throws `:nihilite/builtin-action` for `:observe`, `:modify`, `:cancel` and
   `:subscriber`. Those cannot be taken back: the dispatch layer branches on
   them whether or not this registry lists them, so removing one would leave
   `install!` rejecting specs whose `:action` the runtime still honours. To
   stop a hook from cancelling, change its `:action`."
  [action-key]
  (reg/unregister-action! action-key))

