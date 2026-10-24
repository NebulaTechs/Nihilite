# Drivers

`clojure -T:build check` runs one contract test and 18 driver passes in this
order:

```clojure
:check-order [clojure-contract-test
             weaving-contract-driver
             retransform-driver
             javaagent-driver
             eval-attach-driver
             eval-jar-driver
             examples-jdkstdlib-driver
             examples-hotrewrite-driver
             examples-positions-driver
             examples-lifecycle-driver
             examples-actions-driver
             examples-attach-eval-driver
             redefine-instance-driver
             indy-driver
             prod-bootstrap-driver
             dual-loader-driver
             concurrent-invoke-driver
             multi-hook-fanout-driver
             hostile-safe-driver]
```

The drivers exist because the contract test runs in-process and cannot reach a
real `Instrumentation`. The two failure modes this project has actually
suffered, weaving that silently never fires and an install that reports success
while the agent is half-built, are both invisible from inside the contract
test.

| driver | proves |
| --- | --- |
| `weaving-contract-driver` | the registry agrees with the JVM after a fresh install, an uninstall and reinstall, and a `clear!` and reinstall under a new id; the bridge's own counter is the ground truth for `:fired` |
| `retransform-driver` | all four positions on an already-loaded class; a co-located `:entry` survives a `:redefine` uninstall; the reentrancy guard cuts a bridge that re-enters its own target |
| `javaagent-driver` | the agent classpath shape: the system loader is a `URLClassLoader`, worker-equivalent concurrent requires succeed, and a file-transport eval request round-trips |
| `eval-attach-driver` | a second process attaching to that child and driving `loadAgent` with `eval:` requests: value, `*out*`, thrown error, `:done false`, poll, interrupt, close, and a hook installed over the channel that then fires |
| `eval-jar-driver` | the eval stack loads and runs from the uberjar alone, with source directories, class directories, and the local Maven basis off the classpath |
| `examples-jdkstdlib-driver` | the packaged agent runs the `jdkstdlib` example in a child JVM, and its `FileOutputStream.write` hook weaves, fires, and records no exception |
| `examples-hotrewrite-driver` | the packaged agent runs the `hotrewrite` example in a child JVM, and `swap-bridge!` sends consecutive `BitSet.size` calls to bridge v1 and then bridge v2 |
| `examples-positions-driver` | the packaged agent runs the `positions` example in a child JVM, and `:entry`, `:return`, `:throw`, and `:redefine` fire with their distinct bridge shapes |
| `examples-lifecycle-driver` | the packaged agent runs the `lifecycle` example in a child JVM, and install, fire, status inspection, bridge swap, uninstall, and public lookup/list cleanup all succeed |
| `examples-actions-driver` | the packaged agent runs the `actions` example in a child JVM, and `:observe`, `:modify`, `:cancel`, and `:subscriber` produce their distinct observable results and counters |
| `examples-attach-eval-driver` | a plain target JVM with no Nihilite can be attached to at runtime, then returns the expected value through the file-backed eval protocol and closes its session |
| `redefine-instance-driver` | an instance method redefined such that the body captures `self` via `@This` |
| `indy-driver` | an invokedynamic call site woven into a bootstrap-loader method actually firing |
| `prod-bootstrap-driver` | all four positions installing and firing on a bootstrap-loader class through the production `install!` path, with the worker itself supplying the redefine dispatcher |
| `dual-loader-driver` | one class name under two ClassLoaders: a single spec serves both, `:woven-count` reports how many loaded Classes share the name rather than how many hooks exist, and nothing records which loader an event came from |
| `concurrent-invoke-driver` | 8 threads in one advice body: Nihilite's `:fired` atom and `:sequence` `AtomicLong` are exact under contention, a user bridge's own unsynchronized state loses 5 to 9% of its increments, and the per-thread reentrancy guard does not protect it |
| `multi-hook-fanout-driver` | one woven call site reaches every hook on the method, in install order; `:cancel` ends the walk *and* skips the host body, `:subscriber` does neither |
| `hostile-safe-driver` | the three bootstrap-loader targets from the limits table that do not overflow the stack: installing on one neither throws nor reports an empty woven count, and the one documented as working really fires |

