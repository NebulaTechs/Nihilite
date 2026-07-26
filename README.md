# Nihilite

A Clojure REPL you can attach to a running JVM.

```sh
java -jar nihilite.jar
```

It binds `127.0.0.1:7888`. Connect any nREPL client, or `telnet`. You get a
live Clojure runtime talking to whatever JVM you launched it from — including
a Minecraft server, if you attach with `-javaagent`.

---

## Run it

### Standalone (just a Clojure REPL on a port)

```sh
java -jar nihilite.jar
```

Open another terminal:

```sh
telnet 127.0.0.1 7888
```

```
> (* 6 7)
=> 42
> (exit)
bye
```

`telnet` gives you server-side editing (history, TAB completion, C-a/e/k/u/w,
ESC-b/f, C-c cancel, C-d exit). `nc`/`socat` work too but use your local
terminal's editing — no history, no completion.

For a real editor:

```sh
lein repl :connect 127.0.0.1:7888
# or: cider-connect, Calva, Cursive, anything that speaks nREPL
```

Same port. The server sniffs the client and routes accordingly.

### With an init file

```sh
java -jar nihilite.jar -Dnihilite.init=/path/to/init.clj
```

`init.clj` runs once at boot, before any client connects. Put your
`(require ...)`s, plugin registrations, and shared `def`s there.

### Inside another JVM (e.g. Minecraft)

```sh
java -javaagent:/path/to/nihilite.jar \
     -Dnihilite.init=/path/to/init.clj \
     -jar minecraft.jar
```

The agent attaches, the REPL comes up on the same port. From your editor,
`(import 'net.minecraft.server.MinecraftServer)` and you're talking to live
objects.

---

## Use it

Connect, then write Clojure:

```clojure
> (+ 1 2)
=> 3

> (defn greet [name] (str "hello, " name))
> (greet "world")
=> "hello, world"
```

Hot-rewrite a handler:

```clojure
> (require '[user.handlers :as h])

> (alter-var-root #'h/on-chat
                     (constantly
                       (fn [ev]
                         (println "saw chat:" (:msg ev))
                         ev)))
;; the next matching event routes through the new function
;; no restart, no reload
```

Reset back to file defaults without restarting:

```clojure
> (require '[nihilite.reload :as r])
> (r/re-init!)
```

---

## Configure

| Flag / property                | Default       | What it does                |
|--------------------------------|---------------|-----------------------------|
| `--port=<n>` / `-Dnihilite.port`  | `7888`      | Listen port (loopback only) |
| `--bind=<host>` / `-Dnihilite.bind` | `127.0.0.1` | Bind host                |
| `-Dnihilite.init=<path>`        | (off)       | Clojure file to load at boot |

One socket. The server routes each connection to: nREPL bencode (your
editor), raw readline (telnet/nc/socat), HTTP (`GET /healthz`,
`POST /v1/eval`), or WebSocket — automatically, based on what the client
speaks. All unauthenticated, all loopback. Don't expose this to a network
you don't trust.

---

## Limits

- Doesn't redefine already-loaded Java classes. You rewrite Clojure vars /
  protocols / namespaces — that's the hot-rewrite surface.
- Doesn't add new Minecraft entities, items, or blocks. Same limits as
  Fabric/Forge/Bukkit.
- One binary, no opt-in flags. `-javaagent:nihilite.jar`, that's it.
- No auth. Loopback bind only — operator's job to keep the network right.

---

## License

BSD 2-Clause.