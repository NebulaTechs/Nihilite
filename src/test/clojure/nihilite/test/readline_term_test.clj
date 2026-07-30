(ns nihilite.test.readline-term-test
  "Terminal construction picks the right terminfo profile and size for
   raw-branch clients. Real telnet (:char echo mode) gets full ANSI;
   nc/socat (:line echo mode) gets `dumb` so jline3's multi-column
   list renderer does not stair-case across columns on the second TAB.

   Regression: build-terminal previously hard-coded `xterm` with
   `Size(0,0,0,0)`. On a real telnet client the multi-column list
   rendered fine the first time, then `redrawLine()` after the second
   TAB computed the diff against a virtual-screen model that did not
   match the actual terminal (no NAWS negotiated, no clr_eol, columns
   assumed 0 → list wrapped to one char per row)."
  (:require [clojure.test :refer [deftest is testing]]
            [nihilite.readline.terminal :as term])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [org.jline.terminal.impl ExternalTerminal]))

(deftest terminal-type-defaults-to-xterm
  (testing "3-arg arity is unchanged; type defaults to `xterm`."
    (let [t (term/build-terminal
              (ByteArrayInputStream. (byte-array 0))
              (ByteArrayOutputStream.)
              "nihilite-test")]
      (is (instance? ExternalTerminal t))
      (is (= "xterm" (.getType ^ExternalTerminal t))
          "default terminal type is xterm (real telnet)"))))

(deftest terminal-type-keyword-dumb
  (testing "passing :dumb yields terminfo profile `dumb` for nc/socat."
    (let [t (term/build-terminal
              (ByteArrayInputStream. (byte-array 0))
              (ByteArrayOutputStream.)
              "nihilite-test" :dumb)]
      (is (= "dumb" (.getType ^ExternalTerminal t))
          ":dumb keyword maps to terminfo profile `dumb`"))))

(deftest terminal-size-from-columns-env
  (testing "terminal size reads COLUMNS env; defaults to 80 if unset/invalid."
    (let [t (term/build-terminal
              (ByteArrayInputStream. (byte-array 0))
              (ByteArrayOutputStream.)
              "nihilite-test")]
      (let [size (.getSize ^ExternalTerminal t)]
        (is (pos? (.getColumns size))
            "columns is positive (COLUMNS env or 80 default)")))))

(deftest build-reader-forces-packed-list-on-dumb-terminal
  (testing "build-reader sets LIST_PACKED + LIST_ROWS_FIRST when the
            underlying terminal type is `dumb` (nc/socat)."
    (let [t (term/build-terminal
              (ByteArrayInputStream. (byte-array 0))
              (ByteArrayOutputStream.)
              "nihilite-test" :dumb)
          ;; build-reader needs an atom with :ns key; the completer
          ;; resolves the current ns.
          repl-state (atom {:ns (find-ns 'user)})
          reader (term/build-reader t repl-state)]
      (is (true? (.isSet reader org.jline.reader.LineReader$Option/LIST_PACKED))
          "LIST_PACKED set on dumb terminals")
      (is (true? (.isSet reader org.jline.reader.LineReader$Option/LIST_ROWS_FIRST))
          "LIST_ROWS_FIRST set on dumb terminals"))))

(deftest build-reader-leaves-xterm-options-alone
  (testing "build-reader does NOT touch LIST_PACKED on xterm terminals
            (real telnet keeps the multi-column renderer)."
    (let [t (term/build-terminal
              (ByteArrayInputStream. (byte-array 0))
              (ByteArrayOutputStream.)
              "nihilite-test")
          repl-state (atom {:ns (find-ns 'user)})
          reader (term/build-reader t repl-state)]
      (is (false? (.isSet reader org.jline.reader.LineReader$Option/LIST_PACKED))
          "LIST_PACKED is unchanged on xterm"))))