# Drivers

`clojure -T:build check` runs the contract tests plus six drivers. The drivers
exist because the contract tests run in-process and cannot reach a real
`Instrumentation`, and the two failure modes this project has actually suffered
— weaving that silently never fires, and an install that reports success while
the agent is half-built — are both invisible from inside.

| driver | proves |
| --- | --- |
| `retransform` | all four positions on an already-loaded class; a co-located `:entry` survives a `:redefine` uninstall; the reentrancy guard cuts a bridge that re-enters its own target |
| `jar-smoke` | a spawned `java -javaagent:` process: the init form evaluates, a hook reports a non-zero `:woven-count` **and** fires, and the child's log carries no bridge or transformer errors |
| `eval-attach` | a second process attaching to that child and driving `loadAgent` with `eval:` requests — value, `*out*`, thrown error, `:done false`, poll, interrupt, close, and a hook installed over the channel that then fires |
| `redefine-instance` | an instance method redefined such that the body captures `self` via `@This` |
| `indy` | an invokedynamic call site woven into a bootstrap-loader method actually firing |
| `prod-bootstrap` | all four positions installing and firing on a bootstrap-loader class through the production `install!` path, with the worker itself supplying the redefine dispatcher |

```sh
clojure -T:build clojure-contract-test   # 190 cases
clojure -T:build check                   # build + verify + all drivers
clojure -T:build retransform-driver      # one driver on its own
```

## Why the drivers assert what they assert

**`jar-smoke` uses `-javaagent:`, never `-java -jar`.** Weaving needs a real
`Instrumentation` and only the agent entry points get one; `java -jar` reaches
`Main-Class`, which is handed `nil`, so `installer/install` never runs and every
hook sits at `:pending? true` forever — with nothing thrown. It also asserts
`:fired > 0`, because a woven count of 1 is not evidence the advice runs.

**`eval-attach`'s child gets a classpath of third-party jars only**, so every
nihilite class it uses comes out of the agent jar. A child that could see
`src/main/clojure` would resolve edited namespaces straight from disk and prove
nothing about the artifact.

**`prod-bootstrap` waits for the worker's dispatcher rather than installing
it.** It used to install `install-redefine-dispatcher!` itself right after
`premain`, which masked a real failure: the worker lost a `require` race, never
installed the dispatcher, and the driver quietly supplied the missing piece — so
the one thing the driver exists to prove was the thing it was papering over. It
now waits and lets the `:redefine` assertion be a real gate.

That race was real: `clojure.core/load-one` evaluates the whole file and only
then records the lib in `*loaded-libs*`, so `RT/var` on a namespace another
thread is still evaluating returns a var rooted at `Var$Unbound`. Two
catch-and-log layers turned the resulting `IllegalStateException` into a warning
nobody read, and every `:redefine` hook silently returned its stub default. The
worker now waits for the var to be bound and fails loudly if it never does —
`nihilite.test.agent-worker-once-test` locks both halves of that contract.

**`prod-bootstrap` also prints a `REENTRY_STATUS` line**: what a hook on
`java.io.FileInputStream.read` did when its bridge re-entered the target. That
number varies between runs, so it is reported and not asserted — see
[hook-limits.md](hook-limits.md).

## Passes outside `check`

`clojure -T:build hostile-target-driver` is the characterisation pass behind the
limits table. It asserts nothing, because some of its targets are unsafe on
purpose, and it is deliberately not part of `check`.

`nihilite.test.init-service-driver` measures that an init script can deploy its
own network service and put a jar the agent has never seen onto the classloader.
It is also outside `check` — and has been invalidated twice by unrelated changes
without anything going red, which is the argument for not leaving anything
outside `check` that looks load-bearing.
