(ns nihilite.readline.terminal
  "jline3 terminal + reader construction. Owns the ExternalTerminal
   pump thread and the LineReaderBuilder chain."
  (:require [nihilite.readline.completion :as completion]
            [nihilite.readline.history :as hist])
  (:import [java.io InputStream OutputStream]
           [java.nio.charset StandardCharsets]
           [org.jline.terminal Terminal Terminal$SignalHandler
                                Size]
           [org.jline.terminal.impl ExternalTerminal]
           [org.jline.reader LineReader LineReader LineReaderBuilder
                              LineReader$Option]
           [org.jline.reader.impl DefaultParser]
           [org.jline.reader.impl.history DefaultHistory]))

(def ^:const dumb-terminal-type "dumb")
(def ^:const xterm-terminal-type "xterm")

(defn- columns-default
  "Resolve terminal width: COLUMNS env if set and positive, else 80."
  ^long []
  (let [v (System/getenv "COLUMNS")]
    (if-let [n (and v (try (Long/parseLong v) (catch Throwable _ nil)))]
      (if (pos? n) (long n) 80)
      80)))

(defn- ->terminal-type
  "Normalize the terminal-type argument to a String. Accepts the
   pre-existing default value (a String) or a keyword."
  ^String [t]
  (cond
    (keyword? t) (case t
                   :dumb  dumb-terminal-type
                   :xterm xterm-terminal-type
                   (name t))
    :else (or t xterm-terminal-type)))

(defn build-terminal
  "Build jline3 ExternalTerminal over socket streams; SIG_IGN for
   SIGINT. Terminal type defaults to `xterm`; pass `:dumb` for nc/socat
   clients which lack ANSI/ECMA-48 support. Size defaults to
   COLUMNS env (fallback 80) x 24."
  ^Terminal ([^InputStream in ^OutputStream out ^String name]
             (build-terminal in out name :xterm))
  ([^InputStream in ^OutputStream out ^String name terminal-type]
   (let [type-str (->terminal-type terminal-type)
         term (ExternalTerminal.
                nil
                name
                type-str
                in
                out
                StandardCharsets/UTF_8
                Terminal$SignalHandler/SIG_IGN
                false)]
     (.setSize term (Size. (int (columns-default)) 24))
     (.resume term)
     term)))

(defn build-reader
  "Build LineReader bound to terminal; Clojure-aware TAB completer;
   history synced from shared deque. On `dumb` terminals, force
   LIST_PACKED + LIST_ROWS_FIRST so jline3's multi-column list renderer
   does not assume NAWS / clr_eol support and staircases across columns."
  ^LineReader [^Terminal terminal repl-state]
  (let [current-ns (:ns @repl-state)
        completer  (completion/completer-for current-ns)
        history    (DefaultHistory.)
        reader (-> (LineReaderBuilder/builder)
                   (.terminal terminal)
                   (.completer completer)
                   (.parser (DefaultParser.))
                   (.history history)
                   (.option LineReader$Option/DISABLE_EVENT_EXPANSION true)
                   (.build))]
    (when (= dumb-terminal-type (.getType ^ExternalTerminal terminal))
      (.setOpt reader LineReader$Option/LIST_PACKED)
      (.setOpt reader LineReader$Option/LIST_ROWS_FIRST))
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
