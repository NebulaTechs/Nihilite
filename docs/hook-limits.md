# Hook limits

There is no method that is safe to hook unconditionally. A hook is unsafe
exactly when the advice's own machinery needs the hooked method: the generated
advice stub resolves its forwarder var by name on every call, the advice
dispatches through hash maps, and it logs. That cycle closes *above* the
reentrancy guard, before the advice body is ever entered, which is why the guard
cannot save you here. `nihilite.kernel.advice/reentrancy-guard` is real and does
cut re-entry from a *bridge* that calls its own target — but it never gets
consulted in these cases, because the advice body is not reached.

Two classes, and only the first is hopeless:

- **Hopeless.** `String.length`, `String.hashCode`, `Object.equals`,
  `StringBuilder.append`, `ClassLoader.loadClass`. These sit on Clojure's own
  hot paths, so the advice cannot run without re-entering itself. -
  **Classpath-dependent.** `FileInputStream.read`. It is the byte path by which
  a `.class` or `.clj` file on a classpath *directory* becomes a `Class`
  (`BuiltinClassLoader.findClassOnClassPathOrNull` → `Resource.getBytes` →
  `read`). JDK classes come from the module image and jar entries come through
  `ZipFile` + `Inflater`, neither of which touches it.

## Measured boundary

JDK 25, each target through the production `install!` path in its own JVM:

```sh
clojure -T:build hostile-target-driver                # all of them
HOSTILE_TARGET_INDEX=3 clojure -T:build hostile-target-driver
HOSTILE_TARGET_INDEX=-1 clojure -T:build hostile-target-driver   # backwards
```

The index is an environment variable, not an argument: `clojure -T:build`
does not forward positional arguments to the task. The last `HOSTILE_BEGIN`
line in the log names whichever target killed the JVM.

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
hook matches — ByteBuddy matches on it and `nihilite.registry.index` keys its
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
statement of `nihilite.kernel.advice/hk-onEntry` — before the guard, before
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
