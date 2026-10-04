# Nihilite

A JVM bytecode hook agent written in Clojure, with no `.java` source in the
repo. Mount it with `-javaagent:` and `install!` weaves a bridge function into a
method that is already loaded — at entry, at return, at throw, or as a full
replacement of the method body.

## Build

```sh
clojure -T:build uberjar        # -> target/nihilite.jar
```

## Run

```sh
java -javaagent:target/nihilite.jar -jar your-app.jar
java -Dnihilite.init='(load-file "init.clj")' -javaagent:target/nihilite.jar -jar your-app.jar
```

`-D` properties go **before** `-javaagent`. `nihilite.init` is a Clojure
**form**; a path works too, because `(load-file "init.clj")` is a form.

`java -jar nihilite.jar` prints a stub notice and returns. `Main-Class` is
handed a `nil` `Instrumentation`, so it can never weave — only `-javaagent:` and
a dynamic attach get a real one.

## Hooks

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

`:descriptor` is required; without it `install!` throws
`:nihilite/missing-descriptor`, and one that is not a valid JVM method
descriptor throws `:nihilite/bad-descriptor`. The descriptor decides which loaded
method the hook matches, so a malformed one would otherwise register
successfully and never fire. `:position` is `:entry`, `:return`, `:throw` or
`:redefine`, and `:action` is `:observe` (default), `:modify`, `:cancel` or
`:subscriber`.

`:arity` is optional: the descriptor already says how many parameters the method
takes, so omitting it derives the count, and supplying one that disagrees
throws `:nihilite/arity-descriptor-mismatch` rather than registering a hook
that can never match.

`api/lookup` returns a `HookSpec` whose `:internal-class` and
`:method-descriptor` are computed by `install!` from `:target-internal` and
`:descriptor`. They were called `:source-class` and `:source-descriptor`, which
read as if a hook could target some method other than the one named; the old
names are no longer present, and passing them in had always been silently
overwritten.

The bridge receives a `HookEvent` record — `:spec-id`, `:self`, `:args`,
`:return-value`, `:throwable`, `:cancelled?`, `:cancel!`, `:thread-name`,
`:timestamp-ns`, `:sequence`, `:stack` — except under `:redefine`, which gets
`(self, args, method-name)` and whose return value *becomes* the method's return
value, because the original body does not run at all.

Under `:redefine` and `:modify` a numeric return value is narrowed to the target
method's return type, so `(fn [] 7)` is fine against an `int`-returning method.
A value that does not fit that type is rejected rather than truncated — the
JVM's own narrowing saturates silently, which would change what the woven
method returns with no signal — with `:nihilite/invalid-modify-value` under
`:modify` and `:nihilite/invalid-redefine-value` under `:redefine`, naming the
spec and echoing the descriptor. A value of the wrong shape entirely (a String
against a `MyThing` return) is rejected the same way.

Reference types are not converted, only checked: the bridge must return the
target's declared type or a subtype of it. That covers array returns too, which
are built a dimension at a time because no class name spells `[I`. Build the
value in the bridge — `(fn [_] (int-array 3))` or `(fn [_] (MyThing. x))` —
since there is no numeric-style coercion for a type the Clojure reader has no
opinion about.

`install!` waits for the redefine dispatcher before weaving a `:redefine` hook,
and throws `:nihilite/redefine-dispatcher-unavailable` if it never arrives. That
position replaces the method body, so a hook woven before the dispatcher is
ready would return the stub default instead of the bridge's value. Nothing is
half-registered when it gives up.

| verb | |
| --- | --- |
| `(api/uninstall! id)` | remove the hook and retransform |
| `(api/lookup id)` | the registered `HookSpec`, or nil |
| `(api/list-specs)` | every registered id |
| `(api/install-status! id)` | install-side *and* runtime-side counters |
| `(api/swap-bridge! id f)` | replace the bridge in place |
| `(api/register-action! :kw)` | register a custom `:action` |

`install-status!` reports two sides that never imply each other:

