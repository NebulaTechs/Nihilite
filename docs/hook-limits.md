# Hook limits

There is no method that is safe to hook unconditionally. A hook is unsafe
exactly when the advice's own machinery needs the hooked method: the generated
advice stub resolves its forwarder var by name on every call, the advice
dispatches through hash maps, and it logs. That cycle closes *above* the
reentrancy guard, before the advice body is ever entered, which is why the guard
cannot save you here. `nihilite.trainer.advice/reentrancy-guard` is real and does
cut re-entry from a *bridge* that calls its own target — but it never gets
consulted in these cases, because the advice body is not reached.

Two classes, and only the first is hopeless:

- **Hopeless.** `String.length`, `String.hashCode`, `Object.equals`,
  `StringBuilder.append`, `ClassLoader.loadClass`. These sit on Clojure's own
  hot paths, so the advice cannot run without re-entering itself. -
  **Classpath-dependent.** `FileInputStream.read`. It is the byte path by which
  a `.class` or `.clj` file on a classpath *directory* becomes a `Class`
  (`BuiltinClassLoader.findClassOnPathOrNull` → `Resource.getBytes` →
  `read`). JDK classes come from the module image and jar entries come through
  `ZipFile` + `Inflater`, neither of which touches it.

## Where the cycle actually closes — two wrong answers

This was chased rather than assumed, and the first two explanations both look
right from the source and neither accounts for what is measured. It is recorded
because the wrong answers are the useful part. Read the correction below before
relying on the sequence, though: the record of what was actually attempted is
incomplete, and one mitigation asserted here for years was never implemented.

**Wrong answer 1: the `reentrancy-guard` is too high.** True but not
sufficient. `advice/reentrancy-guard` lives in `hk-onEntry`, and the generated
stub's first three instructions are `Symbol.intern` ×2 and `Var.intern`, so the
guard is never reached when those recurse. `javap` on Clojure 1.12.5 shows the
chain: `Var.intern` → `Namespace.intern` → an `IPersistentMap` lookup on a
`Symbol` key → `Symbol.hashCode()`, whose bytecode is
`invokevirtual java/lang/String.hashCode`. That hash is still on the hot path:
the four advice class specs (`advice.clj:207-215`, `:217-225`, `:233-242`,
`:244-263`) declare no `:fields`, and `bytegen.clj:462` is the only thing in the
tree that emits a static field, so the generated advice carries none and there
is no cache taking that hash off anything.

**Measured: `String.hashCode`, `String.length` and `StringBuilder.append` all
still overflow the stack.** (hostile-target indices 3, 2, 4.)

**Wrong answer 2: `String.intern` in the forwarder.** Also insufficient — the
same three targets still overflow.

**A correction, and what this section cannot claim.** This text used to close
Wrong answer 1 by asserting that the advice classes "now cache the resolved
forwarder in a static `IMPL` field filled at premain", and then used that as the
baseline for dismissing Wrong answer 2 — "after the change above". No such field
has ever existed. `grep -rn IMPL` over the repo outside `target/` matches only
this file, `git log -S IMPL -- src/main/clojure/` returns no commits, and the
sentence arrived with the three-layer refactor (`8f6847a`), not with a
performance change. So both dismissals were argued against a baseline that was
never built.

The measurements above are unaffected by this, and so is the conclusion the
section reaches: the cycle that actually kills these targets is in the indy
linking path below, which no advice-side change would have reached either. What
cannot be recovered is the order the measurements were taken in — that was never
recorded, and that, rather than any single explanation, is the real defect in a
section whose job is to be the record of falsified explanations.

**What is left.** The cycle that actually kills them sits in the **indy linking
path**, above the advice entirely, so no advice guard can reach it:
`iab-bootstrap` looks its anti-reentrancy placeholder up in `linking-map` under
a Clojure vector of three Strings, and registering that placeholder happens
*inside* the `.put` whose own hashing has already recursed. A bootstrap-loader
target reaches this on its first call, while the call site is still linking —
before any advice method body exists to be guarded. This is source inference,
not measured: the overflow is too deep for the JVM to print a stack, which is
itself a symptom of unbounded recursion rather than of a bounded failure.

The obvious next experiment is to make both maps key on identity —
`IdentityHashMap` keyed by the call site's `MethodType`, which is one object
per call site and never hashes a String. That has not been tried, and given the
first two failures it should be treated as a hypothesis, not a fix.

## Measured boundary

JDK 25, each target through the production `install!` path in its own JVM:

```sh
clojure -T:build hostile-target-driver                # all of them
HOSTILE_TARGET_INDEX=3 clojure -T:build hostile-target-driver
HOSTILE_TARGET_INDEX=-1 clojure -T:build hostile-target-driver   # backwards
clojure -T:build hostile-safe-driver                  # the three that cannot SOE
```

The index is an environment variable, not an argument: `clojure -T:build`
does not forward positional arguments to the task. The last `HOSTILE_BEGIN`
line in the log names whichever target killed the JVM.

`hostile-safe-driver` runs the same table in `HOSTILE_MODE=safe`, which keeps
only the three targets that cannot overflow the stack and asserts on them. It
is the form `check` runs. It asserts that installing on each neither throws nor
reports an empty woven count, and that `FileOutputStream.write` really fires. It
deliberately does **not** assert that `Object.hashCode` and `ZipFile.getEntry`
stay silent — they are "never fires" below because that is what the measurement
found, not because it is the desired behaviour, and pinning it would make a
future fix look like a regression.

