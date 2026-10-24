# Examples

Every example is a plain Clojure `load-file` script. The examples directory is
not on the project classpath, and no example is bundled into the release jar.
The five init-style examples are loaded through `nihilite.init`. The dynamic
attach example runs as a script against a target JVM.

## Common setup

Build the agent jar before running an example:

```sh
clojure -T:build uberjar        # -> target/nihilite.jar
```

For an init-style example, replace `your-app.jar` with an application jar.
The JVM must start with `-javaagent:target/nihilite.jar`. Without the agent,
the hook never weaves and never fires.

`nihilite.init` takes a Clojure form, not a path:

```sh
java -Dnihilite.init='(load-file "examples/jdkstdlib/init.clj")' \
     -javaagent:target/nihilite.jar -jar your-app.jar
```

## `jdkstdlib/init.clj`

**What it demonstrates:** The public API for a JDK standard library hook. The
hook targets `java.io.FileOutputStream.write([BII)V`, a bootstrap-loader
method reached through an invokedynamic call site.

**Prerequisites:** Build `target/nihilite.jar`, and provide an application jar
that writes a file.

**Command:**

```sh
java -Dnihilite.init='(load-file "examples/jdkstdlib/init.clj")' \
     -javaagent:target/nihilite.jar -jar your-app.jar
```

**Observable output:** The script prints a line beginning
`[examples.jdkstdlib.init] loaded.`, and the agent prints
`nihilite:init-done` after the init form finishes. The application must call
`FileOutputStream.write` before the hook can fire.

**CI-gated:** Yes. `clojure -T:build examples-jdkstdlib-driver` runs the
example and its deterministic probe. The probe prints
`nihilite:example-pass jdkstdlib`, and the driver prints
`DRIVER_PASS examples init: jdkstdlib`.

## `hotrewrite/init.clj`

**What it demonstrates:** `swap-bridge!` replacing a live bridge on
`java.util.BitSet.size`. Consecutive calls can reach bridge v1 and then bridge
v2 without restarting the JVM.

**Prerequisites:** Build `target/nihilite.jar`, and provide an application jar.

**Command:**

```sh
java -Dnihilite.init='(load-file "examples/hotrewrite/init.clj")' \
     -javaagent:target/nihilite.jar -jar your-app.jar
```

**Observable output:** The script prints a line beginning
`[examples.hotrewrite.init] loaded.`, and the agent prints
`nihilite:init-done` after the init form finishes.

**CI-gated:** Yes. `clojure -T:build examples-hotrewrite-driver` calls the
target before and after the bridge swap. The probe prints
`nihilite:example-pass hotrewrite`, and the driver prints
`DRIVER_PASS examples init: hotrewrite`.

## `positions/init.clj`

**What it demonstrates:** The four hook positions, `:entry`, `:return`,
`:throw`, and `:redefine`, on measured-safe `java.util.BitSet` targets. The
first three bridges receive one context argument. The redefine bridge receives
`self`, `args`, and `method-name`, and its return value replaces the original
method result.

**Prerequisites:** Build `target/nihilite.jar`, and provide an application jar.

**Command:**

```sh
java -Dnihilite.init='(load-file "examples/positions/init.clj")' \
     -javaagent:target/nihilite.jar -jar your-app.jar
```

**Observable output:** The script prints a line beginning
`[examples.positions.init] loaded.`, and the agent prints
`nihilite:init-done` after the init form finishes. When
`(examples.positions.init/exercise!)` runs, the script prints one line for
each position.

**CI-gated:** Yes. `clojure -T:build examples-positions-driver` runs
`exercise!` and checks each position and bridge shape. The probe prints
`nihilite:example-pass positions`, and the driver prints
`DRIVER_PASS examples init: positions`.

## `lifecycle/init.clj`

**What it demonstrates:** The complete public hook lifecycle on
`java.util.BitSet.size`: install, fire, inspect `install-status!`, swap the
bridge, fire again, uninstall, and confirm that public lookup and the public
spec list no longer contain the hook.

**Prerequisites:** Build `target/nihilite.jar`, and provide an application jar.

**Command:**

```sh
java -Dnihilite.init='(load-file "examples/lifecycle/init.clj")' \
     -javaagent:target/nihilite.jar -jar your-app.jar
```

**Observable output:** The script does not print a result of its own. The agent
prints `nihilite:init-done` after the init form finishes.

**CI-gated:** Yes. `clojure -T:build examples-lifecycle-driver` runs the
lifecycle and checks the status counters and cleanup state. The probe prints
`nihilite:example-pass lifecycle`, and the driver prints
`DRIVER_PASS examples init: lifecycle`.

## `actions/init.clj`

**What it demonstrates:** The four built-in actions and their different
observable results. `:observe` preserves the original result, `:modify`
replaces it, `:cancel` short-circuits with the cancellation exception, and
`:subscriber` records cancellation while preserving the original result.

**Prerequisites:** Build `target/nihilite.jar`, and provide an application jar.

**Command:**

```sh
java -Dnihilite.init='(load-file "examples/actions/init.clj")' \
     -javaagent:target/nihilite.jar -jar your-app.jar
```

**Observable output:** The script prints a line beginning
`[examples.actions.init] loaded.`, and the agent prints
`nihilite:init-done` after the init form finishes.

**CI-gated:** Yes. `clojure -T:build examples-actions-driver` exercises all
four actions and checks their results and runtime counters. The probe prints
`nihilite:example-pass actions`, and the driver prints
`DRIVER_PASS examples init: actions`.

## `dynamic_attach_eval.clj`

**What it demonstrates:** Attaching Nihilite to an already-running JVM with
`com.sun.tools.attach.VirtualMachine`, then driving the file-backed eval
protocol without Java source. The evaluated code opens a session, evaluates
`(+ 20 22)`, polls the result, and closes the session.

**Prerequisites:** Build `target/nihilite.jar`. Start a plain JVM that remains
alive, with no Nihilite agent attached, and take its process id. The attaching
process must be allowed to attach to that JVM.

**Command:**

```sh
clojure examples/dynamic_attach_eval.clj <pid> target/nihilite.jar
```

**Observable output:** The script prints
`nihilite:example-pass dynamic-attach-eval`. The target does not print
`nihilite:init-done`, because it had no Nihilite agent when it started.

**CI-gated:** Yes. `clojure -T:build examples-attach-eval-driver` starts a
plain target JVM, attaches to it, and checks the file-backed eval result. The
driver prints `DRIVER_PASS examples attach: dynamic-attach-eval`.

## How these are tested

Each init-style example is paired with a deterministic probe under
`src/test/clojure/probe/examples/`. A shared driver runs the example and its
probe in a child JVM. The child classpath contains only third-party jars, so
the example and probe can resolve Nihilite only through the packaged agent
jar. The driver requires both `nihilite:init-done` and the example's
`nihilite:example-pass` marker.

The dynamic attach example is driven against a plain target JVM. That target
has no Nihilite until the attach happens, so the driver proves that runtime
attachment supplies the agent and the eval protocol without prior startup
configuration.
