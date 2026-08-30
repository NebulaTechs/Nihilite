# Nihilite

Attach a Clojure nREPL to any running JVM, with a ByteBuddy control plane
for weaving method-level hooks into host classes.

Two entry points on the same fat jar:

- `java -jar nihilite.jar` — standalone nREPL server, no host instrumentation
- `-javaagent:nihilite.jar` — attaches the REPL + the ByteBuddy installer

Default bind: `127.0.0.1:7888`. Unauthenticated. Loopback only.

## On-ramp (60 seconds)

```sh
# Build
./gradlew --no-daemon assemble

# Run the Minecraft Vanilla example against a 26.1 server jar
java -javaagent:build/libs/nihilite.jar \
     -Dnihilite.init=examples/minecraft/init.clj \
     -jar path/to/server-26.1.jar

# From a second terminal, connect a Clojure-aware editor
;; M-x cider-connect → 127.0.0.1 → 7888
```

The agent attaches the nREPL, weaves `MinecraftServer.sendSystemMessage`
with the bridge from `examples/minecraft/init.clj`, and exposes all
installed specs via `(nihilite.api/list-specs)`.

## Build

JDK 25.

```sh
./gradlew --no-daemon assemble
```

Artifact: `build/libs/nihilite.jar`. Version resolves from the jar
manifest (`Package.getPackage("nihilite.server")`); falls back to a
build-timestamp hardcoded value when running from source.

## Verify

```sh
./gradlew --no-daemon check
```

Three gates, all required:

- `clojureContractTest` — Clojure contract tests across the public API
- `retransformDriver`   — in-process driver that proves ByteBuddy
  actually weaves `:entry` / `:return` / `:redefine` advice on a
  retransformed class
- `verifyJarContents`   — the jar must not bundle `examples/`

## Public API

Five verbs, one namespace: `nihilite.api`.

```clojure
(require '[nihilite.api :as api])

(api/install! {:id              "my-signal"
               :target-internal "com/example/Host"
               :method-name     "doWork"
               :descriptor      "(I)Ljava/lang/String;"
               :arity           1
               :position        :return      ; :entry | :return | :throw | :redefine
               :action          :modify      ; :observe | :modify | :cancel | :subscriber
               :bridge          (fn [ctx]
                                  (let [orig (:returnValue ctx)]
                                    (str orig " (rewritten)")))})

(api/uninstall! "my-signal")
(api/lookup "my-signal")
(api/list-specs)         ; => ["my-signal" ...]
```

Hot-swap the bridge without uninstalling the spec:

```clojure
(api/swap-bridge! "my-signal" (fn [ctx] ...new impl...))
```

Four phases:

- `:entry`    — fires before the host method runs; observe / cancel
- `:return`   — fires on normal exit; observe / modify the return value
- `:throw`    — fires on exception; observe
- `:redefine` — replaces the method body with the bridge fn

For `:return` / `:entry` the bridge takes one arg: the context map.
For `:redefine` the bridge takes `(args method-name)`.

## Init files

Init files are operator-side; they are not packaged. Tracked examples:

- `examples/minecraft/init.clj` — Minecraft Vanilla hook on
  `MinecraftServer.sendSystemMessage` (97 lines, real)
- `examples/fabric/init.clj`    — Fabric loader example demonstrating
  `:entry` on `runServer` and `:return` modify on `sendSystemMessage`
  (102 lines, scaffolding)

```sh
java -jar nihilite.jar -Dnihilite.init=examples/minecraft/init.clj
java -javaagent:nihilite.jar -Dnihilite.init=examples/minecraft/init.clj -jar host.jar
```

If your host class only exists after main() runs, register a
`nihilite.boot/set-ready!` thunk; the worker thread blocks on it
before binding the nREPL port.

## Limits

- Hooks only fire on classes the JVM's Instrumentation can retransform.
  If a class was loaded by a child classloader that the agent can't
  reach, the hook is silent.
- `:redefine` replaces the method body. It does not call the original.
- `uninstall!` walks `Instrumentation.getAllLoadedClasses()` and
  retransforms matching loaded classes; classes not yet loaded are
  skipped silently.
- No auth. Loopback bind only.
- No other transports. nREPL bencode over one port.
- `swap-bridge!` is uninstall+reinstall; brief instrumentation gap is
  accepted (see `nihilite.api/swap-bridge!`).

## License

BSD 2-Clause.