```sh
clojure -T:build clojure-contract-test   # prints its own pass/fail/error summary
clojure -T:build check                   # build + verify + all drivers
clojure -T:build retransform-driver      # one driver on its own
```

## Why the drivers assert what they assert

**`javaagent-driver` and the standalone `jar-smoke` pass use `-javaagent:`,
never `-java -jar`.** Weaving needs a real `Instrumentation`, and only the
agent entry points get one. `java -jar` reaches `Main-Class`, which is handed
`nil`, so `installer/install` never runs and every hook sits at
`:pending? true` forever, with nothing thrown. The smoke pass also asserts
`:fired > 0`, because a woven count of 1 is not evidence the advice runs.

**`eval-attach-driver`'s child gets a classpath of third-party jars only**, so
every Nihilite class it uses comes out of the agent jar. A child that could
see `src/main/clojure` would resolve edited namespaces straight from disk and
prove nothing about the artifact.

**The six example rows run the packaged agent jar against the example scripts
in child JVMs whose classpath holds only third-party jars.** Every Nihilite
class used by an example or its probe therefore comes out of the agent jar.
The rows test the release artifact rather than the working tree, and they
keep the examples from depending on `examples/` being on the classpath.

**`examples-attach-eval-driver` proves that a JVM without Nihilite can be
attached to at runtime.** The target starts as a plain JVM with no agent. The
attaching process loads the packaged agent jar through
`com.sun.tools.attach.VirtualMachine`, then uses the file-backed eval protocol
to run the session lifecycle in that target.

**`prod-bootstrap-driver` waits for the worker's dispatcher rather than
installing it.** It used to install `install-redefine-dispatcher!` itself right
after `premain`, which masked a real failure: the worker lost a `require` race,
never installed the dispatcher, and the driver quietly supplied the missing
piece. The one thing the driver exists to prove was the thing it was papering
over. It now waits and lets the `:redefine` assertion be a real gate.

That race was real: `clojure.core/load-one` evaluates the whole file and only
then records the lib in `*loaded-libs*`, so `RT/var` on a namespace another
thread is still evaluating returns a var rooted at `Var$Unbound`. Two
catch-and-log layers turned the resulting `IllegalStateException` into a warning
nobody read, and every `:redefine` hook silently returned its stub default. The
worker now waits for the var to be bound and fails loudly if it never does.
`nihilite.test.agent-worker-once-test` locks both halves of that contract.

**`prod-bootstrap-driver` also prints a `REENTRY_STATUS` line**: what a hook on
`java.io.FileInputStream.read` did when its bridge re-entered the target. That
number varies between runs, so it is reported and not asserted. See
[hook-limits.md](hook-limits.md).

## Passes outside `check`

The standalone `jar-smoke` pass remains available, but it is not part of
`check` and is not in `:check-order`. It starts a child with `-javaagent:`,
checks that the init form ran, that a hook wove and fired, and that the child's
log contains no bridge or transformer error.

`clojure -T:build hostile-target-driver` is the characterisation pass behind the
limits table. It asserts nothing, because some of its targets are unsafe on
purpose, and it is deliberately not part of `check`. Use
`HOSTILE_TARGET_INDEX` to run one target per JVM — a target that overflows the
stack ends the run, and the rest of the table goes unmeasured with it.

`check` runs `hostile-safe-driver` instead: the same driver in `HOSTILE_MODE=safe`,
which narrows the table to the three targets that cannot kill the JVM and turns
the assertions on. It deliberately does *not* assert that `Object.hashCode` and
`ZipFile.getEntry` stay silent — they register and never fire today, and pinning
that would freeze the bug rather than measure the boundary, so a future fix still
passes. What it does assert is that installing on a bootstrap-loader class
neither throws nor reports an empty woven count, which is the failure that had
no signal at all before.

A third pass used to live here: `nihilite.test.init-service-driver`, which
measured that an init script can deploy its own network service and put a jar
the agent has never seen onto the classloader. It was deleted on 2026-10-10
because nothing could run it — it was the only driver without a `gen-class`
form, so it had no AOT class, no `build.clj` task, and no path from `check` or
CI. It had already been invalidated twice by unrelated changes without anything
going red, which is the argument for not leaving anything outside `check` that
looks load-bearing — and this one was not even reachable outside `check`.
