# Nihilite

Clojure nREPL agent for any running JVM, with ByteBuddy hooks into host
classes.

```
./gradlew --no-daemon assemble
./gradlew --no-daemon check
```

## Run

```
java -jar build/libs/nihilite.jar
java -javaagent:build/libs/nihilite.jar -jar build/libs/nihilite.jar
```

Connect: `lein repl :connect 127.0.0.1:7888`.

Configuration (`-D` system property or `--key=value` CLI arg, CLI wins):

- `nihilite.bind` (default `127.0.0.1`)
- `nihilite.port` (default `7888`)
- `nihilite.init` — a Clojure form run at startup. Default `(require 'clojure.repl)`. Use `(load-file "examples/jdkstdlib/init.clj")` for scripts.

## Hooks

```
(require '[nihilite.api :as api])

(api/install!
  {:id              "fis-read"
   :target-internal "java/io/FileInputStream"
   :method-name     "read"
   :descriptor      "([BII)I"
   :position        :return
   :action          :observe
   :bridge          (fn [ctx] ...)
   :note            "..."})
```

`spec` keys:

- `:id` (string, required) — unique id
- `:target-internal` (string, required) — JVM class name, e.g. `"java/io/FileInputStream"`
- `:method-name` (string, required)
- `:position` (`:entry` / `:return` / `:throw` / `:redefine`, required)
- `:descriptor` — JVM method descriptor, e.g. `"([BII)I"`. Use this when the method has overloads.
- `:arity` — parameter count. Alternative to `:descriptor`.
- `:action` — `:observe` (default), `:modify`, `:cancel`, `:subscriber`
- `:bridge` — `(fn [ctx] ...)` where `ctx` has `:hook-id`, `:self`, `:args`, `:phase`, `:return-value`, `:throwable`, `:cancelled?`, `:cancel!`. Convenience accessors `nihilite.registry/ctx-return`, `ctx-throw`, `ctx-self`.
- `:note` — surfaced in `install-status!`

Returns `true` for a fresh install, `false` if replaced.

Other API:

- `(api/uninstall! id)` — returns `true` if removed
- `(api/install-status! id)` — `{:registered? :woven-count :pending? :last-error}`
- `(api/lookup id)` — spec map or nil
- `(api/list-specs)` — sorted ids
- `(api/swap-bridge! id new-fn)` — replace bridge on existing spec
- `(api/register-action! :kw)` — register custom action

See `examples/jdkstdlib/init.clj` for a complete hook.

## Test

```
./gradlew --no-daemon check                 # full
./gradlew --no-daemon clojureContractTest   # Clojure tests
bash scripts/smoke-jdkstdlib.sh             # end-to-end spawn + probe
```

## License

BSD 2-Clause.
