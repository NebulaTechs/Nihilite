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
  (:require [nihilite.eval :as eval]
            [nihilite.registry :as reg]
            [nihilite.registry.stats :as stats]))

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
  (vec (sort (keys (stats/stats-snapshot)))))

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

;;; ------------------------------------------------------------------- eval

(defn open-session
  "Opens an eval session and returns its id.

   A session evaluates in its own namespace and keeps it, so `(def x 1)` is
   still there for the next `eval-in`. Two sessions cannot see each other.

   Sessions are how out-of-process code drives this JVM. See
   `nihilite.eval.protocol` for the wire format an attaching process uses,
   and examples/nrepl_service.clj for starting a full REPL from an init
   script instead."
  []
  (eval/open-session))

(defn eval-in
  "Starts evaluating `code` in the session and returns an eval id right away.

   Asynchronous on purpose: the usual caller is an attacher holding a
   `loadAgent` call open, and a form that never returns must not wedge it.
   Poll `snapshot` for the result.

   Returns nil when the session id is unknown."
  [session-id code]
  (eval/eval-in session-id code))

(defn snapshot
  "Reads a session's output and state: `:events` (one ordered log, tagged
   `:out` or `:err`, each with a `:seq`), `:cursor`, `:value`, `:error`,
   `:running?` and `:ns`.

   Pass the `:cursor` from the previous read as the second argument to get
   only what is new since then."
  ([session-id] (eval/snapshot session-id))
  ([session-id since] (eval/snapshot session-id since)))

(defn interrupt!
  "Asks the session's running eval to stop.

   This is `Thread.interrupt`: it unblocks a thread waiting on I/O, sleep or
   a monitor. It cannot stop a tight `(loop [] (recur))`, because Clojure's
   `recur` never checks the interrupt flag and `Thread.stop` was removed in
   JDK 20."
  [session-id]
  (eval/interrupt session-id))

(defn close-session!
  "Drops a session and interrupts anything still running in it. Returns true
   when the session existed."
  [session-id]
  (eval/close-session session-id))
