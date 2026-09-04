# Nihilite

Attach a Clojure nREPL to any running JVM, with a ByteBuddy control plane
for weaving method-level hooks into host classes.

```sh
./gradlew --no-daemon assemble   # build → build/libs/nihilite.jar
./gradlew --no-daemon check      # verify
```

Two entry points on the same fat jar:

- `java -jar nihilite.jar` — standalone nREPL server
- `-javaagent:nihilite.jar` — REPL + ByteBuddy installer

Public API: `nihilite.api` — `install!`, `uninstall!`, `lookup`,
`list-specs`, `swap-bridge!`, `register-action!`.

License: BSD 2-Clause.