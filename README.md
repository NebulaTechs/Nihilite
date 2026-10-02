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
- `(api/install-status! id)` — both sides of a hook's state:

  | side | keys | means |
  | --- | --- | --- |
  | install | `:registered?` `:woven-count` `:pending?` `:target-loader` `:last-error` | what the agent did to the already-loaded classes |
  | runtime | `:fired` `:modified` `:cancelled` `:exceptions` | what the advice has actually done since |

  Neither side implies the other, which is why both are reported. A
  `:woven-count` of 1 means the bytes were rewritten, not that the advice runs.
  A `:fired` of 0 means no bridge has run yet — indistinguishable from a dead
  hook, so only read it once the target method has demonstrably been called.
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

## Limits

There is no method that is safe to hook unconditionally. A hook is unsafe
exactly when the advice's own machinery needs the hooked method: the generated
advice stub resolves its forwarder var by name on every call, the advice
dispatches through hash maps, and it logs — so anything on that path
re-enters the advice. That cycle closes *above* the reentrancy guard, before
the advice body is ever entered, which is why the guard cannot save you here.

Measured on JDK 25, each hooked through the production `install!` path in its
own JVM (`clojure -T:build hostile-target-driver`, one target per JVM via
`HOSTILE_TARGET_INDEX`):

| Target | Outcome |
| --- | --- |
| `java.io.FileOutputStream.write([BII)V` | fires, re-entrancy cut |
| `java.util.BitSet` `isEmpty`/`size`/`cardinality`/`get` | all four positions fire |
| `java.io.FileInputStream.read([BII)I` | classpath-dependent — see below |
| `java.lang.Object.hashCode()I` | registers, reports woven count 1, **never fires** |
| `java.util.zip.ZipFile.getEntry` | registers, reports woven count 1, **never fires** |
| `java.lang.String.length()I` | StackOverflowError |
| `java.lang.String.hashCode()I` | StackOverflowError |
| `java.lang.Object.equals(Object)` | StackOverflowError |
| `java.lang.StringBuilder.append(String)` | JVM assertion failure in `libinstrument`, then StackOverflowError |
| `java.lang.ClassLoader.loadClass(String)` | `ClassCircularityError` at install time |

Three things to take from that table. "Registers but never fires" is the
failure mode to watch for: `install!` returns a woven count and nothing throws,
so the jar-smoke driver asserts a real firing on every run.

`FileInputStream.read` is the interesting one, and it is what exposed a real
defect in this API. Its outcome is a function of the classpath shape, not of
the method. It is the byte path by which a `.class` or `.clj` file on the
classpath becomes a `Class` (`BuiltinClassLoader.findClassOnClassPathOrNull` →
`Resource.getBytes` → `read`), while JDK classes come from the module image and
jar entries come through `ZipFile` + `Inflater`. Measured, same code:

| Classpath | Outcome |
| --- | --- |
| directories (`src/main/clojure`, `target/classes`) | registers, never fires (4/4); `StackOverflowError` seen on other runs |
| jar only (`target/nihilite.jar` + `target/test-classes`) | fires every run, 1–2 calls, no overflow |

In the never-fires case the advice is never entered at all: a counter at the
first line of `nihilite.kernel.advice/hk-onEntry` stays at 0 across every run,
while the hooked method returns bytes normally and `install!` reports a woven
count of 1. The call site is woven but its invokedynamic is never linked, so
the bytes are rewritten and the advice never runs. This is the same silent
shape recorded when the BitSet `redefine` bridge was fixed in `26ad354`.

Neither the JVM's nor this API's behaviour is reliable here. A hook on
`FileInputStream.read` cannot carry a test, let alone an example.

## Tests

```sh
clojure -T:build clojure-contract-test   # 164 cases
clojure -T:build check                   # build + verify + all drivers
```

`check` runs five drivers that exercise the real `Instrumentation` path
the contract tests cannot reach: `retransform` (all four positions on an
already-loaded class, including a co-located `:entry` surviving a
`:redefine` uninstall, and the reentrancy guard cutting a bridge that
re-enters its own target), `jar-smoke` (a spawned `java -jar` process where a
production-path hook has to fire and the child's log must contain no bridge
or transformer errors), `redefine-instance`, `indy` (an invokedynamic call
site woven into a bootstrap-loader method actually firing), and
`prod-bootstrap` (all four positions installing and firing on a
bootstrap-loader class through the production `install!` path).

`prod-bootstrap` also prints a `REENTRY_STATUS` line: what a hook on
`java.io.FileInputStream.read` did when its bridge re-entered the target.
That number varies between runs and is reported, not asserted — see Limits.

`clojure -T:build hostile-target-driver` is separate and is not part of
`check`: it is the characterisation pass behind the Limits table, and it
asserts nothing — some of those targets are unsafe on purpose.

## License

BSD 2-Clause.
