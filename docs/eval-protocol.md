# Eval protocol

How another process evaluates code inside a JVM Nihilite is attached to.

## The wire format

`VirtualMachine.loadAgent` takes one String and returns void. That String is the
only way in, and there is no way back, so Nihilite reads it as a small format
any language can implement:

```text
eval:<transport>|<base64-code>
```

| transport | effect |
| --- | --- |
| `discard` | run it, reply nowhere |
| `file:<path>` | reply written to that file as EDN |
| `tcp:<host>:<port>:<token>` | reply written to a socket, token echoed first |

Agent args without the `eval:` prefix are not an eval request, so an existing
invocation behaves exactly as it did. The reply is one EDN map:

| key | |
| --- | --- |
| `:token` | echoed from the transport, so a caller can match replies |
| `:session` | the session id, live if `:done` is false |
| `:ns` | the session's namespace |
| `:value` | the last non-nil value the session saw, rendered |
| `:out` / `:err` | everything printed since the previous read |
| `:error` | the rendered exception, or nil |
| `:done` | false if the eval was still running when the wait expired |

A complete round trip:

```java
VirtualMachine vm = VirtualMachine.attach(pid);
vm.loadAgent("target/nihilite.jar",
             "eval:file:/tmp/reply.edn|" + base64("(nihilite.api/list-specs)"));
vm.detach();
// /tmp/reply.edn now holds {:value "{...}", :done true, ...}
```

## Sessions

`:done false` means the eval outlived the wait. The `:session` in the reply is
then **left open on purpose** — send a second request whose code is
`(nihilite.eval/snapshot "<session-id>")` to read it again. That session is not
closed by the request that created it, so this works. A request whose eval
finishes normally *does* close its own session.

One attach round trip per read is the price of `loadAgent` having no return
channel. If you want cheaper reads, start a real service from your init script
instead (`examples/nrepl_service.clj`).

The wait defaults to 30s and is settable with `-Dnihilite.eval.wait-ms`, which
matters mostly for tests that need to reach the `:done false` path without
waiting half a minute.

## Inside the target

The same code is reachable in-process as `nihilite.eval`, which is deliberately
a separate namespace from `nihilite.api`: one is the hook workbench, the other
is a control plane, so requiring one does not drag in the other.

| verb | |
| --- | --- |
| `(eval/open-session)` | open a session, returns its id |
| `(eval/eval-in sid code)` | start evaluating, returns an eval id **immediately** |
| `(eval/snapshot sid)` / `(eval/snapshot sid since)` | output and state |
| `(eval/interrupt sid)` | `Thread.interrupt` the running eval |
| `(eval/close-session sid)` | interrupt anything running, then forget it |
| `(eval/session? sid)` / `(eval/session-ids)` | which sessions exist |

Each session keeps its own namespace, so `(def x 1)` survives into the next
`eval-in` and is invisible to other sessions.

A session owns **one thread and a queue**, not one thread per eval. Two evals
submitted to the same session run in submission order, because they share its
namespace: concurrently they would race on `*ns*`, and the second thread's
binding vector would replace the first's, so the first eval could read the wrong
namespace or write its output through the wrong writer. This is the same shape
nREPL uses.

`:running?` is therefore "this session has an eval executing or one still
queued", and it goes true at enqueue time rather than when the thread picks the
task up — a poll landing between those two would otherwise read the session too
early.

`eval-in` is asynchronous because the usual caller is an attacher holding a
`loadAgent` open: a form that never returns must not wedge it.

### Two shapes that are easy to misread

`:value` is the last **non-nil** value the session saw, not this eval's result,
so a form returning nil leaves it alone. Every non-nil form's rendered value is
*also* emitted into `:events` with `:stream :out` — which is what a REPL should
print. Printing `:value` instead looks correct until a `(require ...)` appears
to return whatever the previous form returned.

`:events` is one ordered log, each entry tagged `:out` or `:err` with a `:seq`,
so the interleaving of the two streams is recorded rather than reconstructed.
Pass the previous `:cursor` back as `since` to read incrementally; `:cursor`
comes back monotonic.

### What interrupt cannot do

`interrupt` delivers `Thread.interrupt` to the session's thread and discards
anything queued behind it. The thread itself survives and serves the next eval.
It unblocks a thread waiting on I/O, sleep or a monitor, and throws
`InterruptedException` into it.

It does **not** stop a tight `(loop [] (recur))`: Clojure's `recur` never checks
the interrupt flag, and `Thread.stop`, which would, was removed in JDK 20.
Measured here rather than assumed:

| JDK | `Thread.stop()` on a Clojure tight loop |
| --- | --- |
| 17 | accepted, thread dies |
| 25 | `UnsupportedOperationException`, thread lives |

nREPL's answer to the same problem is a scheduled reaper that force-kills after
5s, using `Thread.stop` before JDK 20 and a JVMTI native agent after. We do not:
it needs a platform `.so` unpacked and the target attaching to itself, it risks
state corruption, and it buys the ability to kill code the user wrote wrongly.
`:interrupted? true` therefore means the request was delivered, not that the
eval stopped — poll `snapshot` for `:running?` to find out.

Code that must be stoppable manages that itself. `eval-in` returns immediately,
so spawning a thread and holding it is cheap.

Infinite recursion is a different case and does terminate, bounded by stack
depth:

```text
(defn boom [n] (inc (boom (inc n)))) (boom 0)
  settled?        true
  error present?  true
  error head      #error { ... :type java.lang.StackOverflowError ...
```

The failure arrives through `:error` like any other.

## Invariants that keep streaming a later addition

Four things are built in so that push-based streaming is an additive change
rather than a redesign. They are implemented in `nihilite.eval.session`.

1. Output goes through one function, `emit!`. A streaming sink is another sink
   inside `emit!`, and nothing else moves. 2. Every chunk lands in ONE ordered
   log tagged `:stream`, so out/err interleaving is recorded rather than
   reconstructed. Two separate buffers would lose the order permanently. 3. Each
   chunk carries a monotonic `:seq`, so a read can take a cursor. The counter
   behind `:seq` counts output events and nothing else — eval ids come off their
   own counter — so the sequence stays dense and a cursor is a real index. 4. A
   session keeps the `Thread` running its eval. That reference is the only
   reason `interrupt` can exist and it cannot be recovered once dropped.

What streaming cannot become: push rather than poll needs a channel the target
can write to, which means the attaching side listens on loopback and the agent
connects back. Nihilite itself still listens on nothing.
