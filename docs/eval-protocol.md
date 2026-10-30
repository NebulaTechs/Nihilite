# Eval protocol

How another process evaluates code inside a JVM Nihilite is attached to.

## The wire format

`VirtualMachine.loadAgent` takes one String and returns void. That String is the
only way in, and there is no way back, so Nihilite reads it as a small format
any language can implement:

```text
eval:TRANSPORT|CODE-SOURCE
```

| transport | effect |
| --- | --- |
| `discard` | run it, reply nowhere |
| `tcp:HOST:PORT:TOKEN` | reply written to a socket, token echoed first |

| code-source | effect |
| --- | --- |
| `BASE64-CODE` | the code itself, Base64 of its UTF-8 bytes |
| `tcp:HOST:PORT:TOKEN` | the code read from that socket |

The two halves are independent. A request may carry the code inline and the
reply over a socket, or the code over a socket and the reply discarded, or both
over sockets.

The socket form for the code is not a nicety. The attach protocol caps one
request argument at 1024 characters on JDK 11 through 21
(`AttachOperation::arg_length_max`, checked by the target in
`AttachOperation::RequestReader::read_request`), and the JDK drops an oversized
request without a word, so the attaching side sees only a `Premature EOF`. Code
that travels over a socket keeps the request's size independent of the code's
size, and a socket exists on every platform the JVM runs on, so nothing has to
be written to shared storage first.

The inline form stays for short code, where the whole request is one line of
the attacher.

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

A complete round trip, with the code inline:

```java
VirtualMachine vm = VirtualMachine.attach(pid);
vm.loadAgent("target/nihilite.jar",
             "eval:tcp:127.0.0.1:9001:reply-token|" + base64("(nihilite.builder.api/list-specs)"));
vm.detach();
// the socket on 127.0.0.1:9001 now holds "reply-token\n{:value \"{...}\", :done true, ...}"
```

And with the code on its own socket, so its size does not enter the attach
argument at all:

```java
ServerSocket code = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
new Thread(() -> {                          // loadAgent blocks until
  try (Socket s = code.accept()) {          // agentmain returns, and
    s.getOutputStream().write(codeBytes);   // agentmain cannot return
    s.shutdownOutput();                     // before it has the code
  }
}).start();
vm.loadAgent("target/nihilite.jar",
             "eval:tcp:127.0.0.1:9001:reply-token|tcp:127.0.0.1:" + code.getLocalPort() + ":code-token");
```

The target connects to the code socket, echoes the token on the first line so
the attacher can confirm it reached the agent it asked, and then reads the code
until the attacher closes its half of the connection.

## Sessions

`:done false` means the eval outlived the wait. The `:session` in the reply is
then **left open on purpose** — send a second request whose code is
`(nihilite.builder.eval/snapshot "SESSION-ID")` to read it again. That session is not
closed by the request that created it, so this works. A request whose eval
finishes normally *does* close its own session.

One attach round trip per read is the price of `loadAgent` having no return
channel. For a complete socket-backed attach round trip, see
[`examples/dynamic_attach_eval.clj`](../examples/dynamic_attach_eval.clj).

The wait defaults to 30s and is settable with `-Dnihilite.eval.wait-ms`, which
matters mostly for tests that need to reach the `:done false` path without
waiting half a minute.

## Inside the target

The same code is reachable in-process as `nihilite.builder.eval`, which is deliberately
a separate namespace from `nihilite.builder.api`: one is the hook workbench, the other
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

The mirror of that is when it goes false. Not when an eval finishes: the thread
hands `:running` straight to the next queued eval instead of clearing it, because
clearing it left a window where a second eval submitted during the first was
already off the queue (so the queue-size half of the check read empty) and not
yet finished (so the atom read empty too). Measured: `(+ 1 1)` then
`(Thread/sleep 600)` reported `:running?` false for the whole 600ms, and a caller
polling it was told the work was done while it was running.

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

An interrupted eval is not a failed eval. The `InterruptedException` is not
reported through `:error`: `:error` is where a caller's code went wrong, and
an interrupt is somebody asking the eval to stop. The session stays open, so
the next `eval-in` on it runs normally.

`close-session` is different: it ends the session, and its thread with it.
The two are told apart by a flag the session carries, so a close that lands
while an eval is still running still ends the thread — the interrupt that
reaches the running eval clears the thread's interrupt flag, and without the
flag the thread would go back to waiting on a queue nobody will ever feed.

Neither path prints anything to stderr. An `InterruptedException` that
escapes the session thread is written by the JVM's default handler as
`Exception in thread "..." ` — without a trailing newline, before the stack
trace — so any output written in that window lands on the same line. In a
JVM whose stdout and stderr share a pipe that is one line, not two, and a
reader matching markers per line then fails to see a marker that was
printed. Measured: the init-done marker arrived as
`Exception in thread "nihilite-eval-s1" nihilite:init-done`.

It does **not** stop a tight `(loop [] (recur))`: Clojure's `recur` never checks
the interrupt flag, and `Thread.stop`, which would, was removed in JDK 20.
Measured here rather than assumed:

| JDK | `Thread.stop()` on a Clojure tight loop |
| --- | --- |
| 17 | accepted, thread dies |
| 25 | `UnsupportedOperationException`, thread lives |

nREPL's answer to the same problem is a scheduled reaper that force-kills after
5s, using `Thread.stop` before JDK 20 and a JVMTI native agent after — and that
agent has to be enabled for the kill to happen at all. We do not: it needs a
platform `.so` unpacked and the target attaching to itself, it risks state
corruption, and it buys the ability to kill code the user wrote wrongly.
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
rather than a redesign. They are implemented in `nihilite.builder.eval.session`.

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
connects back. The agent itself never listens: every channel it uses is one it
dials out on.
