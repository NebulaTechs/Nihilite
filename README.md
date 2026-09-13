# Nihilite

Clojure nREPL agent for running JVM.

## Build

```sh
clojure -T:build uberjar
```

## Run

```sh
java -jar target/nihilite.jar
java -javaagent:target/nihilite.jar -jar target/nihilite.jar
```

Connect to `127.0.0.1:7888` with any bencode nREPL client.

Configuration via `-D` system property or `--key=value` CLI arg:

- `nihilite.bind` (default `127.0.0.1`)
- `nihilite.port` (default `7888`)
- `nihilite.init` — a Clojure form run at startup

## Hooks API

```clojure
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

| key | meaning |
|-----|---------|
| `:id` | unique hook identifier (string) |
| `:target-internal` | JVM internal class name (`"java/io/FileInputStream"`) |
| `:method-name` | method name |
| `:descriptor` | JVM method descriptor (use when the method has overloads) |
| `:position` | `:entry` / `:return` / `:throw` / `:redefine` |
| `:action` | `:observe` (default), `:modify`, `:cancel`, `:subscriber` |
| `:bridge` | `(fn [ctx] ...)`; `ctx` has `:hook-id`, `:self`, `:args`, `:phase`, `:return-value`, `:throwable`, `:cancelled?`, `:cancel!` |

Other verbs:

- `(api/uninstall! id)` — remove hook and retransform
- `(api/lookup id)` — the registered `HookSpec`, or `nil`
- `(api/list-specs)` — all registered ids, sorted
- `(api/install-status! id)` — last install/uninstall event
- `(api/swap-bridge! id new-fn)` — replace the bridge in place
- `(api/register-action! :kw)` — register a custom `:action`

See `examples/jdkstdlib/init.clj` for a complete hook.

## Tests

```sh
clojure -T:build clojure-contract-test   # 115 cases
clojure -T:build check                   # build + verify + all drivers
```

## License

BSD 2-Clause.