| side | keys |
| --- | --- |
| install | `:registered?` `:woven-count` `:pending?` `:target-loader` `:last-error` |
| runtime | `:fired` `:modified` `:cancelled` `:exceptions` |

`:woven-count 1` means the bytes were rewritten, not that the advice runs. A
`:fired` of 0 is indistinguishable from a dead hook until you know the target
method has actually been called.

## Limits

There is no method that is safe to hook unconditionally. A hook is unsafe
exactly when the advice's own machinery needs the hooked method — the generated
stub resolves its forwarder var by name on every call, the advice dispatches
through hash maps, and it logs.

Measured on JDK 25, each target in its own JVM through the production `install!`
path:

| Target | Outcome |
| --- | --- |
| `java.io.FileOutputStream.write([BII)V` | fires, re-entrancy cut |
| `java.util.BitSet` `isEmpty`/`size`/`cardinality`/`get` | all four positions fire |
| `java.io.FileInputStream.read([BII)I` | classpath-dependent, unreliable |
| `java.lang.Object.hashCode()I` | registers, woven count 1, **never fires** |
| `java.util.zip.ZipFile.getEntry` | registers, woven count 1, **never fires** |
| `java.lang.String.length` / `hashCode`, `Object.equals` | StackOverflowError |
| `java.lang.StringBuilder.append(String)` | JVM assertion failure, then StackOverflowError |
| `java.lang.ClassLoader.loadClass(String)` | `ClassCircularityError` at install time |

"Registers but never fires" is the failure mode to watch: nothing throws and
`install!` reports success. [docs/hook-limits.md](docs/hook-limits.md) has the
measurement, including why `FileInputStream.read` depends on the classpath shape
rather than on the method.

## Evaluating code in an attached JVM

`VirtualMachine.loadAgent` takes one String and returns void, so that String is
the only way in. Nihilite reads it as:

```text
eval:<transport>|<base64-code>
```

`discard`, `file:<path>` or `tcp:<host>:<port>:<token>`. The reply is an EDN map
with `:value` `:out` `:err` `:error` `:session` `:done`:

```java
VirtualMachine vm = VirtualMachine.attach(pid);
vm.loadAgent("target/nihilite.jar",
             "eval:file:/tmp/reply.edn|" + base64("(nihilite.api/list-specs)"));
vm.detach();
```

Inside the target the same code is reachable directly as `nihilite.eval`
(`open-session`, `eval-in`, `snapshot`, `interrupt`, `close-session`) — a
separate namespace from `nihilite.api` on purpose, so requiring one does not
drag in the other. Wire format, polling, session semantics and the interrupt
limitation: [docs/eval-protocol.md](docs/eval-protocol.md).

`eval` is polling, not streaming. For a real REPL, start your own server from
the init script.

## Examples

| Example | Shows |
| --- | --- |
| [`examples/jdkstdlib/init.clj`](examples/jdkstdlib/init.clj) | spec shape against a JDK stdlib method, reached through invokedynamic |
| [`examples/hotrewrite/init.clj`](examples/hotrewrite/init.clj) | `swap-bridge!` hot rewrite |
| [`examples/minecraft/init.clj`](examples/minecraft/init.clj) | an app-loader target that actually fires |
| [`examples/fabric/init.clj`](examples/fabric/init.clj) | Fabric mod-loader hooks |
| [`examples/nrepl_service.clj`](examples/nrepl_service.clj) | bring your own control plane |

```sh
java -Dnihilite.init=examples/jdkstdlib/init.clj -javaagent:target/nihilite.jar -jar your-app.jar
```

Measured: an init script can deploy its own network service, and can put a jar
the agent has never seen onto the classloader and `require` from it —
`DynamicClassLoader` extends `URLClassLoader`, so one `addURL` does what
core.async's `add-libs` does (`add-libs` is not in `clojure.core`).

## Tests

```sh
clojure -T:build check
```

Contract tests plus six drivers that go through a real `Instrumentation`, which
the contract tests cannot reach. What each driver proves and why it asserts what
it does: [docs/drivers.md](docs/drivers.md).

## License

BSD 2-Clause.
