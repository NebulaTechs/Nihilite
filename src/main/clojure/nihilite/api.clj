(ns nihilite.api
  "Public facade over nihilite.registry — the verbs a user actually
   reaches for at the REPL or in a script.

   The 4-verb surface (`install!`, `uninstall!`, `lookup`,
   `install-status!`) plus the introspection helpers (`list-specs`,
   `swap-bridge!`, `register-action!`) are intentionally thin: every
   function delegates straight to `nihilite.registry`, so contract
   guarantees (id-keyed uniqueness, target/method indexing, atomic
   install/uninstall) are inherited unchanged.

   Spec map shape — see `nihilite.registry/spec` for the constructor
   and the field-level validation. Quick reference:

   | key               | required | meaning                                       |
   | ----------------- | -------- | --------------------------------------------- |
   | :id               | yes      | unique hook identifier (any printable string)  |
   | :target-internal  | yes      | JVM internal name of the target class, e.g.   |
   |                   |          | `\"java/lang/String\"`                          |
   | :method-name      | yes      | method name (string)                          |
   | :descriptor       | no       | raw method descriptor (e.g. `\"()I\"`)          |
   | :position         | yes      | `:entry` / `:return` / `:throw` / `:redefine` |
   | :arity            | no       | expected arg count (informational)            |
   | :bridge           | yes      | 1-arg fn called from the advice/delegator     |
   | :note             | no       | free-form description                         |
   | :action           | no       | `:observe` / `:replace` / `:modify`           |
   |                   |          | (defaults to `:observe`)                       |
   | :tag              | no       | free-form grouping label                      |"
  (:require [nihilite.registry :as reg]))

(defn install!
  "Install a hook spec under `:id`. Atomic: validation, indexing by
   target/method, and (when an `Instrumentation` is registered)
   ByteBuddy retransform all happen under a single lock.

   Throws `ex-info` with `:nihilite/kind :nihilite/missing-id` /
   `:nihilite/missing-target` when the spec is missing required keys,
   and `:nihilite/kind :nihilite/duplicate-spec` when `:id` is already
   registered.

   Returns the registered `HookSpec` record on success.

   Examples:
     (api/install! {:id \"trace-hello\"
                    :target-internal \"java/lang/String\"
                    :method-name \"length\"
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
   id was registered. May log a WARN through `nihilite.registry` when
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
   alphabetically. Empty when the registry is empty."
  []
  (vec (sort (keys (reg/stats-snapshot)))))

(defn install-status!
  "Mark a spec's last install/uninstall event timestamp and return
   the full `StatsRecord`. Returns `nil` if no spec with that id is
   registered."
  [id]
  (reg/install-status! id))

(defn swap-bridge!
  "Atomically replace the bridge fn on the spec with `:id`. Useful for
   hot-patching observed behaviour without uninstalling and reinstalling
   the whole hook. Returns the updated `HookSpec` record."
  [id new-impl]
  (reg/replace-bridge! id new-impl))

(defn register-action!
  "Register a custom action keyword in the registry's action table.
   After registration the keyword can be used in `:action` fields of
   new specs. Returns `true` when the action was newly registered,
   `false` when it was already known."
[action-key]
   (reg/register-action! action-key))
