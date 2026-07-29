(ns nihilite.readline.terminal
  "jline3 terminal + reader construction. Owns the ExternalTerminal
   pump thread and the LineReaderBuilder chain. History sync
   between the server-wide deque and jline3's per-reader
   DefaultHistory lives here too."
  (:require [nihilite.readline.completion :as completion]
            [nihilite.readline.history :as hist])
  (:import [java.io InputStream OutputStream]
           [java.nio.charset StandardCharsets]
           [org.jline.terminal Terminal Terminal$SignalHandler]
           [org.jline.terminal.impl ExternalTerminal]
           [org.jline.reader LineReader LineReaderBuilder]
           [org.jline.reader.impl DefaultParser]
           [org.jline.reader.impl.history DefaultHistory]))

(defn build-terminal
  "Build jline3 ExternalTerminal (xterm) over socket streams; SIG_IGN for SIGINT."
  ^Terminal [^InputStream in ^OutputStream out ^String name]
  (let [term (ExternalTerminal.
               nil
               name
               "xterm"
               in
               out
               StandardCharsets/UTF_8
               Terminal$SignalHandler/SIG_IGN
               false)]
    (.resume term)
    term))

(defn build-reader
  "Build LineReader bound to terminal; Clojure-aware TAB completer; history synced from shared deque."
  ^LineReader [^Terminal terminal repl-state]
  (let [current-ns (:ns @repl-state)
        completer  (completion/completer-for current-ns)
        history    (DefaultHistory.)
        reader (-> (LineReaderBuilder/builder)
                   (.terminal terminal)
                   (.completer completer)
                   (.parser (DefaultParser.))
                   (.history history)
                   (.build))]
    reader))

(defn sync-history-into-reader!
  "Purge the reader's jline history and reload it from the shared
   server-wide deque so Up/Down and C-r see cross-socket entries.
   O(n), n ≤ 1000."
  [^LineReader reader]
  (let [h (.getHistory reader)]
    (.purge h)
    (doseq [entry (hist/history-entries)]
      (.add h ^String entry))))