| Target | Outcome |
| --- | --- |
| `java.io.FileOutputStream.write([BII)V` | fires, re-entrancy cut |
| `java.io.FileInputStream.read([BII)I` | classpath-dependent — see below |
| `java.lang.Object.hashCode()I` | registers, reports woven count 1, **never fires** |
| `java.util.zip.ZipFile.getEntry` | registers, reports woven count 1, **never fires** |
| `java.lang.String.length()I` | StackOverflowError |
| `java.lang.String.hashCode()I` | StackOverflowError |
| `java.lang.Object.equals(Object)` | StackOverflowError |
| `java.lang.StringBuilder.append(String)` | JVM assertion failure in `libinstrument`, then StackOverflowError |
| `java.lang.ClassLoader.loadClass(String)` | `ClassCircularityError` at install time |
| `java.util.zip.Inflater.inflate([B)I` | in the driver's table, not measured — the hammer's gzip stream does not match a default `Inflater` |

`java.util.BitSet` (`isEmpty` / `size` / `cardinality` / `get`) fires at all
four positions, but it is not in this table's source: it is measured by
`clojure -T:build prod-bootstrap-driver`, which is where the four-position
matrix lives. The two passes answer different questions — this one looks for
targets that defeat the machinery, that one for targets that survive it.

"Registers but never fires" is the failure mode to watch for. `install!` returns
a woven count and nothing throws, so every driver asserts a real firing rather
than a successful install.

One route to it is now closed. The `:descriptor` decides which loaded method a
hook matches — ByteBuddy matches on it and `nihilite.builder.registry.index` keys its
buckets by it — so a descriptor that is not a valid JVM method descriptor used
to register successfully and then match nothing. `install!` now rejects one
with `:nihilite/bad-descriptor`, checked at install time rather than left to
surface as silence. The targets measured above all had correct descriptors; the
untestable ones (a method that cannot be hooked) still cannot.

## FileInputStream.read, in detail

Its outcome is a function of the classpath shape, not of the method. Same code,
same driver:

| Classpath | Outcome |
| --- | --- |
| directories (`src/main/clojure`, `target/classes`) | registers, never fires (4/4); StackOverflowError on other runs |
| jar only (`target/nihilite.jar` + `target/test-classes`) | fires every run, 1–2 calls, no overflow |

In the never-fires case the advice is never entered at all. That was
established by temporarily inserting an `AtomicLong` counter as the first
statement of `nihilite.trainer.advice/hk-onEntry` — before the guard, before
`lookup-spec` — and watching it stay at 0 across every run while the hooked
method returned bytes normally and `install!` still reported success. The
counter is not in the tree now; the characterisation driver's `:fired 0` is
the standing evidence for the same thing. So this is not "advice ran but the
spec did not match" — the guard was never consulted and `lookup-spec` was
never reached. The call site is woven but its invokedynamic is never linked.
This is the same silent shape recorded when the BitSet `redefine` bridge was
fixed in `26ad354`.

Two hypotheses were ruled out rather than left standing:

- **JIT inlining.** No. Every measurement is on the `premain` path about 1.3s
  in, before any meaningful compilation, and a late-install experiment (cold,
  warm 2M calls with `-XX:-TieredCompilation`, warm with default JIT) returned
  `fired 0` in all three arms. - **The method being loaded too late.** No.
  `:loader :bootstrap` in `install-status!` is itself the proof the class is
  loaded — the target loader does `Class/forName` first and only reports
  `:unloaded` when that throws.

Neither the JVM's nor this API's behaviour is reliable here, so a hook on
`FileInputStream.read` cannot carry a test, let alone an example. The example
set uses `FileOutputStream.write` instead.

## The uninstall window

`uninstall!` removes the spec from the registry *before* it retransforms the
target class, and the removal has to come first: the transformer reads the
registry as its input, so a spec still in the registry would be re-woven and
the uninstall would do nothing. Between those two steps the spec is absent
from the registry while the bytecode still carries the advice.

That window is measured, not assumed:

```sh
clojure -T:build uninstall-race-driver
```

The driver parks a call *inside* the advice, performs the uninstall while
that call is in flight, and then releases it. Measured on JDK 27:

| Observation | Result |
| --- | --- |
| the uninstall window | ~20–24 ms |
| a call that arrives during the window | finds no spec, is skipped, the target method runs normally |
| a call that was already inside the advice | runs the old bridge to completion |
| `:fired` after that stale fire | 0 |

The last row is the one that matters. `stats/remove-stats` drops the counter
record before the retransform, and `stats/bump-fired!` is
`(when-let [r (get-stats spec-id)] (swap! (:fired r) inc))` — with the record
gone the increment is a no-op. So the fire happened and the registry has no
record of it. `install-status!` then reports `:registered? false` and
`:fired 0`, which is self-consistent with the registry and inconsistent with
the history.

The driver asserts the shape, not a count: the stale bridge must run, the
counter must read 0 afterwards, and `lookup` must return nil. A control
phase runs the same bridge without parking it and asserts `:fired` is 1,
which is what rules out the alternative explanation that the counter never
counts anything.

The window is a property of the current design, not a defect in it: the
ordering is required, and the JVM cannot un-run bytecode that is already
executing. What the measurement buys is that the boundary is documented
rather than re-discovered, and that the size of the window is known. Closing
it would mean draining in-flight calls before the removal, which is a
larger change than the problem currently warrants.
