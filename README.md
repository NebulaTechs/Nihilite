# Nihilite

Clojure nREPL agent for running JVMs. Load it with `-javaagent` (or `-jar`,
which also prearms the hook installer) and you get a bencode nREPL on
`127.0.0.1` where `nihilite.api/install!` weaves a hook into any
already-loaded class method.

All bytecode classes are generated from Clojure at runtime / AOT (zero
`.java` source in the repo).

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

Configuration via `-D` system property (placed before `-javaagent` and `-jar`):

- `nihilite.bind` (default `127.0.0.1`)
- `nihilite.port` (default `7888`)
- `nihilite.init` — a Clojure file evaluated at startup

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
| --- | --- |
| `:id` | unique hook identifier (string) |
| `:target-internal` | JVM internal class name (`"java/io/FileInputStream"`) |
| `:method-name` | method name |
| `:descriptor` | JVM method descriptor, required. Without it `install!` throws `:nihilite/missing-descriptor`. |
| `:position` | `:entry` / `:return` / `:throw` / `:redefine` |
| `:action` | `:observe` (default), `:modify`, `:cancel`, `:subscriber` |
| `:bridge` | a Clojure function; see `ctx` shape below |

### `ctx` shape by position

- **`:entry` / `:throw`** — a `HookEvent` record with `:spec-id`, `:source`,
  `:phase`, `:self`, `:args`, `:return-value`, `:throwable`, `:cancelled?`,
  `:cancel!`, `:thread-name`, `:timestamp-ns`, `:sequence`, `:note`,
  `:stack`. Use `nihilite.registry/ctx-return` to read the target method's
  return value.
- **`:return`** — a `HookEvent`. When `:action` is `:modify`, the bridge's
  return value replaces the method's return value.
- **`:redefine`** — three arguments `(self, args, method-name)`, not a `ctx`.
  A `:redefine` hook WRAPS the method so the original body does not run at
  all; the bridge's return value becomes the method's return value.
  Deliberately different from the other positions, which instrument the
  body in place.

### Other verbs

- `(api/uninstall! id)` — remove the hook and retransform
- `(api/lookup id)` — the registered `HookSpec`, or `nil`
- `(api/list-specs)` — all registered ids, sorted
- `(api/install-status! id)` — the most recent install/uninstall event,
  including `:woven-count` and `:target-loader`
- `(api/swap-bridge! id new-fn)` — replace the bridge in place
- `(api/register-action! :kw)` — register a custom `:action`

## Examples

| Example | What it shows |
| --- | --- |
| [`examples/jdkstdlib/init.clj`](examples/jdkstdlib/init.clj) | Spec shape against a JDK stdlib method. Bootstrap target, reached through invokedynamic. |
| [`examples/hotrewrite/init.clj`](examples/hotrewrite/init.clj) | `swap-bridge!` hot rewrite on a bootstrap class. |
| [`examples/minecraft/init.clj`](examples/minecraft/init.clj) | Vanilla Minecraft `MinecraftServer.sendSystemMessage` observer. App-loader target — actually fires. |
| [`examples/fabric/init.clj`](examples/fabric/init.clj) | Fabric mod-loader hooks. App-loader target — actually fires. |

Load any example via `-Dnihilite.init=examples/<name>/init.clj`.

## Tests

```sh
clojure -T:build clojure-contract-test   # 140 cases
clojure -T:build check                   # build + verify + all drivers
```

`check` runs five drivers that exercise the real `Instrumentation` path
the contract tests cannot reach: `retransform` (all four positions on an
already-loaded class, including a co-located `:entry` surviving a
`:redefine` uninstall), `jar-smoke` (agent deploys and the nREPL server
comes up in a spawned `java -jar` process), `redefine-instance`, `indy`
(an invokedynamic call site woven into a bootstrap-loader method
actually firing), and `prod-bootstrap` (all four positions installing
and firing on a bootstrap-loader class through the production
`install!` path).

## License

BSD 2-Clause.
