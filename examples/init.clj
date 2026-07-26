;; nihilite-module: -
;; nihilite-requires: -
;;
;; Minimal init file used as a TEST FIXTURE and as an opt-in example.
;; nihilite does NOT auto-load this file. Pure-REPL startup means
;; nothing is loaded unless -Dnihilite.init=<path> is set on the JVM
;; command line. Operators may edit this file or pass a different
;; -Dnihilite.init=<path>.
;;
;; This file is exercised by nihilite.reload/re-init! when an operator
;; explicitly passes :init-file "examples/init.clj" (or sets
;; -Dnihilite.init=examples/init.clj). It is a no-op so a smoke can
;; validate load-init! / re-init! without touching any framework
;; classes.
;;
;; NOTE: `nihilite-module:` is intentionally `-` so nihilite.reload's
;; topologically-sorted walk does NOT auto-pick this file up. It is
;; loaded only when explicitly requested.

(println "[examples/init] test fixture loaded; not auto-loaded by nihilite — operators opt in via -Dnihilite.init=examples/init.clj or pass their own")
