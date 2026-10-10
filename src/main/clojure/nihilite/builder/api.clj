(ns nihilite.builder.api
  "Public facade over nihilite.builder.registry — the verbs a user actually
   reaches for at the REPL or in a script.

   The 4-verb surface (`install!`, `uninstall!`, `lookup`,
   `install-status!`) plus the introspection helpers (`list-specs`,
   `swap-bridge!`, `register-action!`) are intentionally thin: every
   function delegates straight to `nihilite.builder.registry`, so contract
   guarantees (id-keyed uniqueness, target/method indexing, atomic
   install/uninstall) are inherited unchanged.

   Spec map shape. `install!` normalises the map and returns a HookSpec
   record; the fields it computes rather than copies are `:internal-class`
   and `:method-descriptor`.

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
   | :bridge           | yes      | 1-arg fn called from the advice/delegator     |
   | :note             | no       | free-form description                         |
   | :action           | no       | `:observe` (default) / `:modify` / `:cancel`  |
   |                   |          | / `:subscriber`                               |
   | :tag              | no       | free-form grouping label                      |"
  (:require [nihilite.builder.registry :as reg]))

(defn install!
  "Install a hook spec under `:id`. Atomic: validation, indexing by
   target/method, and (when an `Instrumentation` is registered)
   ByteBuddy retransform all happen under a single lock.

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

