# Nihilite

Clojure agent for weaving hooks into running JVMs. Load it with
`-javaagent` and `nihilite.api/install!` weaves a hook into any already-loaded
class method.

**Nihilite opens no port.** It used to ship an embedded bencode nREPL server
on `127.0.0.1:7888`; that is gone, along with the `nrepl` dependency. What
replaced it is the choice the server was taking away:

- **Want an interactive control plane?** Start your own from the init script.
  See `examples/nrepl_service.clj` — it puts nrepl back on the classpath from
  an init script and binds it to loopback. Bring cider-nrepl too if you want
  Calva; that middleware was never shipped here, which is why an editor
  refused to talk to the old built-in server.
- **Just want to evaluate something in an attached JVM?** Use the eval
  protocol below, or `nihilite.api/eval-in` from inside the target.

All bytecode classes are generated from Clojure at runtime / AOT (zero
`.java` source in the repo).

## Build

```sh
clojure -T:build uberjar
```

## Run

```sh
java -javaagent:target/nihilite.jar -jar your-app.jar
java -Dnihilite.init='(load-file "init.clj")' -javaagent:target/nihilite.jar -jar your-app.jar
```

Configuration via `-D` system property (placed before `-javaagent` and `-jar`):

- `nihilite.init` — a Clojure **form** evaluated at startup. A path works
  too, because `(load-file "init.clj")` is a form.

There is deliberately no port setting any more: nothing binds.

## Evaluating code in an attached JVM

`VirtualMachine.loadAgent` takes one String and returns void, so that String
is the only way in and there is no way back. Nihilite's answer is a small
wire format that any language can implement:

```text
eval:<transport>|<base64-code>
```

| transport | effect |
| --- | --- |
| `discard` | run it, reply nowhere |
| `file:<path>` | reply written to that file as EDN |
| `tcp:<host>:<port>:<token>` | reply written to a socket, token echoed first |

Agent args without the `eval:` prefix are not an eval request, so an existing
invocation behaves exactly as it did. The reply is one EDN map with `:token`,
`:session`, `:ns`, `:value`, `:error`, `:out`, `:err` and `:done`.

So a complete round trip from another process is:

```java
VirtualMachine vm = VirtualMachine.attach(pid);
vm.loadAgent("target/nihilite.jar",
             "eval:file:/tmp/reply.edn|" + base64("(nihilite.api/list-specs)"));
vm.detach();
// /tmp/reply.edn now holds {:value "{...}", :done true, ...}
```

`:done false` means the eval was still running when the wait expired; the
`:session` in the reply is live and can be polled by sending a second request
whose code is `(nihilite.eval/snapshot "<session-id>")`. One attach round
trip per read is the price of `loadAgent` having no return channel — if you
want cheaper reads, start a real service from your init script instead.

## Hooks API

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

### Eval verbs

These are the in-process side of the wire format above; a process on the other
side of `loadAgent` reaches the same code.

- `(api/open-session)` — open a session, returns its id. Each session keeps
  its own namespace, so `(def x 1)` survives into the next `eval-in` and is
  invisible to other sessions
- `(api/eval-in sid code)` — start evaluating, returns an eval id **immediately**.
  Asynchronous on purpose: the usual caller is an attacher holding a
  `loadAgent` open, and a form that never returns must not wedge it
- `(api/snapshot sid)` / `(api/snapshot sid since)` — output and state.
  `:events` is one ordered log, each entry tagged `:out` or `:err` with a
  `:seq`, so the interleaving of the two streams is recorded rather than
  reconstructed. Pass the previous `:cursor` as `since` to read incrementally
- `(api/interrupt! sid)` — `Thread.interrupt`. It unblocks a thread waiting on
  I/O, sleep or a monitor. It cannot stop a tight `(loop [] (recur))`: Clojure's
  `recur` never checks the interrupt flag, and `Thread.stop` was removed in
  JDK 20
- `(api/close-session! sid)` — interrupt anything running, then forget it

## Examples

| Example | What it shows |
| --- | --- |
| [`examples/jdkstdlib/init.clj`](examples/jdkstdlib/init.clj) | Spec shape against a JDK stdlib method. Bootstrap target, reached through invokedynamic. |
| [`examples/hotrewrite/init.clj`](examples/hotrewrite/init.clj) | `swap-bridge!` hot rewrite on a bootstrap class. |
| [`examples/minecraft/init.clj`](examples/minecraft/init.clj) | Vanilla Minecraft `MinecraftServer.sendSystemMessage` observer. App-loader target — actually fires. |
| [`examples/fabric/init.clj`](examples/fabric/init.clj) | Fabric mod-loader hooks. App-loader target — actually fires. |
| [`examples/nrepl_service.clj`](examples/nrepl_service.clj) | Bring your own control plane: puts nrepl back on the classloader from an init script and binds it to loopback. |

Load any example via `-Dnihilite.init=examples/<name>/init.clj`.

Measured: an init script CAN deploy its own network service, and CAN put a jar
the agent has never seen onto the classloader and `require` from it.
`clojure.lang.DynamicClassLoader` extends `URLClassLoader`, so one `addURL`
does what core.async's `add-libs` does — `add-libs` itself is not in
`clojure.core`. Note that inside the init eval context `Compiler/LOADER`
reads back as a `clojure.lang.Var`, so `deref` it (or use `RT/baseLoader`);
the thread context classloader is an `AppClassLoader` and `addURL` does not
apply there. `examples/nrepl_service.clj` shows the working shape, and
`nihilite.test.init-service-driver` measures it end to end from another
process.

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
clojure -T:build clojure-contract-test   # 187 cases
clojure -T:build check                   # build + verify + all drivers
```

`check` runs five drivers that exercise the real `Instrumentation` path
the contract tests cannot reach: `retransform` (all four positions on an
already-loaded class, including a co-located `:entry` surviving a
`:redefine` uninstall, and the reentrancy guard cutting a bridge that
re-enters its own target), `jar-smoke` (a spawned `java -javaagent:` process, with
no server anywhere: the init form has to evaluate, the hook has to report a
non-zero `:woven-count` **and** fire, and the child's log must contain no
bridge or transformer errors), `redefine-instance`, `indy` (an invokedynamic
call site woven into a bootstrap-loader method actually firing), and
`prod-bootstrap` (all four positions installing and firing on a
bootstrap-loader class through the production `install!` path).

`jar-smoke` uses `-javaagent:` rather than `-jar` on purpose. Weaving needs a
real `Instrumentation`, and only the agent entry points get one — `java -jar`
reaches `Main-Class`, which is handed `nil`, so nothing is ever woven and every
hook sits at `:pending? true` forever. `-javaagent` is also the deployment this
project documents, and the one where `-Dnihilite.init` used to be dead.

`prod-bootstrap` also prints a `REENTRY_STATUS` line: what a hook on
`java.io.FileInputStream.read` did when its bridge re-entered the target.
That number varies between runs and is reported, not asserted — see Limits.

`clojure -T:build hostile-target-driver` is separate and is not part of
`check`: it is the characterisation pass behind the Limits table, and it
asserts nothing — some of those targets are unsafe on purpose.

## License

BSD 2-Clause.
